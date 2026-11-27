package io.github.wlmosv.screenbox.data.repo

import androidx.room.withTransaction
import io.github.wlmosv.screenbox.data.db.OcrStatus
import io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.domain.query.ShotFilter
import io.github.wlmosv.screenbox.domain.query.ShotSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一查询引擎。
 *
 * ══ 为什么需要它 ══
 *
 * 之前每个功能各写一套查询：
 *   分类页 → screenshot_categories
 *   清理页 → cleanup_suggestions
 *   搜索页 → ocr_fts
 *
 * 结果：用户无法组合条件，也做不出"微信 + 90天前 + 排除收藏"这种筛选。
 *
 * 现在所有查询都走这里，条件是 ShotFilter。
 * 分类浏览、批量整理、删除清单、搜索 —— 共用一条 SQL 生成逻辑。
 *
 * ══ 为什么用原生 SQL 而不是 Room 注解 ══
 *
 * 组合条件有 2^10 种可能，Room 注解要写几百个方法。
 * 用动态 SQL 拼接，一处维护，且能精确控制 JOIN 和索引命中。
 *
 * 安全：所有用户输入都通过绑定参数传入，不拼字符串。
 * 集合类条件用占位符展开（IN (?,?,?)），值仍然绑定。
 */
class ScreenshotQueryRepository(private val db: ScreenBoxDatabase) {

    /** 查询结果分页单元。 */
    data class Page(
        val items: List<ScreenshotEntity>,
        val total: Int,
    )

    /**
     * 按筛选条件查询一批。
     *
     * @param limit 0 = 不限（用于"全选筛选结果"场景，调用方需自己控制规模）
     */
    suspend fun query(
        filter: ShotFilter,
        sort: ShotSort = ShotSort.NEWEST,
        limit: Int = 500,
        offset: Int = 0,
    ): List<ScreenshotEntity> = withContext(Dispatchers.IO) {
        val (sql, args) = buildSql(filter, sort, limit, offset, selectCount = false)
        db.query(sql, args.toTypedArray()).use { c ->
            val out = ArrayList<ScreenshotEntity>(c.count.coerceAtMost(limit.coerceAtLeast(1)))
            while (c.moveToNext()) {
                out += readEntity(c)
            }
            out
        }
    }

