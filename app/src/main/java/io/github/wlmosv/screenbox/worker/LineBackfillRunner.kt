package io.github.wlmosv.screenbox.worker

import android.content.Context
import android.net.Uri
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.TaskStateEntity
import io.github.wlmosv.screenbox.data.ocr.MlKitOcrSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 行级坐标回填的前台快跑通道。
 *
 * ══ 为什么需要第二条通道 ══
 *
 * R10 实测（ColorOS / Android 15）：WorkManager 后台任务的真实吞吐是
 * 150 秒 4 张 —— 比 R9 记录的 3 张/秒慢两个数量级。
 * 原因是系统在应用进入 cached 状态后限制后台执行，
 * `dumpsys jobscheduler` 里对应 job 的 Ready=false。
 *
 * 7930 张按这个速度要 80 小时，等于没有。
 *
 * 所以拆成两条通道：
 *   · 前台（本文件）：用户停在设置页时全速跑，进程活着就一直跑
 *   · 后台（LineBackfillWorker）：用户离开后接力，慢但不丢进度
 *
 * 两条通道共用同一张 task_state 与同一个 target 查询，
 * 靠「写入后 lineBounds 不再为 NULL」天然去重，不会重复识别同一张。
 */
object LineBackfillRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 每批处理数量。前台可以小一点，让进度更新更密。 */
    private const val BATCH = 8
    private const val BETWEEN_MS = 40L

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 开始前台回填。
     *
     * @param onTick 每批结束回调（已处理数、剩余数），用于刷新界面
     */
    fun start(context: Context, onTick: (done: Int, remaining: Int) -> Unit) {
        if (isRunning) return
        val app = context.applicationContext
        // 前台接手时把后台任务停掉，避免两个通道抢同一批 id。
        LineBackfillWorker.pause(app)

        job = scope.launch {
            Graph.init(app)
            val db = Graph.db
            val ocrDao = db.ocrDao()
            val stateDao = db.taskStateDao()
            val now = System.currentTimeMillis()

            val remainingAtStart = ocrDao.countMissingLineBounds()
            if (remainingAtStart <= 0) {
                stateDao.put(
                    TaskStateEntity(
                        kind = LineBackfillWorker.TASK_KIND,
                        status = "COMPLETED",
                        totalCount = 0,
                        updatedAt = now,
                    )
                )
                onTick(0, 0)
                return@launch
            }

            val prev = stateDao.get(LineBackfillWorker.TASK_KIND)
            val total = if (prev != null && prev.totalCount >= remainingAtStart && prev.totalCount > 0) {
                prev.totalCount
            } else {
                remainingAtStart
            }
            var failedTotal = prev?.failedCount ?: 0
            var skippedTotal = prev?.skippedCount ?: 0

            stateDao.put(
                TaskStateEntity(
                    kind = LineBackfillWorker.TASK_KIND,
                    status = "RUNNING",
                    totalCount = total,
                    doneCount = (total - remainingAtStart).coerceAtLeast(0),
                    failedCount = failedTotal,
                    skippedCount = skippedTotal,
                    startedAt = prev?.startedAt ?: now,
                    updatedAt = now,
                )
            )

            val src = MlKitOcrSource(app)
            try {
                while (isActive) {
                    val targets = ocrDao.idsMissingLineBounds(BATCH)
                    if (targets.isEmpty()) break

                    for (t in targets) {
                        if (!isActive) break
                        val attempt = src.recognize(Uri.parse(t.uri))
                        val r = attempt.getOrNull()
                        if (r == null) {
                            // 读不出来：写空串哨兵，标记「试过且失败」，避免无限重试。
                            ocrDao.setLineBounds(t.id, "")
                            failedTotal++
                        } else {
                            val json = r.lineBoundsJson
                            if (json == null) {
                                // 空白图：正常结果，不是失败。
                                ocrDao.setLineBounds(t.id, "[]")
                                skippedTotal++
                            } else {
                                ocrDao.setLineBounds(t.id, json)
                            }
                        }
                        delay(BETWEEN_MS)
                    }

                    val remaining = ocrDao.countMissingLineBounds()
                    val done = (total - remaining).coerceAtLeast(0)
                    stateDao.updateCounts(
                        LineBackfillWorker.TASK_KIND,
                        done = done,
                        failed = failedTotal,
                        skipped = skippedTotal,
                        now = System.currentTimeMillis(),
                    )
                    onTick(done, remaining)
                    if (remaining <= 0) break
                }

                val remaining = ocrDao.countMissingLineBounds()
                if (remaining <= 0) {
                    stateDao.updateCounts(
                        LineBackfillWorker.TASK_KIND,
                        done = total,
                        failed = failedTotal,
                        skipped = skippedTotal,
                        now = System.currentTimeMillis(),
                    )
                    stateDao.updateStatus(
                        LineBackfillWorker.TASK_KIND,
                        "COMPLETED",
                        System.currentTimeMillis(),
                    )
                    stateDao.setError(LineBackfillWorker.TASK_KIND, null, System.currentTimeMillis())
                } else {
                    // 被暂停或进程要退出：交给后台通道接力。
                    LineBackfillWorker.enqueue(app)
                }
            } catch (e: CancellationException) {
                stateDao.updateStatus(
                    LineBackfillWorker.TASK_KIND,
                    "PAUSED",
                    System.currentTimeMillis(),
                )
                throw e
            } finally {
                src.close()
            }
        }
    }

    /** 停止前台回填（用户点暂停）。 */
    fun stop() {
        job?.cancel()
        job = null
    }
}
