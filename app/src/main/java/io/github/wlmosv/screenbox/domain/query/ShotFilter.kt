package io.github.wlmosv.screenbox.domain.query

import io.github.wlmosv.screenbox.data.db.OcrStatus

/**
 * 时间范围。用相对天数表达，避免"今天"随日期漂移。
 */
data class TimeWindow(
    val label: String,
    val fromMs: Long?,
    val toMs: Long? = null,
) {
    companion object {
        private const val DAY = 24L * 3600 * 1000

        fun today(now: Long = System.currentTimeMillis()): TimeWindow {
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            return TimeWindow("今天", cal.timeInMillis, null)
        }

        fun lastDays(days: Int, now: Long = System.currentTimeMillis()) =
            TimeWindow("最近 $days 天", now - days * DAY, null)

        fun olderThan(days: Int, now: Long = System.currentTimeMillis()) =
            TimeWindow("$days 天以前", null, now - days * DAY)

        fun all() = TimeWindow("全部时间", null, null)
    }
}

/**
 * 筛选条件。
 *
 * ══ 设计动机 ══
 *
 * 之前分类、清理、搜索是三个独立功能，各自写查询：
 *   - 分类页查 screenshot_categories
 *   - 清理页查 cleanup_suggestions
 *   - 搜索页查 ocr_fts
 * 用户无法组合条件，也没法"先按微信筛，再按 90 天前筛，再排除收藏"。
 *
 * 现在统一成一个不可变的筛选对象，任何维度都能组合。
 * 所有页面（分类浏览、批量整理、删除清单、搜索）共用同一个查询引擎。
 *
 * ══ 维度 ══
 *
 * 内容用途 / 来源应用 / 时间 / 文件大小 / 收藏 / 保护 / OCR 状态
 * / 文本关键词 / 重复关系 / 相似关系 —— 全部可叠加。
 */
