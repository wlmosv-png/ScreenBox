package io.github.wlmosv.screenbox.domain.query

/**
 * 搜索语法解析。
 *
 * ══ 为什么需要明确的语法 ══
 *
 * 用户输入"微信 转账"时，到底是"包含微信或转账"还是"两者都要"？
 * 不定义清楚，用户永远猜不到。
 *
 * ══ 语法（简单、可预测）══
 *
 *   空格           → AND（都要包含）
 *   OR / |         → OR（任一包含）
 *   "..."          → 精确短语
 *   -词            → 排除
 *   其余           → 普通关键词
 *
 * 示例：
 *   微信 转账          → 同时包含"微信"和"转账"
 *   微信 OR QQ         → 包含微信或 QQ
 *   "订单已发货"       → 精确短语
 *   聊天 -广告         → 含聊天且不含广告
 *   2025-03-15        → 日期（走结构化实体）
 *   ¥199              → 金额
 *
 * ══ 容错 ══
 *
 * 每个关键词展开成变体（全角半角、大小写、O/0、I/1/l），
 * 用 OR 连接。但精确匹配的结果排在前面，容错结果只作补充。
 */
data class SearchQuery(
    /** AND 组：每个组内的词都要命中（组内是 OR 变体）。 */
    val andGroups: List<List<String>> = emptyList(),
    /** 排除词。 */
    val excludes: List<String> = emptyList(),
    /** 精确短语（原文）。 */
    val phrases: List<String> = emptyList(),
    /** 原始输入，用于展示和记录历史。 */
    val raw: String = "",
) {
    val isEmpty: Boolean
        get() = andGroups.isEmpty() && excludes.isEmpty() && phrases.isEmpty()

    /**
     * 是否像结构化查询（日期 / 金额 / 长编号）。
     * 命中时查询引擎会额外查 structured_entities 表，
     * 因为 OCR 文本里"2025-03-15"可能被识别成"2025年3月15日"。
     */
    val looksStructured: Boolean
        get() = raw.let {
            Regex(
                """\d{4}\s*[-/年]\s*\d{1,2}|[¥￥$]\s*\d+|\d+\.\d{2}\s*元|(?<!\d)\d{8,}(?!\d)"""
            ).containsMatchIn(it)
        }

    /** 提取结构化查询的候选值（去符号，用于匹配 normalized）。 */
    fun structuredCandidates(): List<String> {
        val out = mutableListOf<String>()
        // 日期：2025-03-15 / 2025/3/15 / 2025年3月15日
        Regex("""(20\d{2})\s*[-/年.]\s*(\d{1,2})\s*[-/月.]\s*(\d{1,2})""").findAll(raw).forEach { m ->
            val y = m.groupValues[1]
            val mo = m.groupValues[2].padStart(2, '0')
            val d = m.groupValues[3].padStart(2, '0')
            out += "$y-$mo-$d"
            out += "$mo-$d"
        }
        // 金额
        Regex("""[¥￥$]\s*(\d+(?:\.\d{1,2})?)""").findAll(raw).forEach { m ->
            out += m.groupValues[1]
        }
        Regex("""(\d+(?:\.\d{1,2})?)\s*元""").findAll(raw).forEach { m ->
            out += m.groupValues[1]
        }
        // 长编号
        Regex("""(?<!\d)([A-Za-z0-9]{8,32})(?!\d)""").findAll(raw).forEach { m ->
            out += m.groupValues[1]
        }
        return out.distinct()
    }

    /** 人类可读的语法说明。 */
    fun describe(): String = buildString {
        if (andGroups.isNotEmpty()) {
            append(
                andGroups.joinToString(" 且 ") { g ->
                    // 组内多个变体说明是 OR 关系，要显示清楚
                    if (g.size > 1) "(${g.joinToString(" 或 ")})" else g.first()
                }
            )
        }
        if (phrases.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append(phrases.joinToString(" ") { "「$it」" })
        }
        if (excludes.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append("排除 " + excludes.joinToString("/"))
        }
    }.ifEmpty { raw }
}

