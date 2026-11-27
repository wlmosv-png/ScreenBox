package io.github.wlmosv.screenbox.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.domain.rules.LayoutAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 行级坐标回填探针（debug only）。
 *
 * adb shell am broadcast -a io.github.wlmosv.screenbox.BACKFILL_LINES \
 *   -n io.github.wlmosv.screenbox/.bench.LineBackfillReceiver --ei limit 200
 *
 * ══ 目的 ══
 *
 * R8 发现：块级坐标无法区分内容类型（chat 交错度 0.53 vs app_ui 0.57）。
 * 假设是块级合并抹平了气泡结构。这个探针重跑少量图片的 OCR，
 * 拿到行级坐标，然后比较行级特征的区分度。
 *
 * 如果行级特征也区分不开，就说明这条路走不通，
 * 不应该浪费几个小时全量重跑 8144 张。
 */
class LineBackfillReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context.applicationContext)
        val app = context.applicationContext
        val limit = intent.getIntExtra("limit", 200)

        CoroutineScope(Dispatchers.IO).launch {
            val sb = StringBuilder()
            sb.appendLine("=== 行级坐标回填探针 ===")
            sb.appendLine("limit=$limit")

            val db = Graph.db
            val ocrSource = io.github.wlmosv.screenbox.data.ocr.MlKitOcrSource(app)

            // 挑有坐标的、非删除的图
            val targets = db.query(
                "SELECT s.id, s.contentUri, s.width, s.height FROM screenshots s " +
                    "WHERE s.protectionStatus NOT IN ('DELETED','MISSING') " +
                    "AND s.ocrStatus IN ('SUCCESS','PARTIAL') LIMIT ?",
                arrayOf(limit),
            ).use { c ->
                val out = ArrayList<Triple<Long, String, IntArray>>()
                while (c.moveToNext()) {
                    out += Triple(
                        c.getLong(0),
                        c.getString(1),
                        intArrayOf(c.getInt(2), c.getInt(3)),
                    )
                }
                out
            }

            sb.appendLine("目标数: ${targets.size}")

            var ok = 0
            var fail = 0
            val t0 = System.currentTimeMillis()

            for ((id, uri, wh) in targets) {
                val r = ocrSource.recognize(android.net.Uri.parse(uri))
                val res = r.getOrNull()
                if (res == null || res.lineBoundsJson == null) {
                    fail++
                    continue
                }
                // 写回行级坐标（不覆盖原有文本和块坐标）
                runCatching {
                    db.query(
                        "UPDATE ocr SET lineBounds = ? WHERE screenshotId = ?",
                        arrayOf(res.lineBoundsJson, id),
                    ).close()
                }
                ok++
            }

            val elapsed = (System.currentTimeMillis() - t0) / 1000.0
            sb.appendLine("成功: $ok  失败: $fail  耗时: %.1f 秒".format(elapsed))
            sb.appendLine("吞吐: %.2f 张/秒".format(ok / maxOf(elapsed, 0.1)))
            ocrSource.close()

            File(app.getExternalFilesDir(null), "line_backfill.txt").writeText(sb.toString())
            android.util.Log.i("SCREENBOX_LINE_BACKFILL", sb.toString())
        }
    }
}