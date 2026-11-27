package io.github.wlmosv.screenbox.domain.extract

/**
 * OCR 结构化信息提取。
 *
 * ══ 设计原则 ══
 *
 * 1. 每条实体必须有独立的验证规则，不能"看到 11 位数字就当手机号"。
 * 2. 每条实体带置信度和原文位置，用户能看到"为什么被识别成这个"。
 * 3. OCR 原始文本永远保留，提取只是附加信息，不覆盖。
 * 4. 拿不准就不提取 —— 宁可空着，也不要污染搜索。
 *
 * ══ 为什么放在 domain 层 ══
 *
 * 纯函数，不依赖 Android，方便单测和后续换引擎。
 */
object EntityExtractor {

    enum class Kind(val label: String) {
        DATE("日期"),
        AMOUNT("金额"),
        ORDER_NO("订单号"),
        PHONE("手机号"),
        URL("网址"),
        EMAIL("邮箱"),
        TRACKING("物流单号"),
        TIME("时间"),
    }

    data class Entity(
        val kind: Kind,
        /** 原文片段。 */
        val raw: String,
        /** 归一化后的值，便于比较和搜索。 */
        val normalized: String,
        val confidence: Float,
        /** 在 OCR 全文中的起始位置，用于详情页定位。 */
        val start: Int,
    )

    // ─────────────────────────────────────────────
    // 日期
    // ─────────────────────────────────────────────

    /**
     * 日期。
     *
     * 只认带完整年份的（2025-03-15 / 2025年3月15日 / 2025/03/15），
     * 或者带明确语境词的无年份日期（"订单日期 3月15日"）。
     * 单纯 "3-15" 这种不认，误判率太高。
     */
    private val DATE_FULL = Regex("""(20\d{2})\s*[-/年.]\s*(\d{1,2})\s*[-/月.]\s*(\d{1,2})\s*日?""")

    /** 无年份但带语境词。 */
    private val DATE_CONTEXT = Regex(
        """(?:订单日期|下单时间|支付时间|交易时间|有效期至|截止|日期)[：:\s]*(\d{1,2})\s*[-/月]\s*(\d{1,2})\s*日?"""
    )

    // ─────────────────────────────────────────────
    // 金额
    // ─────────────────────────────────────────────

    /**
     * 金额。
     *
     * 必须满足以下之一：
     * - 带货币符号：¥ ￥ $ € £
     * - 带"元/块/圆"单位
     * - 带语境词（合计/实付/应付/总价/小计/优惠/退款）
     * - 两位小数且带语境
     *
     * 排除：纯数字、数量、编号、日期片段。
     */
    private val AMOUNT_SYMBOL = Regex("""[¥￥$€£]\s*(\d{1,7}(?:[.,]\d{1,2})?)""")
    private val AMOUNT_UNIT = Regex("""(\d{1,7}(?:[.,]\d{1,2})?)\s*(?:元|块钱|圆)""")
    private val AMOUNT_CONTEXT = Regex(
        """(?:合计|实付|应付|总价|小计|优惠|退款|支付金额|订单金额|余额)[：:\s]*[¥￥]?\s*(\d{1,7}(?:[.,]\d{1,2})?)"""
    )

    // ─────────────────────────────────────────────
    // 订单号
    // ─────────────────────────────────────────────

    /**
     * 订单号。
     *
     * 必须有语境词引导，纯数字不认。
     * 长度 8-32 位，只含数字和常见分隔。
     */
    private val ORDER_NO = Regex(
        """(?:订单号|订单编号|交易号|流水号|商户单号|order\s*(?:no|id)|transaction\s*id)[：:#\s]*([A-Za-z0-9\-]{8,32})""",
        RegexOption.IGNORE_CASE
    )

    // ─────────────────────────────────────────────
    // 手机号
    // ─────────────────────────────────────────────

    /**
     * 手机号。
     *
     * 中国大陆：1[3-9] 开头，11 位，前后不能是数字。
     * 必须有边界断言，避免从长数字串里截取。
     */
    private val PHONE_CN = Regex("""(?<!\d)(1[3-9]\d{9})(?!\d)""")

    // ─────────────────────────────────────────────
    // 物流单号
    // ─────────────────────────────────────────────

    /**
     * 物流单号。
     *
     * 必须有语境词（运单号/快递单号/物流单号/tracking）。
     * 各家格式差异大，不靠格式猜。
     */
    private val TRACKING = Regex(
        """(?:运单号|快递单号|物流单号|tracking\s*(?:no|number))[：:#\s]*([A-Za-z0-9]{8,32})""",
        RegexOption.IGNORE_CASE
    )

    // ─────────────────────────────────────────────
    // URL / 邮箱
    // ─────────────────────────────────────────────

    private val URL = Regex(
        """https?://[^\s，。、；）)\]]+|(?<![\w.])www\.[^\s，。、；）)\]]+""",
        RegexOption.IGNORE_CASE
    )

    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    // ─────────────────────────────────────────────
    // 时间
    // ─────────────────────────────────────────────

    /**
     * 时间。
     *
     * 关键修正：孤立的时间没有价值 —— 几乎每张截图的状态栏都有 HH:mm。
     * 只提取带明确语境词的时间（下单时间/支付时间/发布时间…）。
     */
    private val TIME = Regex(
        """(?:下单时间|支付时间|交易时间|创建时间|发布时间|发送时间|到达时间|时间)[：:\s]*([01]?\d|2[0-3]):([0-5]\d)"""
    )

