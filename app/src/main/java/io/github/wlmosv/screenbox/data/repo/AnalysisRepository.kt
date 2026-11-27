package io.github.wlmosv.screenbox.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase
import io.github.wlmosv.screenbox.data.db.TaskStateEntity
import io.github.wlmosv.screenbox.data.media.ImageComplexity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 图片分析任务：SHA-256 + pHash + 图像熵。
 *
 * ══ R5 重写：计数改为从数据库实时推导 ══
 *
 * 历史教训（连续两轮踩同一个坑）：
 *   R3 用累加 → syncCounts 被调多次导致重复累加，8137 张显示成 159248
 *   R4 加 SQL MIN() 夹紧 → 数字不再溢出，但 done 触顶后永远显示"已完成"
 *        而数据库里还有 1237 张没处理
 *
 * 根因：**累加模型本身就是错的**。
 * 只要有一次重复调用、一次进程被杀、一次状态回退，累加值就永久失真。
 *
 * R5 方案：不累加。每次更新进度都从数据库重新统计真实值。
 *   doneCount   = 已有熵值的图片数
 *   totalCount  = 有熵值的 + 待处理的
 *   failedCount = 熵值为 -1（解码失败）的
 *
 * 这样无论任务怎么中断、重启、重跑，显示的数字永远等于数据库真实状态。
 * 代价是每次更新多一次 COUNT 查询（万级数据量下 <5ms，可接受）。
 */
class AnalysisRepository(
    private val context: Context,
    private val db: ScreenBoxDatabase,
) {

    companion object {
        const val KIND = "analysis"

        /** 每批处理多少张。 */
        private const val BATCH = 40

        /** 进度更新间隔（每处理多少张刷新一次库）。 */
        private const val SYNC_EVERY = 10
    }

    private val taskDao = db.taskStateDao()

    /**
     * 执行图片分析。
     *
     * @param maxCount 本次最多处理多少张，0 = 直到队列空
     */
    suspend fun run(
        maxCount: Int = 0,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val dao = db.screenshotDao()

        // 启动时先重置残留的 PROCESSING 类状态（本任务没有，但保持一致）
        val pendingAtStart = dao.countPendingAnalysis()
        if (pendingAtStart == 0) {
            publishProgress(TaskStatus.COMPLETED)
            return@withContext
        }

        publishProgress(TaskStatus.RUNNING)

        var processedThisRun = 0

        try {
            while (true) {
                if (maxCount > 0 && processedThisRun >= maxCount) break
                coroutineContext.ensureActive()

                val batch = dao.nextAnalysisBatch(BATCH)
                if (batch.isEmpty()) break

                for (shot in batch) {
                    coroutineContext.ensureActive()
                    val uri = Uri.parse(shot.contentUri)

                    runCatching {
                        db.withTransaction {
                            // SHA-256（只算一次）
                            if (shot.sha256 == null) {
                                val sha = io.github.wlmosv.screenbox.data.media.Hashing.sha256(context, uri)
                                if (sha != null) {
                                    dao.setSha256(shot.id, sha, System.currentTimeMillis())
                                }
                            }
                            // pHash
                            if (shot.perceptualHash == null) {
                                val phash = io.github.wlmosv.screenbox.data.media.Hashing.perceptualHash(context, uri)
                                if (phash != null) {
                                    dao.setPhash(shot.id, phash, System.currentTimeMillis())
                                }
                            }
                            // 图像复杂度
                            if (shot.imageEntropy == null) {
                                val cx = ImageComplexity.analyze(context, uri)
                                if (cx != null) {
                                    dao.setImageComplexity(
                                        shot.id, cx.entropy, cx.colorVariance,
                                        System.currentTimeMillis(),
                                    )
                                } else {
                                    // 解码失败也要标记，避免无限重试
                                    dao.setImageComplexity(shot.id, -1f, -1f, System.currentTimeMillis())
                                }
                            }
                        }
                    }

                    processedThisRun++

                    if (processedThisRun % SYNC_EVERY == 0) {
                        publishProgress(TaskStatus.RUNNING)
                        onProgress?.invoke(processedThisRun, -1)
                    }
                }

                publishProgress(TaskStatus.RUNNING)
            }

            // 结算：从数据库真实状态判定
            val remaining = dao.countPendingAnalysis()

            // 分析完成后刷新特征标签（熵/尺寸是标签的输入）
            runCatching { TagRepository(db).rebuildIncremental(limit = 8000) }

            publishProgress(if (remaining == 0) TaskStatus.COMPLETED else TaskStatus.PAUSED)
        } catch (e: kotlinx.coroutines.CancellationException) {
            publishProgress(TaskStatus.PAUSED)
            throw e
        } catch (t: Throwable) {
            taskDao.setError(KIND, t.message?.take(200), System.currentTimeMillis())
            publishProgress(TaskStatus.FAILED)
        }
    }

    /**
     * 把数据库真实状态写成任务进度。
     *
     * 这是 R5 的核心改动：不累加、不夹紧，纯粹反映现实。
     */
    private suspend fun publishProgress(status: TaskStatus) {
        val dao = db.screenshotDao()
        val done = dao.countAnalyzed()        // 有熵值且 >= 0
        val failed = dao.countAnalysisFailed() // 熵值 = -1
        val pending = dao.countPendingAnalysis()

        taskDao.put(
            TaskStateEntity(
                kind = KIND,
                status = status.name,
                totalCount = done + failed + pending,
                doneCount = done,
                failedCount = failed,
                skippedCount = 0,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
}