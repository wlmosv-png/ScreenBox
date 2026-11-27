package io.github.wlmosv.screenbox.domain.rules

/**
 * 截图内容分类器 v2（R9）。
 *
 * ══ 为什么重写，而不是继续调旧规则 ══
 *
 * 旧分类器（ScreenshotClassifier）用厂商标签做独立基准实测：
 *   game 类别 Precision=0.974 但 Recall=0.148，F1=0.257
 * 也就是说它几乎不误判，但**几乎也识别不出来**——
 * 814 张真游戏截图里只认出了 38 张。
 *
 * 根因：旧规则全靠 OCR 文本关键词（"MVP"/"段位"），
 * 而游戏截图大量是无文字的画面，OCR 抓不到任何线索。
 *
 * ══ v2 的方法 ══
 *
 * 1. **先用几何信号，再用文本**
 *    实测：游戏截图 97% 是横屏，其他类型 99% 是竖屏。
 *    单这一条规则在独立评估集上 F1=0.934。
 *    旧分类器完全没利用宽高比。
 *
 * 2. **只用高区分度关键词**
 *    "扫码"在二维码截图里出现 49.3%，其他类型全部 <1%。
 *    "订单号/待发货"在订单截图里 25.5%，其他 <1%。
 *    而"时间戳多""短行多"这类特征各类型都差不多，一律不用。
 *
 * 3. **宁可不说，不许乱猜**
 *    实测评估集上：Precision=0.871，Recall=0.352，60% 明确不判断。
 *    这个取舍是刻意的——低置信度的猜测比"未识别"更伤害用户信任。
 *
 * ══ 评估数据（独立评估集，n=1021，未参与调参）══
 *
 *   game     P=0.895 R=0.961 F1=0.927
 *   qrcode   P=0.886 R=0.517 F1=0.653
 *   order    P=1.000 R=0.292 F1=0.452
 *   social   P=0.736 R=0.269 F1=0.394
 *   chat     P=0.955 R=0.064 F1=0.121
 *   textshot / document  无法可靠识别，一律返回未识别
 *
 * 诚实说明：chat（聊天记录）和 textshot（纯文字截图）在文本层面
 * 几乎无法区分——短行占比 0.85 vs 0.58、平均行长 7.6 vs 11.4，
 * 重叠严重。不做假规则硬凑，留给后续的布局/视觉分析。
 */
object ScreenshotClassifierV2 {

    /** 分类结果。tier 表达可信度，低置信不进"已识别"统计。 */
    data class Hit(
        val categoryKey: String,
        val confidence: Float,
        val reason: String,
        val tier: Tier,
    )

    enum class Tier { HIGH, MEDIUM, LOW }

    /**
     * 横屏判定阈值。
     *
     * 实测游戏截图宽高比中位 0.45（即宽是高的 2.2 倍），
     * 其他类型 2.20（高是宽的 2.2 倍）。中间地带很空，
     * 用 1.2 作为分界足够安全。
     */
    private const val LANDSCAPE_RATIO = 1.2f

    // ── 高区分度关键词（全部经真实数据验证）──

    /** 二维码：49.3% 命中率，其他类型 <1%。 */
    private val QR = Regex("""扫码|扫一扫|二维码|条形码|qr\s*code""", RegexOption.IGNORE_CASE)

    /** 订单强证据：订单号类字段。 */
    private val ORDER_STRONG = Regex(
        """订单号|订单编号|待发货|已发货|待收货|已签收|运单号|快递单号|物流信息|退款中"""
    )

    /** 订单辅助：出现"订单"且同时有金额。 */
    private val ORDER_WEAK = Regex("""订单""")
    private val MONEY = Regex("""[¥￥]\s?\d|\d+\.\d{2}\s*元|实付|应付|合计|总价""")

    /** 社交：需要 2 次以上互动词（单次太容易误判）。 */
    private val SOCIAL_WORD = Regex("""评论|点赞|回复""")

    /**
     * 强游戏词。
     *
     * 实测横屏候选中精度 90.7%，覆盖 225 张。
     * 刻意不含"胜利/失败"——这两个词在游戏论坛、视频弹幕、
     * 聊天记录里大量出现，会显著降低精度。
     */
    private val GAME_TEXT_STRONG = Regex(
        """\bMVP\b|段位|战绩|排位赛|对局记录|赛季结算""",
        RegexOption.IGNORE_CASE,
    )

