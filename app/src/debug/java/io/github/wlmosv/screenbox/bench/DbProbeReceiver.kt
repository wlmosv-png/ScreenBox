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
 * 数据库极限探针（debug only）。
 *
 * adb shell am broadcast -a io.github.wlmosv.screenbox.DB_PROBE \
 *   -n io.github.wlmosv.screenbox/.bench.DbProbeReceiver
 *
 * ══ 为什么需要它 ══
 *
 * R6 声称"全选 8000+ 静默失败，根因是 SQLite 999 变量上限"。
 * 但这个归因**从未被真正验证过** —— 只是根据现象推测。
 *
 * 实测桌面 SQLite 的 MAX_VARIABLE_NUMBER 是 250000，
 * SQLite 3.32+ 默认已改为 32766。Android 15 的 SQLite 版本更高，
 * 所以"999 上限"这个说法很可能是错的。
 *
 * 这个探针直接跑真实 DAO，用真实数据量测出：
 *   - 真实 SQLite 版本与变量上限
 *   - filterSelectable / byIds / sumBytes 在不同规模下的真实耗时与结果
 *
 * 结论写文件，不靠推测。
 */
class DbProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context.applicationContext)
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val sb = StringBuilder()
            sb.appendLine("=== DB 极限探针 ===")
            sb.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")

            runCatching {
                val db = Graph.db
                val dao = db.screenshotDao()

                // SQLite 版本与编译选项（真实值，不是猜的）
                db.query("SELECT sqlite_version()", emptyArray()).use { c ->
                    if (c.moveToFirst()) sb.appendLine("SQLite 版本: ${c.getString(0)}")
                }
                runCatching {
                    db.query("PRAGMA compile_options", emptyArray()).use { c ->
                        while (c.moveToNext()) {
                            val v = c.getString(0)
                            if (v.contains("MAX_VARIABLE", true) || v.contains("MAX_SQL", true)) {
                                sb.appendLine("编译选项: $v")
                            }
                        }
                    }
                }

                val allIds = dao.allActiveIds()
                sb.appendLine("有效截图: ${allIds.size}")
                sb.appendLine()

                // ── 逐个规模实测 ──
                for (n in listOf(500, 900, 999, 1000, 2000, 4000, 8137)) {
                    val ids = allIds.take(n)
                    if (ids.isEmpty()) continue
                    sb.appendLine("--- n=${ids.size} ---")

                    val t1 = System.currentTimeMillis()
                    val selectable = runCatching { dao.filterSelectable(ids) }
                        .getOrElse {
                            sb.appendLine("  filterSelectable 异常: ${it.javaClass.simpleName}: ${it.message}")
                            emptyList()
                        }
                    val d1 = System.currentTimeMillis() - t1
                    sb.appendLine("  filterSelectable: ${selectable.size} 条, ${d1}ms")

                    val t2 = System.currentTimeMillis()
                    val shots = runCatching { dao.byIds(ids) }
                        .getOrElse {
                            sb.appendLine("  byIds 异常: ${it.javaClass.simpleName}: ${it.message}")
                            emptyList()
                        }
                    val d2 = System.currentTimeMillis() - t2
                    sb.appendLine("  byIds: ${shots.size} 条, ${d2}ms")

                    val t3 = System.currentTimeMillis()
                    val bytes = runCatching { dao.sumBytes(ids) }.getOrElse { -1L }
                    val d3 = System.currentTimeMillis() - t3
                    sb.appendLine("  sumBytes: $bytes, ${d3}ms")
                }

                sb.appendLine()
                sb.appendLine("--- 结论 ---")
                sb.appendLine("若 n=8137 时 filterSelectable 返回接近 8136 条，")
                sb.appendLine("说明 SQLite 变量上限不是 999，R6 的归因需要修正。")
            }.onFailure {
                sb.appendLine("探针失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            val out = sb.toString()
            File(app.getExternalFilesDir(null), "db_probe.txt").writeText(out)
            android.util.Log.i("SCREENBOX_DB_PROBE", out)
        }
    }
}