    /**
     * 提取全部实体。
     *
     * @param text OCR 原文
     * @param maxPerKind 每种最多取几个，避免长截图产出过多噪音
     */
    fun extract(text: String, maxPerKind: Int = 5): List<Entity> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<Entity>()

        // ── 完整日期 ──
        DATE_FULL.findAll(text).take(maxPerKind).forEach { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return@forEach
            val mo = m.groupValues[2].toIntOrNull() ?: return@forEach
            val d = m.groupValues[3].toIntOrNull() ?: return@forEach
            if (mo !in 1..12 || d !in 1..31) return@forEach
            out += Entity(
                Kind.DATE,
                m.value,
                "%04d-%02d-%02d".format(y, mo, d),
                0.90f,
                m.range.first,
            )
        }

        // ── 语境日期（无年份）──
        if (out.none { it.kind == Kind.DATE }) {
            DATE_CONTEXT.findAll(text).take(2).forEach { m ->
                val mo = m.groupValues[1].toIntOrNull() ?: return@forEach
                val d = m.groupValues[2].toIntOrNull() ?: return@forEach
                if (mo !in 1..12 || d !in 1..31) return@forEach
                out += Entity(Kind.DATE, m.value, "%02d-%02d".format(mo, d), 0.55f, m.range.first)
            }
        }

        // ── 金额（三种来源合并去重）──
        val amounts = mutableListOf<Entity>()
        AMOUNT_SYMBOL.findAll(text).take(maxPerKind).forEach { m ->
            val v = m.groupValues[1].replace(",", ".")
            amounts += Entity(Kind.AMOUNT, m.value, v, 0.85f, m.range.first)
        }
        AMOUNT_CONTEXT.findAll(text).take(maxPerKind).forEach { m ->
            val v = m.groupValues[1].replace(",", ".")
            if (amounts.none { it.normalized == v }) {
                amounts += Entity(Kind.AMOUNT, m.value, v, 0.80f, m.range.first)
            }
        }
        AMOUNT_UNIT.findAll(text).take(maxPerKind).forEach { m ->
            val v = m.groupValues[1].replace(",", ".")
            if (amounts.none { it.normalized == v }) {
                amounts += Entity(Kind.AMOUNT, m.value, v, 0.70f, m.range.first)
            }
        }
        // 精度过滤：0 元、0.00 这类是噪音（"优惠0""0元"）
        // 真实截图里 0 金额几乎没有信息价值，反而污染搜索
        out += amounts
            .distinctBy { it.normalized }
            .filter { e ->
                val v = e.normalized.toDoubleOrNull() ?: return@filter false
                v > 0.0
            }
            .take(maxPerKind)

        // ── 订单号 ──
        ORDER_NO.findAll(text).take(maxPerKind).forEach { m ->
            val v = m.groupValues[1]
            // 排除纯日期、纯重复数字
            if (v.all { it == v.first() }) return@forEach
            out += Entity(Kind.ORDER_NO, m.value, v, 0.85f, m.range.first)
        }

        // ── 物流单号 ──
        TRACKING.findAll(text).take(maxPerKind).forEach { m ->
            out += Entity(Kind.TRACKING, m.value, m.groupValues[1], 0.80f, m.range.first)
        }

        // ── 手机号 ──
        PHONE_CN.findAll(text).take(maxPerKind).forEach { m ->
            out += Entity(Kind.PHONE, m.value, m.groupValues[1], 0.80f, m.range.first)
        }

        // ── URL ──
        URL.findAll(text).take(maxPerKind).forEach { m ->
            out += Entity(Kind.URL, m.value, m.value.lowercase(), 0.90f, m.range.first)
        }

        // ── 邮箱 ──
        EMAIL.findAll(text).take(maxPerKind).forEach { m ->
            // 排除已被 URL 包含的
            if (out.none { it.kind == Kind.URL && it.raw.contains(m.value) }) {
                out += Entity(Kind.EMAIL, m.value, m.value.lowercase(), 0.90f, m.range.first)
            }
        }

        // ── 时间（仅带语境词）──
        TIME.findAll(text).take(maxPerKind).forEach { m ->
            val hh = m.groupValues.getOrNull(1).orEmpty().padStart(2, '0')
            val mm = m.groupValues.getOrNull(2).orEmpty().padStart(2, '0')
            if (hh.isNotBlank() && mm.isNotBlank()) {
                out += Entity(Kind.TIME, m.value, "$hh:$mm", 0.60f, m.range.first)
            }
        }

        return out
    }

    /**
     * 把实体转成可搜索的关键词 token。
     * 搜索"2025-03-15"或"3月15日"都能命中同一条。
     */
    fun searchTokens(e: Entity): List<String> = when (e.kind) {
        Kind.DATE -> {
            val norm = e.normalized
            buildList {
                add(norm) // 2025-03-15
                val parts = norm.split("-")
                if (parts.size == 2) add("${parts[0]}月${parts[1]}日")
                if (parts.size == 3) {
                    add("${parts[0]}年${parts[1].toIntOrNull()}月${parts[2].toIntOrNull()}日")
                    add("${parts[0]}/${parts[1]}/${parts[2]}")
                }
            }
        }
        Kind.AMOUNT -> listOf(e.normalized, "¥${e.normalized}", "${e.normalized}元")
        else -> listOf(e.normalized)
    }
}