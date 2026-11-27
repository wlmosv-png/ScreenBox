package io.github.wlmosv.screenbox.domain.query

import io.github.wlmosv.screenbox.data.db.OcrStatus
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 筛选条件的持久化格式。
 *
 * ══ 为什么不用 org.json ══
 *
 * 保存的筛选条件要能跨版本读回来。org.json 在本地单元测试里是空实现
 * （returnDefaultValues），一旦格式写错，测试根本发现不了，只有真机上
 * 用户点了「保存」才发现没存住。
 *
 * 这里用纯 Kotlin + URLEncoder 的键值格式：
 *   v1|cats=chat,order|srcs=wechat|from=1700000000000|...
 *
 * 值全部 URL 编码，所以分隔符不会出现在值里；未知键直接忽略，
 * 旧版本存的条件在新版本里仍能安全打开。
 */
object SavedFilterCodec {

    private const val VERSION = "v1"

    fun encode(filter: ShotFilter): String {
        val parts = mutableListOf(VERSION)
        fun put(k: String, v: String?) {
            if (v != null && v.isNotEmpty()) parts += "$k=${enc(v)}"
        }
        put("cats", filter.contentCategories.joinToString(","))
        put("srcs", filter.sourceApps.joinToString(","))
        put("feat", filter.featureTags.joinToString(","))
        put("from", filter.timeWindow.fromMs?.toString())
        put("to", filter.timeWindow.toMs?.toString())
        put("tlabel", filter.timeWindow.label)
        put("min", filter.minBytes?.toString())
        put("max", filter.maxBytes?.toString())
        put("fav", filter.favorite?.let { if (it) "1" else "0" })
        put("prot", filter.protected?.let { if (it) "1" else "0" })
        put("ocr", filter.ocrStatuses.joinToString(",") { it.name })
        put("q", filter.textQuery)
        put("exact", if (filter.exactDuplicateOnly) "1" else null)
        put("sim", if (filter.similarOnly) "1" else null)
        put("red", if (filter.redundantOnly) "1" else null)
        return parts.joinToString("|")
    }

    /** 解析失败返回 null，调用方应提示「这条筛选已失效」而不是崩溃。 */
    fun decode(payload: String): ShotFilter? {
        if (payload.isBlank()) return null
        val map = payload.split("|")
            .mapNotNull { seg ->
                val i = seg.indexOf('=')
                if (i <= 0) null else seg.substring(0, i) to dec(seg.substring(i + 1))
            }
            .toMap()
        if (map.isEmpty()) return null

        fun set(key: String): Set<String> =
            map[key]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

        fun long(key: String): Long? = map[key]?.toLongOrNull()

        fun bool(key: String): Boolean? = when (map[key]) {
            "1" -> true
            "0" -> false
            else -> null
        }

        val ocr = set("ocr").mapNotNull { name ->
            runCatching { OcrStatus.valueOf(name) }.getOrNull()
        }.toSet()

        val label = map["tlabel"]?.takeIf { it.isNotBlank() }
            ?: when {
                long("from") != null && long("to") != null -> "自定义时间"
                long("from") != null -> "开始时间之后"
                long("to") != null -> "结束时间之前"
                else -> "全部时间"
            }

        return ShotFilter(
            contentCategories = set("cats"),
            sourceApps = set("srcs"),
            featureTags = set("feat"),
            timeWindow = TimeWindow(label, long("from"), long("to")),
            minBytes = long("min"),
            maxBytes = long("max"),
            favorite = bool("fav"),
            protected = bool("prot"),
            ocrStatuses = ocr,
            textQuery = map["q"]?.takeIf { it.isNotBlank() },
            parsedQuery = map["q"]?.takeIf { it.isNotBlank() }
                ?.let { io.github.wlmosv.screenbox.domain.query.SearchQueryParser.parse(it) },
            exactDuplicateOnly = map["exact"] == "1",
            similarOnly = map["sim"] == "1",
            redundantOnly = map["red"] == "1",
        )
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
