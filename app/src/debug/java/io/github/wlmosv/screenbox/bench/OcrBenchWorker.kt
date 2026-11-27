package io.github.wlmosv.screenbox.bench

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.repo.OcrQueuePolicy
import java.io.File

/**
 * 无界面 OCR 基准。
 *
 * 为什么需要它：
 * 之前用 Activity 跑基准，用户一碰手机就被切走，测不准。
 * 这个 Worker 没有 UI，进程在后台也能跑完，结果落文件。
 *
 * 只测 120 张，约 40 秒（3 张/秒），不干扰正常使用。
 */
class OcrBenchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        val sb = StringBuilder()

        return runCatching {
            val db = Graph.db
            val dao = db.screenshotDao()

            sb.appendLine("=== OCR v2 吞吐基准（Worker 版）===")
            sb.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            sb.appendLine("起始温度: ${"%.1f".format(temp())}°C")
            sb.appendLine("起始 RSS: ${rss()}MB")
            sb.appendLine("起始已识别: ${dao.count()} 张入库 / ${dao.pendingOcrCount()} 张待识别")
            sb.appendLine()

            // 用真实待识别队列，不构造假数据
            val before = System.currentTimeMillis()
            val doneBefore = runCatching { countRecognized(db) }.getOrDefault(0)

            // 跑 120 张
            Graph.ocrRepo.run(maxCount = 120, policy = OcrQueuePolicy.RECENT_FIRST)

            val elapsed = (System.currentTimeMillis() - before) / 1000.0
            val doneAfter = runCatching { countRecognized(db) }.getOrDefault(0)
            val actuallyDone = doneAfter - doneBefore
            val throughput = if (elapsed > 0) actuallyDone / elapsed else 0.0

            sb.appendLine("--- 结果 ---")
            sb.appendLine("耗时: %.1f 秒".format(elapsed))
            sb.appendLine("实际完成: $actuallyDone 张")
            sb.appendLine("吞吐: %.2f 张/秒".format(throughput))
            sb.appendLine("结束温度: %.1f°C".format(temp()))
            sb.appendLine("结束 RSS: ${rss()}MB")
            sb.appendLine()
            sb.appendLine("剩余待识别: ${dao.pendingOcrCount()}")
            sb.appendLine("失败数: ${dao.failedOcrCount()}")

            val out = sb.toString()
            File(applicationContext.getExternalFilesDir(null), "ocr_v2_bench.txt").writeText(out)
            Result.success()
        }.getOrElse {
            sb.appendLine("失败: ${it.message}")
            runCatching {
                File(applicationContext.getExternalFilesDir(null), "ocr_v2_bench.txt").writeText(sb.toString())
            }
            Result.failure()
        }
    }

    private suspend fun countRecognized(db: io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase): Int {
        return db.screenshotDao().recognizedCount()
    }

    private fun temp(): Double {
        var max = 0.0
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.forEach { z ->
            runCatching {
                val t = File(z, "temp").readText().trim().toDouble() / 1000.0
                if (t > 0 && t < 120 && t > max) max = t
            }
        }
        return max
    }

    private fun rss(): Int = runCatching {
        File("/proc/self/status").readLines()
            .first { it.startsWith("VmRSS") }
            .split(Regex("\\s+"))[1].toInt() / 1024
    }.getOrDefault(0)
}