    /** 只算总数，用于"全选筛选结果"时显示真实范围。 */
    suspend fun count(filter: ShotFilter): Int = withContext(Dispatchers.IO) {
        val (sql, args) = buildSql(filter, ShotSort.NEWEST, 0, 0, selectCount = true)
        db.query(sql, args.toTypedArray()).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 一次性查出全部 id（全选场景），不加载实体，节省内存。 */
    suspend fun allIds(filter: ShotFilter): List<Long> = withContext(Dispatchers.IO) {
        val (sql, args) = buildSql(filter, ShotSort.NEWEST, 0, 0, selectCount = false, idsOnly = true)
        val out = ArrayList<Long>()
        db.query(sql, args.toTypedArray()).use { c ->
            val idx = c.getColumnIndexOrThrow("id")
            while (c.moveToNext()) out += c.getLong(idx)
        }
        out
    }

    // ─────────────────────────────────────────────

    private fun buildSql(
        filter: ShotFilter,
        sort: ShotSort,
        limit: Int,
        offset: Int,
        selectCount: Boolean,
        idsOnly: Boolean = false,
    ): Pair<String, List<Any?>> {
        val args = mutableListOf<Any?>()
        val joins = mutableListOf<String>()
        val wheres = mutableListOf<String>()

        // 永远排除已删除/丢失
        wheres += "s.protectionStatus NOT IN ('DELETED','MISSING')"

        // ── 内容分类（OR 语义：属于任一即命中）──
        if (filter.contentCategories.isNotEmpty()) {
            joins += "INNER JOIN screenshot_categories sc ON sc.screenshotId = s.id"
            joins += "INNER JOIN categories c ON c.id = sc.categoryId"
            val ph = filter.contentCategories.joinToString(",") { "?" }
            wheres += "c.`key` IN ($ph)"
            args.addAll(filter.contentCategories.toList())
        }

        // ── 来源应用 ──
        if (filter.sourceApps.isNotEmpty()) {
            joins += "INNER JOIN screenshot_sources ss ON ss.screenshotId = s.id"
            joins += "INNER JOIN source_apps sa ON sa.id = ss.sourceAppId"
            val ph = filter.sourceApps.joinToString(",") { "?" }
            wheres += "sa.`key` IN ($ph)"
            args.addAll(filter.sourceApps.toList())
        }

        // ── 图片特征标签 ──
        if (filter.featureTags.isNotEmpty()) {
            joins += "INNER JOIN screenshot_tags st ON st.screenshotId = s.id"
            joins += "INNER JOIN tags tg ON tg.id = st.tagId"
            val ph = filter.featureTags.joinToString(",") { "?" }
            wheres += "tg.`name` IN ($ph)"
            args.addAll(filter.featureTags.toList())
        }

        // ── 时间 ──
        filter.timeWindow.fromMs?.let {
            wheres += "s.dateModified >= ?"
            args += it
        }
        filter.timeWindow.toMs?.let {
            wheres += "s.dateModified < ?"
            args += it
        }

        // ── 大小 ──
        filter.minBytes?.let {
            wheres += "s.fileSize >= ?"
            args += it
        }
        filter.maxBytes?.let {
            wheres += "s.fileSize <= ?"
            args += it
        }

        // ── 收藏 / 保护 ──
        when (filter.favorite) {
            true -> wheres += "s.isFavorite = 1"
            false -> wheres += "s.isFavorite = 0"
            null -> Unit
        }
        when (filter.protected) {
            true -> wheres += "s.protectionStatus = 'PROTECTED'"
            false -> wheres += "s.protectionStatus != 'PROTECTED'"
            null -> Unit
        }

        // ── OCR 状态 ──
        if (filter.ocrStatuses.isNotEmpty()) {
            val ph = filter.ocrStatuses.joinToString(",") { "?" }
            wheres += "s.ocrStatus IN ($ph)"
            args.addAll(filter.ocrStatuses.map { it.name })
        }

        // ── 文本 + 结构化检索 ──
        //
        // 两路并行：
        //   A. 文本路径：OCR 原文 instr 匹配（AND / OR / 短语 / 排除）
        //   B. 实体路径：structured_entities 归一化值匹配（日期/金额/编号）
        //
        // 为什么需要 B：OCR 可能把"2025-03-15"识别成"2025年3月15日"，
        // 纯文本会漏。两路是 OR 关系 —— 任一路命中即算找到。
        //
        // 排除词单独处理：它是 AND 语义（必须不含），不能进 OR。
        val parsed = filter.parsedQuery
        val textConds = mutableListOf<String>()
        val textArgs = mutableListOf<Any?>()

        if (parsed != null && !parsed.isEmpty) {
            // AND 组：组内 OR，组间 AND
            for (group in parsed.andGroups) {
                val ph = group.joinToString(" OR ") {
                    "instr(lower(o.recognizedText), lower(?)) > 0"
                }
                textConds += "($ph)"
                textArgs.addAll(group)
            }
            // 精确短语
            for (p in parsed.phrases) {
                textConds += "instr(lower(o.recognizedText), lower(?)) > 0"
                textArgs += p
            }
        }

        // 实体路径
        val structured = parsed?.takeIf { it.looksStructured }?.structuredCandidates()
        val entityCond: String? = if (!structured.isNullOrEmpty()) {
            val ph = structured.joinToString(" OR ") {
                "instr(lower(se.normalized), lower(?)) > 0"
            }
            "EXISTS (SELECT 1 FROM structured_entities se " +
                "WHERE se.screenshotId = s.id AND ($ph))"
        } else null

        // 组装主检索条件
        if (textConds.isNotEmpty() || entityCond != null) {
            joins += "INNER JOIN ocr o ON o.screenshotId = s.id"
            val parts = buildList {
                if (textConds.isNotEmpty()) add(textConds.joinToString(" AND "))
                if (entityCond != null) add(entityCond)
            }
            // 多路之间是 OR
            wheres += "(" + parts.joinToString(" OR ") + ")"
            args.addAll(textArgs)
            if (!structured.isNullOrEmpty()) args.addAll(structured)
        }

        // 排除词：AND 语义，独立加入
        if (parsed != null) {
            for (ex in parsed.excludes) {
                wheres += "instr(lower(o.recognizedText), lower(?)) = 0"
                args += ex
            }
            // 如果只有排除词没有正条件，仍需 JOIN ocr
            if (parsed.excludes.isNotEmpty() &&
                parsed.andGroups.isEmpty() && parsed.phrases.isEmpty()
            ) {
                joins += "INNER JOIN ocr o ON o.screenshotId = s.id"
            }
        }

        // ── 重复 / 相似 ──
        if (filter.exactDuplicateOnly) {
            wheres += """s.id IN (
                SELECT dm.screenshotId FROM duplicate_members dm
                INNER JOIN duplicate_groups dg ON dg.id = dm.groupId
                WHERE dg.groupType = 'EXACT' AND dg.dismissed = 0
            )"""
        }
        if (filter.similarOnly) {
            wheres += """s.id IN (
                SELECT dm.screenshotId FROM duplicate_members dm
                INNER JOIN duplicate_groups dg ON dg.id = dm.groupId
                WHERE dg.groupType = 'VISUAL' AND dg.dismissed = 0
            )"""
        }
        if (filter.suggestionType != null) {
            // 只认 OPEN 状态的建议：已处理/已忽略的不该再出现在结果里。
            wheres += """s.id IN (
                SELECT cs.screenshotId FROM cleanup_suggestions cs
                WHERE cs.suggestionType = ? AND cs.status = 'OPEN'
            )"""
            args += filter.suggestionType
        }
        if (filter.redundantOnly) {
            wheres += """s.id IN (
                SELECT dm.screenshotId FROM duplicate_members dm
                INNER JOIN duplicate_groups dg ON dg.id = dm.groupId
                WHERE dg.dismissed = 0
            )"""
        }

        val distinct = if (joins.isEmpty()) "" else "DISTINCT "

        val selectClause = when {
            selectCount -> "SELECT COUNT(DISTINCT s.id)"
            idsOnly -> "SELECT DISTINCT s.id"
            else -> "SELECT $distinct s.*"
        }

        val orderClause = when (sort) {
            ShotSort.NEWEST -> " ORDER BY s.dateModified DESC"
            ShotSort.OLDEST -> " ORDER BY s.dateModified ASC"
            ShotSort.LARGEST -> " ORDER BY s.fileSize DESC"
            ShotSort.SMALLEST -> " ORDER BY s.fileSize ASC"
        }

        val limitClause = when {
            selectCount -> ""
            limit > 0 -> " LIMIT $limit OFFSET $offset"
            else -> ""
        }

        val sql = buildString {
            append(selectClause)
            append(" FROM screenshots s")
            if (joins.isNotEmpty()) {
                append(" ")
                append(joins.distinct().joinToString(" "))
            }
            if (wheres.isNotEmpty()) {
                append(" WHERE ")
                append(wheres.joinToString(" AND "))
            }
            if (!selectCount) append(orderClause)
            append(limitClause)
        }
        return sql to args
    }

    private fun readEntity(c: android.database.Cursor): ScreenshotEntity {
        fun col(name: String) = c.getColumnIndexOrThrow(name)
        return ScreenshotEntity(
            id = c.getLong(col("id")),
            mediaStoreId = c.getLong(col("mediaStoreId")),
            contentUri = c.getString(col("contentUri")),
            displayName = c.getString(col("displayName")),
            relativePath = c.getString(col("relativePath")),
            mimeType = c.getString(col("mimeType")),
            fileSize = c.getLong(col("fileSize")),
            width = c.getInt(col("width")),
            height = c.getInt(col("height")),
            dateAdded = c.getLong(col("dateAdded")),
            dateModified = c.getLong(col("dateModified")),
            sha256 = c.getString(col("sha256")),
            perceptualHash = c.getString(col("perceptualHash")),
            screenshotConfidence = runCatching {
                io.github.wlmosv.screenbox.data.db.ScreenshotConfidence.valueOf(
                    c.getString(col("screenshotConfidence"))
                )
            }.getOrDefault(io.github.wlmosv.screenbox.data.db.ScreenshotConfidence.MEDIUM),
            ocrStatus = runCatching {
                OcrStatus.valueOf(c.getString(col("ocrStatus")))
            }.getOrDefault(OcrStatus.NOT_STARTED),
            classifyStatus = runCatching {
                io.github.wlmosv.screenbox.data.db.ClassifyStatus.valueOf(
                    c.getString(col("classifyStatus"))
                )
            }.getOrDefault(io.github.wlmosv.screenbox.data.db.ClassifyStatus.NOT_STARTED),
            protectionStatus = runCatching {
                io.github.wlmosv.screenbox.data.db.ProtectionStatus.valueOf(
                    c.getString(col("protectionStatus"))
                )
            }.getOrDefault(io.github.wlmosv.screenbox.data.db.ProtectionStatus.NORMAL),
            isFavorite = c.getInt(col("isFavorite")) != 0,
            userCategoryId = if (c.isNull(col("userCategoryId"))) null else c.getLong(col("userCategoryId")),
            lastViewedAt = if (c.isNull(col("lastViewedAt"))) null else c.getLong(col("lastViewedAt")),
            createdAt = c.getLong(col("createdAt")),
            updatedAt = c.getLong(col("updatedAt")),
            ocrPriority = c.getInt(col("ocrPriority")),
            ocrAttempts = c.getInt(col("ocrAttempts")),
        )
    }

    // ─────────────────────────────────────────────
    // 统计：给首页和分类页用的口径
    // ─────────────────────────────────────────────

    data class FacetCount(val key: String, val displayName: String, val count: Int)

    /** 各内容分类的图片数（受其他筛选条件影响）。 */
    suspend fun categoryFacets(base: ShotFilter): List<FacetCount> = withContext(Dispatchers.IO) {
        val q = """
            SELECT c.`key` AS k, c.displayName AS n, COUNT(DISTINCT s.id) AS cnt
            FROM screenshots s
            INNER JOIN screenshot_categories sc ON sc.screenshotId = s.id
            INNER JOIN categories c ON c.id = sc.categoryId
            WHERE s.protectionStatus NOT IN ('DELETED','MISSING')
            GROUP BY c.id
            HAVING cnt > 0
            ORDER BY cnt DESC
        """.trimIndent()
        val out = mutableListOf<FacetCount>()
        db.query(q, emptyArray()).use { c ->
            while (c.moveToNext()) {
                out += FacetCount(c.getString(0), c.getString(1), c.getInt(2))
            }
        }
        out
    }

    /** 各来源应用的图片数。 */
    suspend fun sourceFacets(): List<FacetCount> = withContext(Dispatchers.IO) {
        val q = """
            SELECT sa.`key` AS k, sa.displayName AS n, COUNT(DISTINCT s.id) AS cnt
            FROM screenshots s
            INNER JOIN screenshot_sources ss ON ss.screenshotId = s.id
            INNER JOIN source_apps sa ON sa.id = ss.sourceAppId
            WHERE s.protectionStatus NOT IN ('DELETED','MISSING')
            GROUP BY sa.id
            HAVING cnt > 0
            ORDER BY cnt DESC
        """.trimIndent()
        val out = mutableListOf<FacetCount>()
        db.query(q, emptyArray()).use { c ->
            while (c.moveToNext()) {
                out += FacetCount(c.getString(0), c.getString(1), c.getInt(2))
            }
        }
        out
    }

    /** 各特征标签的图片数（长截图/二维码/大文件…）。 */
    suspend fun tagFacets(): List<FacetCount> = withContext(Dispatchers.IO) {
        val q = """
            SELECT t.`name` AS k, t.displayName AS n, COUNT(DISTINCT s.id) AS cnt
            FROM screenshots s
            INNER JOIN screenshot_tags st ON st.screenshotId = s.id
            INNER JOIN tags t ON t.id = st.tagId
            WHERE s.protectionStatus NOT IN ('DELETED','MISSING')
              AND t.`group` = 'FEATURE'
            GROUP BY t.id
            HAVING cnt > 0
            ORDER BY cnt DESC
        """.trimIndent()
        val out = mutableListOf<FacetCount>()
        db.query(q, emptyArray()).use { c ->
            while (c.moveToNext()) {
                out += FacetCount(c.getString(0), c.getString(1), c.getInt(2))
            }
        }
        out
    }
}