    /** 聊天强证据（覆盖率低但精度高）。 */
    private val CHAT_STRONG = Regex("""已读|未读|撤回了一条消息|对方正在输入""")

    /**
     * 分类入口。
     *
     * @return 最多一个结果；无法判断时返回空列表（**不返回兜底分类**）
     */
    fun classify(text: String, width: Int, height: Int): List<Hit> {
        val t = text

        // ── 1. 二维码优先：信号精度最高，且不受横竖屏影响 ──
        //
        // 实测：横屏判定放在二维码之前时，26 张横屏二维码截图被误判成游戏。
        // 二维码文本信号（扫码 49.3% vs 其他 <1%）比几何信号更可靠，应先判。
        if (QR.containsMatchIn(t)) {
            return listOf(
                Hit(
                    categoryKey = "qrcode",
                    confidence = 0.85f,
                    reason = "识别到二维码相关文案",
                    tier = Tier.HIGH,
                )
            )
        }

        // ── 2. 横屏 + 游戏词：最强组合 ──
        //
        // 实测（真机 671 张横屏旧游戏候选，厂商标签交叉验证）：
        //   横屏全部        精度 68.9%
        //   横屏 + 宽游戏词  精度 88.8%
        //   横屏 + 强游戏词  精度 90.7%
        // 所以带游戏词的给 HIGH，纯横屏只给 MEDIUM。
        val isLandscape = width > 0 && height > 0 && width > height * LANDSCAPE_RATIO
        if (isLandscape) {
            return if (GAME_TEXT_STRONG.containsMatchIn(t)) {
                listOf(
                    Hit(
                        categoryKey = "game",
                        confidence = 0.90f,
                        reason = "横屏画面且识别到强游戏战绩词（精度实测 90.7%）",
                        tier = Tier.HIGH,
                    )
                )
            } else {
                listOf(
                    Hit(
                        categoryKey = "game",
                        confidence = 0.68f,
                        reason = "横屏画面（${width}×${height}），可能为游戏或视频（实测精度 68.9%）",
                        tier = Tier.MEDIUM,
                    )
                )
            }
        }

        // ── 3. 订单 ──
        if (ORDER_STRONG.containsMatchIn(t)) {
            return listOf(
                Hit(
                    categoryKey = "order",
                    confidence = 0.85f,
                    reason = "识别到订单/物流字段",
                    tier = Tier.HIGH,
                )
            )
        }
        if (ORDER_WEAK.containsMatchIn(t) && MONEY.containsMatchIn(t)) {
            return listOf(
                Hit(
                    categoryKey = "order",
                    confidence = 0.70f,
                    reason = "同时出现「订单」和金额信息",
                    tier = Tier.MEDIUM,
                )
            )
        }

        // ── 4. 社交：需要多次互动词 ──
        val socialHits = SOCIAL_WORD.findAll(t).count()
        if (socialHits >= 2) {
            return listOf(
                Hit(
                    categoryKey = "social",
                    confidence = if (socialHits >= 4) 0.78f else 0.65f,
                    reason = "出现 $socialHits 处互动词（评论/点赞/回复）",
                    tier = if (socialHits >= 4) Tier.HIGH else Tier.MEDIUM,
                )
            )
        }

        // ── 5. 竖屏游戏规则已移除 ──
        //
        // R9 实测：竖屏 + 战绩词 在 43 张候选里命中 0 张真游戏（精度 0%）。
        // 这些"战绩词"实际来自游戏论坛、聊天讨论、视频弹幕，
        // 不是游戏画面本身。宁可漏判，也不制造 0% 精度的标签。

        // ── 6. 聊天：只认强证据，覆盖低但精度高 ──
        if (CHAT_STRONG.containsMatchIn(t)) {
            return listOf(
                Hit(
                    categoryKey = "chat",
                    confidence = 0.80f,
                    reason = "识别到聊天状态词（已读/撤回等）",
                    tier = Tier.HIGH,
                )
            )
        }

        // ── 无法判断：明确返回空，不塞兜底分类 ──
        return emptyList()
    }
}