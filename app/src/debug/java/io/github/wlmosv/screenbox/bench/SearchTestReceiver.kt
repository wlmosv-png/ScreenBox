package io.github.wlmosv.screenbox.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.domain.query.ShotFilter
import io.github.wlmosv.screenbox.domain.query.SearchQueryParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 搜索与一致性测试（debug only）。
 *
 * adb shell am broadcast -a io.github.wlmosv.screenbox.SEARCH_TEST \
 *   -n io.github.wlmosv.screenbox/.bench.SearchTestReceiver
 */
class SearchTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context.applicationContext)
        val app = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            val sb = StringBuilder()
            sb.appendLine("=== 搜索与一致性测试 ===")
            sb.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            sb.appendLine()

            val db = Graph.db
            val qr = Graph.queryRepo

            // ── 1. 数据一致性 ──
            sb.appendLine("--- 1. 数据一致性 ---")
            val homeTotal = db.screenshotDao().count()
            val activeTotal = db.screenshotDao().countActive()
            val filterTotal = qr.count(ShotFilter())
            sb.appendLine("首页口径: $homeTotal")
            sb.appendLine("图库口径: $activeTotal")
            sb.appendLine("筛选口径: $filterTotal")
            sb.appendLine(
                if (homeTotal == activeTotal && activeTotal == filterTotal)
                    "  ✅ 三者一致"
                else "  ❌ 不一致：首页=$homeTotal 图库=$activeTotal 筛选=$filterTotal"
            )
            sb.appendLine()

            // ── 2. 搜索语法 ──
            sb.appendLine("--- 2. 搜索语法 ---")
            val cases = listOf(
                "微信" to "单关键词",
                "微信 转账" to "AND（两词都要）",
                "微信 OR QQ" to "OR（任一）",
                "\"订单已发货\"" to "精确短语",
                "聊天 -广告" to "排除词",
                "2025-03-15" to "日期（结构化）",
                "¥199" to "金额（结构化）",
            )
            for ((q, label) in cases) {
                val t0 = System.currentTimeMillis()
                val parsed = SearchQueryParser.parse(q)
                val n = runCatching {
                    qr.count(ShotFilter().withText(q))
                }.getOrDefault(-1)
                val ms = System.currentTimeMillis() - t0
                sb.appendLine("「$q」($label)")
                sb.appendLine("  解析: ${parsed?.describe() ?: "无效"}")
                sb.appendLine("  命中: $n 张，耗时 ${ms}ms")
            }
            sb.appendLine()

            // ── 3. 结构化实体搜索 ──
            sb.appendLine("--- 3. 结构化实体 ---")
            val kinds = listOf("DATE", "AMOUNT", "PHONE", "URL", "ORDER_NO", "TRACKING")
            for (k in kinds) {
                val cnt = runCatching {
                    db.entityDao().countByKind().firstOrNull { it.kind == k }?.cnt ?: 0
                }.getOrDefault(0)
                sb.appendLine("  $k: $cnt 条")
            }
            val withEnt = runCatching { db.entityDao().screenshotsWithEntities() }.getOrDefault(0)
            sb.appendLine("  有实体的图: $withEnt 张")
            sb.appendLine()

            // 按实体反查
            val someDate = runCatching {
                db.query(
                    "SELECT normalized FROM structured_entities WHERE kind='DATE' LIMIT 1", emptyArray()
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            if (someDate != null) {
                val t0 = System.currentTimeMillis()
                val ids = db.entityDao().idsByEntity("DATE", someDate)
                sb.appendLine("  按日期「$someDate」反查: ${ids.size} 张，耗时 ${System.currentTimeMillis() - t0}ms")
            }
            sb.appendLine()

            // ── 4. 组合筛选性能 ──
            sb.appendLine("--- 4. 组合筛选性能 ---")
            val combos = listOf(
                "全部" to ShotFilter(),
                "仅游戏截图" to ShotFilter(contentCategories = setOf("game")),
                "游戏 + 90天前" to ShotFilter(
                    contentCategories = setOf("game"),
                    timeWindow = io.github.wlmosv.screenbox.domain.query.TimeWindow.olderThan(90),
                ),
                "游戏应用来源 + 排除收藏" to ShotFilter(
                    sourceApps = setOf("game"), favorite = false,
                ),
                "完全重复" to ShotFilter(exactDuplicateOnly = true),
                "≥2MB + 未收藏" to ShotFilter(minBytes = 2L * 1024 * 1024, favorite = false),
            )
            for ((label, f) in combos) {
                val t0 = System.currentTimeMillis()
                val n = qr.count(f)
                val items = qr.query(f, limit = 100)
                val ms = System.currentTimeMillis() - t0
                sb.appendLine("  $label: $n 张（取前100耗时 ${ms}ms）")
            }
            sb.appendLine()

            // ── 5. 分类质量抽查 ──
            sb.appendLine("--- 5. 分类与来源分布 ---")
            val cats = qr.categoryFacets(ShotFilter())
            sb.appendLine("内容分类：")
            cats.take(8).forEach { sb.appendLine("  ${it.displayName}: ${it.count}") }
            val srcs = qr.sourceFacets()
            sb.appendLine("来源应用：")
            srcs.take(8).forEach { sb.appendLine("  ${it.displayName}: ${it.count}") }
            sb.appendLine()

            sb.appendLine("--- 6. 内存 ---")
            val rt = Runtime.getRuntime()
            sb.appendLine("Java 堆: ${(rt.totalMemory() - rt.freeMemory()) / 1024 / 1024}MB / ${rt.maxMemory() / 1024 / 1024}MB")
            sb.appendLine("Native: ${android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024}MB")

            val out = sb.toString()
            File(app.getExternalFilesDir(null), "search_test.txt").writeText(out)
            android.util.Log.i("SCREENBOX_SEARCH", out)
        }
    }
}