object SearchQueryParser {

    /**
     * 解析用户输入。
     *
     * 返回 null 表示输入为空（调用方应显示空态而非空结果）。
     */
    fun parse(input: String): SearchQuery? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        val andGroups = mutableListOf<List<String>>()
        val excludes = mutableListOf<String>()
        val phrases = mutableListOf<String>()

        // 逐字扫描：支持引号短语、-排除
        var i = 0
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false

        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '"' -> {
                    if (inQuote) {
                        // 短语结束
                        if (sb.isNotEmpty()) {
                            phrases += sb.toString()
                            sb.clear()
                        }
                        inQuote = false
                    } else {
                        // 短语开始
                        if (sb.isNotEmpty()) {
                            tokens += sb.toString()
                            sb.clear()
                        }
                        inQuote = true
                    }
                }
                c.isWhitespace() && !inQuote -> {
                    if (sb.isNotEmpty()) {
                        tokens += sb.toString()
                        sb.clear()
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) {
            if (inQuote) phrases += sb.toString() else tokens += sb.toString()
        }

        // 处理 OR 和排除
        var orBuffer = mutableListOf<String>()

        fun flushAnd() {
            if (orBuffer.isNotEmpty()) {
                andGroups += orBuffer
                orBuffer = mutableListOf()
            }
        }

        for ((idx, t) in tokens.withIndex()) {
            when {
                t.equals("OR", ignoreCase = true) || t == "|" -> {
                    // 下一个词并入当前 OR 组
                    continue
                }
                t.startsWith("-") && t.length > 1 -> {
                    flushAnd()
                    excludes += t.substring(1)
                }
                else -> {
                    // 检查相邻 token 是否是 OR。
                    // 必须用下标：旧实现用 tokens.indexOf(t)，
                    // 同一个词出现两次时永远定位到第一次，"A A OR" 会解析错。
                    val prevIsOr = idx > 0 && (
                        tokens[idx - 1].equals("OR", ignoreCase = true) || tokens[idx - 1] == "|"
                        )
                    val nextIsOr = idx < tokens.size - 1 && (
                        tokens[idx + 1].equals("OR", ignoreCase = true) || tokens[idx + 1] == "|"
                        )

                    if (prevIsOr || nextIsOr) {
                        orBuffer += expandVariants(t)
                    } else {
                        flushAnd()
                        andGroups += listOf(t)  // 单元素组，精确匹配用
                    }
                }
            }
        }
        flushAnd()

        // OR 组内去重（同一词展开多次会产生重复）
        val deduped = andGroups.map { it.distinct() }

        if (deduped.isEmpty() && excludes.isEmpty() && phrases.isEmpty()) return null

        return SearchQuery(
            andGroups = deduped,
            excludes = excludes,
            phrases = phrases,
            raw = raw,
        )
    }

    /**
     * 生成容错变体。
     *
     * 只在 OR 组里用（用户明确要宽松匹配时）。
     * AND 默认组不做变体，保证精确性。
     */
    fun expandVariants(term: String): List<String> {
        val base = normalize(term)
        val out = linkedSetOf(base)

        // 全角 → 半角
        out += toHalfWidth(base)

        // 大小写
        if (base.any { it.isLetter() }) {
            out += base.lowercase()
            out += base.uppercase()
        }

        // 数字字母混淆（只在含数字或全字母短词时）
        if (base.length in 2..20) {
            out += base.replace('0', 'O').replace('1', 'I').replace('l', '1')
        }

        return out.filter { it.isNotBlank() }.toList()
    }

    /** 全角转半角。 */
    fun toHalfWidth(s: String): String = buildString {
        for (c in s) {
            when {
                c.code in 0xFF01..0xFF5E -> append((c.code - 0xFEE0).toChar())
                c.code == 0x3000 -> append(' ')
                else -> append(c)
            }
        }
    }

    fun normalize(s: String): String = toHalfWidth(s).trim()
}