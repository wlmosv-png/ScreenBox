package io.github.wlmosv.screenbox.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.repo.OcrQueuePolicy
import java.util.concurrent.TimeUnit

/**
 * OCR 后台任务。
 *
 * Android 15 的后台限制（实测结论）：
 * - 普通 WorkManager 任务在 Doze / 应用待机分组里会被推迟，不保证连续跑。
 * - 系统对单次任务执行有上限（约 10 分钟），超时会被杀。
 * - 因此这里必须"分批 + 每次有界"，靠 WorkManager 重新调度续跑。
 *
 * 设计：
 * - 每次最多处理 MAX_PER_RUN 张，处理完就 return success。
 * - 用 ocrStatus 判断是否还有活，有就 enqueue 下一次。
 * - 因为状态全在数据库里，被系统杀掉后重启不丢进度。
 * - ExistingWorkPolicy.KEEP 保证不会重复入队。
 */
class OcrWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "screenbox_ocr"
        const val KEY_POLICY = "policy"
        const val KEY_MAX_COUNT = "max_count"
        const val KEY_PROGRESS_DONE = "progress_done"

        /** 单次任务处理上限。按 3 张/秒算，600 张约 3.5 分钟，留足余量。 */
        private const val MAX_PER_RUN = 600

        fun enqueue(context: Context, policy: OcrQueuePolicy = OcrQueuePolicy.RECENT_FIRST) {
            val req = OneTimeWorkRequestBuilder<OcrWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInputData(workDataOf(KEY_POLICY to policy.name))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                req,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        Graph.init(applicationContext)

        val policy = runCatching {
            OcrQueuePolicy.valueOf(inputData.getString(KEY_POLICY) ?: OcrQueuePolicy.RECENT_FIRST.name)
        }.getOrDefault(OcrQueuePolicy.RECENT_FIRST)

        return runCatching {
            Graph.ocrRepo.run(maxCount = MAX_PER_RUN, policy = policy)

            // 还有活就续跑。用数据库真实状态判断，不靠内存标记。
            val remaining = Graph.db.screenshotDao().pendingOcrCount()
            if (remaining > 0) {
                enqueue(applicationContext, policy)
            }
            Result.success(workDataOf(KEY_PROGRESS_DONE to remaining))
        }.getOrElse { e ->
            // 失败重试交给 WorkManager 退避，最多由系统决定次数
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/**
 * 指纹后台任务。与 OCR 独立，互不阻塞。
 */
class FingerprintWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "screenbox_fingerprint"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<FingerprintWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                req,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        return runCatching {
            Graph.scanEngine.runFingerprint()
            val remaining = Graph.db.screenshotDao().countPendingHash()
            if (remaining > 0) enqueue(applicationContext)
            Result.success()
        }.getOrElse {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
