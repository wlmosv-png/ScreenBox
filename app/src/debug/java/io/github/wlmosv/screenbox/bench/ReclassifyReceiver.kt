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
 * 重分类触发器（debug only）。
 *
 * adb shell am broadcast -a io.github.wlmosv.screenbox.RECLASSIFY \
 *   -n io.github.wlmosv.screenbox/.bench.ReclassifyReceiver
 *
 * 结果落文件，便于核对分类质量。
 */
class ReclassifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context.applicationContext)
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val sb = StringBuilder()
            sb.appendLine("=== 重分类结果 ===")
            sb.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            val t0 = System.currentTimeMillis()
            val last = arrayOfNulls<io.github.wlmosv.screenbox.data.repo.ClassifyRepository.Progress>(1)
            runCatching {
                Graph.classifyRepo.reclassifyAll { p -> last[0] = p }
            }.onFailure {
                sb.appendLine("失败: ${it.message}")
                File(app.getExternalFilesDir(null), "reclassify.txt").writeText(sb.toString())
                return@launch
            }
            val elapsed = (System.currentTimeMillis() - t0) / 1000.0
            val p = last[0]
            sb.appendLine("耗时: %.1f 秒".format(elapsed))
            sb.appendLine("处理: ${p?.done ?: 0} / ${p?.total ?: 0}")
            sb.appendLine("有分类: ${p?.classified ?: 0}")
            sb.appendLine("来源已识别: ${p?.sourceIdentified ?: 0}")
            sb.appendLine("来源不明: ${p?.sourceUnknown ?: 0}")
            sb.appendLine()

            // 分类分布
            sb.appendLine("--- 内容分类分布 ---")
            runCatching {
                Graph.db.categoryDao().countByCategory().forEach { c ->
                    sb.appendLine("  ${c.displayName}: ${c.cnt}")
                }
            }
            sb.appendLine()
            sb.appendLine("--- 来源应用分布 ---")
            runCatching {
                Graph.db.sourceAppDao().countByApp().forEach { a ->
                    sb.appendLine("  ${a.displayName}: ${a.cnt}")
                }
            }
            val out = sb.toString()
            File(app.getExternalFilesDir(null), "reclassify.txt").writeText(out)
            android.util.Log.i("SCREENBOX_RECLASS", out)
        }
    }
}