data class ShotFilter(
    /** 内容分类 key 集合。空=不限。多选时是 OR 关系（属于任一类即可）。 */
    val contentCategories: Set<String> = emptySet(),

    /** 来源应用 key 集合。空=不限。 */
    val sourceApps: Set<String> = emptySet(),

    /** 图片特征标签 key 集合（长截图/二维码/大文件…）。空=不限。多选是 OR。 */
    val featureTags: Set<String> = emptySet(),

    val timeWindow: TimeWindow = TimeWindow.all(),

    val minBytes: Long? = null,
    val maxBytes: Long? = null,

    /** true 只看收藏，false 看不收藏的，null 不限。 */
    val favorite: Boolean? = null,
    val protected: Boolean? = null,

    /** OCR 状态集合。空=不限。 */
    val ocrStatuses: Set<OcrStatus> = emptySet(),

    /** 全文关键词（原始输入）。 */
    val textQuery: String? = null,

    /** 解析后的查询结构。由 UI 层调用 SearchQueryParser 填入。 */
    val parsedQuery: SearchQuery? = null,

    /** 只看属于完全重复组的。 */
    val exactDuplicateOnly: Boolean = false,

    /** 只看属于相似组的。 */
    val similarOnly: Boolean = false,

    /** 只看属于任何重复/相似组的（"可清理的冗余"）。 */
    val redundantOnly: Boolean = false,

    /**
     * 只看带有某类清理建议的图片（如 POSSIBLY_EXPIRED）。
     *
     * 为什么需要：清理建议是一个独立的表（cleanup_suggestions），
     * 之前只能在清理工作台里看。用户从首页点「过期与临时信息」时
     * 没有对应的筛选维度，只能掉进"全部图片"，等于入口失效。
     */
    val suggestionType: String? = null,
) {
    val isEmpty: Boolean
        get() = contentCategories.isEmpty() &&
            sourceApps.isEmpty() &&
            featureTags.isEmpty() &&
            timeWindow.fromMs == null && timeWindow.toMs == null &&
            minBytes == null && maxBytes == null &&
            favorite == null && protected == null &&
            ocrStatuses.isEmpty() &&
            textQuery.isNullOrBlank() &&
            !exactDuplicateOnly && !similarOnly && !redundantOnly &&
            suggestionType == null

    /** 人类可读的条件描述，用于 UI 顶部展示"当前筛选"。 */
    fun describe(categoryNames: Map<String, String> = emptyMap(), appNames: Map<String, String> = emptyMap()): List<String> =
        buildList {
            if (contentCategories.isNotEmpty()) {
                add(contentCategories.map { categoryNames[it] ?: it }.joinToString("/"))
            }
            if (sourceApps.isNotEmpty()) {
                add(sourceApps.map { appNames[it] ?: it }.joinToString("/"))
            }
            if (featureTags.isNotEmpty()) add("特征：" + featureTags.joinToString("/"))
            if (timeWindow.fromMs != null || timeWindow.toMs != null) add(timeWindow.label)
            minBytes?.let { add("≥ ${it / 1024 / 1024} MB") }
            maxBytes?.let { add("≤ ${it / 1024 / 1024} MB") }
            when (favorite) {
                true -> add("仅收藏")
                false -> add("非收藏")
                null -> Unit
            }
            when (protected) {
                true -> add("仅保护")
                false -> add("非保护")
                null -> Unit
            }
            if (ocrStatuses.isNotEmpty()) {
                add(
                    ocrStatuses.joinToString("/") {
                        when (it) {
                            OcrStatus.SUCCESS, OcrStatus.PARTIAL -> "已识别"
                            OcrStatus.NOT_STARTED, OcrStatus.QUEUED -> "未识别"
                            OcrStatus.FAILED -> "识别失败"
                            else -> it.name
                        }
                    }
                )
            }
            parsedQuery?.let { if (!it.isEmpty) add(it.describe()) }
            if (exactDuplicateOnly) add("完全重复")
            if (similarOnly) add("画面相似")
            if (redundantOnly) add("冗余图片")
            suggestionType?.let { add(suggestionLabel(it)) }
        }

    private fun suggestionLabel(type: String): String = when (type) {
        "POSSIBLY_EXPIRED" -> "疑似过期"
        "NEAR_BLANK" -> "近乎空白"
        "LARGE_FILE" -> "大文件"
        "LOW_INFORMATION" -> "信息量低"
        "ONE_TIME_CODE" -> "一次性验证码"
        "STALE_NOTIFICATION" -> "过时通知"
        "OLD_GAME" -> "旧游戏截图"
        "OLD_SOCIAL" -> "旧社交截图"
        "EXACT_DUPLICATE" -> "完全重复"
        "VISUAL_DUPLICATE" -> "画面相似"
        else -> type
    }

    // ── 组合操作：返回新对象，保持不可变 ──

    fun withCategory(key: String?): ShotFilter =
        copy(contentCategories = if (key == null) emptySet() else setOf(key))

    fun toggleCategory(key: String): ShotFilter =
        copy(contentCategories = if (key in contentCategories) contentCategories - key else contentCategories + key)

    fun withSource(key: String?): ShotFilter =
        copy(sourceApps = if (key == null) emptySet() else setOf(key))

    fun withFeatureTag(key: String?): ShotFilter =
        copy(featureTags = if (key == null) emptySet() else setOf(key))

    fun toggleFeatureTag(key: String): ShotFilter =
        copy(featureTags = if (key in featureTags) featureTags - key else featureTags + key)

    fun toggleSource(key: String): ShotFilter =
        copy(sourceApps = if (key in sourceApps) sourceApps - key else sourceApps + key)

    fun withTime(w: TimeWindow): ShotFilter = copy(timeWindow = w)

    fun withMinBytes(b: Long?): ShotFilter = copy(minBytes = b)

    fun withFavorite(f: Boolean?): ShotFilter = copy(favorite = f)

    fun withProtected(p: Boolean?): ShotFilter = copy(protected = p)

    fun withText(q: String?): ShotFilter = copy(
        textQuery = q,
        parsedQuery = q?.let { io.github.wlmosv.screenbox.domain.query.SearchQueryParser.parse(it) },
    )

    fun withOcr(statuses: Set<OcrStatus>): ShotFilter = copy(ocrStatuses = statuses)

    fun withoutText(): ShotFilter = copy(textQuery = null, parsedQuery = null)
}

/** 排序方式。 */
enum class ShotSort(val label: String) {
    NEWEST("最新"),
    OLDEST("最旧"),
    LARGEST("最大"),
    SMALLEST("最小"),
}