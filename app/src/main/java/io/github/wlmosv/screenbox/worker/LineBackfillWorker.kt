package io.github.wlmosv.screenbox.worker

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.TaskStateEntity
import io.github.wlmosv.screenbox.data.ocr.MlKitOcrSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * 行级坐标回填（可暂停、可恢复、可重复执行）。
 *
 * ══ 为什么必须是后台任务 ══
 *
 * R8/R9 只回填了 155 张，因为入口是设置页的一个按钮：点一次跑 40 张，
 * 用户要守在页面上点 200 次。这不是功能，是苦力。
 *
 * 全量 OCR 大约 40 分钟。普通 WorkManager 单次执行有约 10 分钟上限，
 * 所以这里采用「单次有界 + 自我续排」，而不是一次跑完：
 *
 *   · 每轮最多 8 分钟，到点就 return success，并 APPEND 下一次
 *   · 进度按数据库真实剩余量算，进程被杀也不丢
 *   · 已写入 lineBounds 的行天然不会被再次选中（SQL 条件保证幂等）
 *   · 整个批次 0 成功（如权限被撤销）时标记 FAILED，不无限重排
 *
 * 暂停 = cancelUniqueWork，状态置 PAUSED；继续 = 重新入队。
 */
class LineBackfillWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "screenbox_line_backfill"
        const val TASK_KIND = "line_backfill"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_REMAINING = "remaining"

        /** 单张之间留一点空，避免 ML Kit 连续调用把 CPU 打满。 */
        private const val BETWEEN_MS = 60L

        /** 单轮时间预算，留足余量给系统 10 分钟上限。 */
        private const val RUN_BUDGET_MS = 8 * 60 * 1000L

        /** 每批读取的目标数量。 */
        private const val BATCH = 20

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<LineBackfillWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag(WORK_NAME)
                .build()
            // REPLACE：用户点「继续」时，旧的排队实例没有意义。
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }

        fun pause(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** 从任意入口触发续跑；已有任务在跑时 REPLACE 会重启它，所以调用方先看状态。 */
        fun resume(context: Context) = enqueue(context)
    }

    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        val db = Graph.db
        val ocrDao = db.ocrDao()
        val stateDao = db.taskStateDao()
        val now = System.currentTimeMillis()

        val remainingAtStart = ocrDao.countMissingLineBounds()
        if (remainingAtStart <= 0) {
            stateDao.put(
                TaskStateEntity(
                    kind = TASK_KIND,
                    status = "COMPLETED",
                    totalCount = 0,
                    doneCount = 0,
                    updatedAt = now,
                )
            )
            setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to 0, KEY_REMAINING to 0))
            return Result.success()
        }

        // 基线：同一轮任务集内 total 不变，done = total - 当前剩余。
        // 这样跨进程、跨多次 doWork 的进度是单调的，不会因为重启归零。
        val prev = stateDao.get(TASK_KIND)
        val resumable = prev != null &&
            prev.status != "COMPLETED" &&
            prev.totalCount >= remainingAtStart &&
            prev.totalCount > 0
        val total = if (resumable) prev!!.totalCount else remainingAtStart
        val failed = if (resumable) prev!!.failedCount else 0

        stateDao.put(
            TaskStateEntity(
                kind = TASK_KIND,
                status = "RUNNING",
                totalCount = total,
                doneCount = (total - remainingAtStart).coerceAtLeast(0),
                failedCount = failed,
                startedAt = prev?.startedAt ?: now,
                updatedAt = now,
            )
        )

        val src = MlKitOcrSource(applicationContext)
        var done = (total - remainingAtStart).coerceAtLeast(0)
        var failedTotal = failed
        var failedThisRun = 0
        var successThisRun = 0
        var firstError: String? = null
        var skippedThisRun = 0
        var skippedTotal = if (resumable) prev!!.skippedCount else 0
        val startedAt = System.currentTimeMillis()

        try {
            while (true) {
                if (System.currentTimeMillis() - startedAt > RUN_BUDGET_MS) break

                val targets = ocrDao.idsMissingLineBounds(BATCH)
                if (targets.isEmpty()) break

                var batchSuccess = 0
                var batchSkipped = 0
                for (t in targets) {
                    val attempt = src.recognize(Uri.parse(t.uri))
                    val r = attempt.getOrNull()
                    if (r == null) {
                        // 记下第一条真实原因，方便判断是解码失败、权限还是模型问题。
                        if (firstError == null) {
                            firstError = attempt.exceptionOrNull()
                                ?.let { "${it.javaClass.simpleName}: ${it.message}" }
                                ?: "unknown"
                        }
                        // 真失败：读不出图、权限被撤、解码错误。
                        // 写空串哨兵，标记「已尝试且失败」。
                        // 否则 lineBounds 一直是 NULL，下一轮又被选中，
                        // 这批坏图会无限重试并把 failed 计数越滚越大。
                        ocrDao.setLineBounds(t.id, "")
                        failedThisRun++
                        failedTotal++
                    } else {
                        // 识别成功。无文字时 lineBoundsJson 为 null，
                        // 这不是失败 —— 空白图本来就没有行。
                        // 写空数组标记「已处理」，否则会被无限重试。
                        val json = r.lineBoundsJson ?: "[]"
                        ocrDao.setLineBounds(t.id, json)
                        if (r.lineBoundsJson == null) {
                            skippedThisRun++
                            skippedTotal++
                        } else {
                            done++
                            batchSuccess++
                            successThisRun++
                        }
                    }
                    delay(BETWEEN_MS)
                }

                val remaining = ocrDao.countMissingLineBounds()
                stateDao.updateCounts(
                    TASK_KIND,
                    done = (total - remaining).coerceAtLeast(0),
                    failed = failedTotal,
                    skipped = skippedTotal,
                    now = System.currentTimeMillis(),
                )
                setProgress(
                    workDataOf(
                        KEY_DONE to done,
                        KEY_TOTAL to total,
                        KEY_REMAINING to remaining,
                    )
                )

                // 整批全失败：权限被撤销、文件被删、模型不可用。
                // 继续循环只会空转，直接停下并留下错误原因。
                // 只有「读不出图」才算失败；空白图记 skipped，不算失败。
                if (batchSuccess == 0 && batchSkipped == 0) {
                    stateDao.setError(
                        TASK_KIND,
                        "最近 $BATCH 张全部无法读取，已停止。请检查照片权限后重试。",
                        System.currentTimeMillis(),
                    )
                    stateDao.updateStatus(TASK_KIND, "FAILED", System.currentTimeMillis())
                    return Result.success()
                }
                if (remaining <= 0) break
            }
        } catch (e: CancellationException) {
            // 用户暂停或系统回收。保留已完成的进度，状态置为可继续。
            stateDao.updateStatus(TASK_KIND, "PAUSED", System.currentTimeMillis())
            throw e
        } finally {
            src.close()
        }

        val remaining = ocrDao.countMissingLineBounds()
        val finalNow = System.currentTimeMillis()
        if (remaining <= 0) {
            stateDao.updateCounts(
                TASK_KIND,
                done = total,
                failed = failedTotal,
                skipped = skippedTotal,
                now = finalNow,
            )
            stateDao.updateStatus(TASK_KIND, "COMPLETED", finalNow)
            stateDao.setError(
                TASK_KIND,
                firstError?.let { "有 $failedTotal 张读取失败，首个原因：$it" },
                finalNow,
            )
        } else {
            // 时间预算用完但还有活：续排下一次，状态保持 RUNNING。
            stateDao.updateCounts(
                TASK_KIND,
                done = (total - remaining).coerceAtLeast(0),
                failed = failedTotal,
                skipped = skippedTotal,
                now = finalNow,
            )
            if (failedTotal > 0 && firstError != null) {
                stateDao.setError(TASK_KIND, "有 $failedTotal 张读取失败，首个原因：$firstError", finalNow)
            }
            enqueueNext()
        }

        // 全部读不出才算失败；全是空白图（skipped）是正常结果。
        // 每轮结束都把真实计数写进 lastError，方便判断"卡住"是调度问题还是数据问题。
        // 空串会覆盖为 null，UI 就不会显示噪音。
        if (failedTotal == 0 && remaining > 0) {
            stateDao.setError(
                TASK_KIND,
                "本轮完成 $successThisRun 张，空白 $skippedThisRun 张，剩余 $remaining 张",
                finalNow,
            )
        }
        if (successThisRun == 0 && skippedThisRun == 0 && failedThisRun > 0) {
            return Result.failure(
                workDataOf("error" to "本轮没有成功回填任何图片，剩余 $remaining 张")
            )
        }
        return Result.success(
            workDataOf(KEY_DONE to (total - remaining), KEY_TOTAL to total, KEY_REMAINING to remaining)
        )
    }

    private fun enqueueNext() {
        val req = OneTimeWorkRequestBuilder<LineBackfillWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(WORK_NAME)
            .build()
        // APPEND_OR_REPLACE：接在当前实例之后，避免把正在收尾的自己顶掉。
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            req,
        )
    }
}
