package io.github.wlmosv.screenbox.worker

import android.content.Context
import androidx.work.WorkManager
import io.github.wlmosv.screenbox.core.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 后台任务的进程重启恢复。
 *
 * ══ 真实问题（R10 实测）══
 *
 * WorkManager 的队列确实在数据库里，但「续排」这一环只写在
 * doWork() 内部：系统把进程杀掉后，没有人再调用 enqueueNext()，
 * 任务就永久停在 RUNNING，进度条不动，用户以为卡死。
 *
 * 实测证据：task_state 里 RUNNING 7916 / done 595，等待 2 分钟
 * 计数不再增长，dumpsys jobscheduler 里对应 job 的 Ready=false。
 *
 * ══ 修复 ══
 *
 * Application 启动时读 task_state：
 *   · 状态是 RUNNING/STOPPING（上次被异常终止）→ 校正为 PAUSED 后重新入队
 *   · 状态是 PAUSED 且用户没喊停 → 不动，等用户点继续
 *   · COMPLETED / FAILED / IDLE → 不动
 *
 * 只在「进程刚起来」时执行一次，不在每次 onResume 触发，
 * 避免和用户手动暂停打架。
 */
object TaskRecovery {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun resumeInterrupted(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                Graph.init(app)
                val dao = Graph.db.taskStateDao()
                val st = dao.get(LineBackfillWorker.TASK_KIND) ?: return@launch
                val remaining = Graph.db.ocrDao().countMissingLineBounds()
                if (remaining <= 0) {
                    dao.updateStatus(LineBackfillWorker.TASK_KIND, "COMPLETED", System.currentTimeMillis())
                    return@launch
                }
                val interrupted = st.status == "RUNNING" || st.status == "STOPPING"
                if (!interrupted) return@launch

                // 上次是被系统杀掉的：先标记暂停，再重新排队。
                // 顺序不能反 —— 先入队后改状态会让 doWork 读到 RUNNING 就重置基线。
                dao.updateStatus(LineBackfillWorker.TASK_KIND, "PAUSED", System.currentTimeMillis())

                // 已经有排队实例时 REPLACE 会重启它，不会重复跑。
                val queued = WorkManager.getInstance(app)
                    .getWorkInfosForUniqueWork(LineBackfillWorker.WORK_NAME)
                    .get()
                    .any { !it.state.isFinished }
                if (!queued) {
                    LineBackfillWorker.enqueue(app)
                }
            }
        }
    }
}
