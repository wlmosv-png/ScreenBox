package io.github.wlmosv.screenbox.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.wlmosv.screenbox.core.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 图片分析任务触发器（debug only）。
 *
 * adb shell am broadcast -a io.github.wlmosv.screenbox.RUN_ANALYSIS \
 *   -n io.github.wlmosv.screenbox/.bench.AnalysisReceiver --ei count 500
 */
class AnalysisReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context.applicationContext)
        val app = context.applicationContext
        val count = intent.getIntExtra("count", 0)

        // 用 GlobalScope 避免 Receiver 返回后被取消
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            sb.appendLine("=== 图片分析任务 ===")
            val t0 = System.currentTimeMillis()
            runCatching {
                Graph.analysisRepo.run(maxCount = count)
            }.onFailure {
                sb.appendLine("失败: ${it.message}")
            }
            val elapsed = (System.currentTimeMillis() - t0) / 1000.0
            sb.appendLine("耗时: %.1f 秒".format(elapsed))

            // 统计熵值分布
            val dist = runCatching {
                Graph.db.query(
                    """
                    SELECT
                      SUM(CASE WHEN imageEntropy < 0.25 THEN 1 ELSE 0 END) AS blank,
                      SUM(CASE WHEN imageEntropy >= 0.25 AND imageEntropy < 0.40 THEN 1 ELSE 0 END) AS low,
                      SUM(CASE WHEN imageEntropy >= 0.40 THEN 1 ELSE 0 END) AS normal,
                      COUNT(*) AS total
                    FROM screenshots WHERE imageEntropy IS NOT NULL AND imageEntropy >= 0
                    """.trimIndent(),
                    emptyArray(),
                ).use { c ->
                    if (c.moveToFirst()) {
                        "近乎空白=${c.getInt(0)} 低信息=${c.getInt(1)} 正常=${c.getInt(2)} 总计=${c.getInt(3)}"
                    } else "无数据"
                }
            }.getOrDefault("查询失败")
            sb.appendLine("熵值分布: $dist")

            // 重建建议
            runCatching { Graph.duplicateRepo.rebuild() }
            runCatching { Graph.suggestionRepo.rebuild() }
            sb.appendLine()
            sb.appendLine("=== 清理建议类型 ===")
            runCatching {
                Graph.suggestionRepo.summary().forEach {
                    sb.appendLine("  ${it.type.label}: ${it.count} 张" +
                        (if (it.groupCount > 0) "（${it.groupCount} 组）" else "") +
                        (if (it.bytes > 0) "，${it.bytes / 1024 / 1024} MB" else ""))
                }
            }

            val out = sb.toString()
            File(app.getExternalFilesDir(null), "analysis.txt").writeText(out)
            android.util.Log.i("SCREENBOX_ANALYSIS", out)
        }
    }
}
