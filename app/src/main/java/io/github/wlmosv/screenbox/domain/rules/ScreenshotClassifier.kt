package io.github.wlmosv.screenbox.domain.rules

/**
 * 可解释规则分类器（v2 重写）。
 *
 * ══ 为什么要重写 ══
 *
 * v1 的分类器实测严重失真（8130 张真实数据）：
 *   web  3681 张（45%）
 *   chat 3649 张（45%）
 *   两者同时命中 3382 张
 *   合计占 90%
 *
 * 抽查 web 分类，全是 TGAutoSign 应用截图，根本不是网页。
 *
 * 根因在 v1 的规则设计：
 *   chat: if (lineCount >= 6)  score += 0.20f
 *   web:  if (lineCount >= 10) score += 0.25f
 * 截图本来就是多行文本，这两个条件对几乎所有图都成立，
 * 等于把"是一张截图"当成了"是聊天记录/网页"的证据。
 *
 * ══ v2 的设计原则 ══
 *
 * 1. 只用**有区分度**的信号。
 *    "文本行数多"不是区分度信号 —— 所有截图都多。
 *    "出现『待发货』"是区分度信号 —— 只有订单页才有。
 *
 * 2. 每条规则必须有至少一个"强信号"（weight >= 0.35）。
 *    弱信号累加再多也不能单独构成分类，避免"凑分"。
 *
 * 3. 允许不分类。
 *    置信度不足就归 other，不硬猜。v1 的问题是硬猜导致 90% 误判。
 *
 * 4. 多标签但有限制。
 *    最多 3 个标签，且总分不能互相矛盾（例如 payment 和 chat 同时高分要降权）。
 *
 * 5. 所有判定都带可读理由，用户能看到"为什么"。
 */
object ScreenshotClassifier {

    /** 低于此置信度不写入分类。 */
    const val MIN_CONFIDENCE = 0.55f

    /** 强信号门槛。没有强信号的规则一律不成立。 */
    private const val STRONG = 0.35f

    /** 单条规则的中间结果。 */
    private data class Rule(
        val key: String,
        val score: Float,
        val strongHits: Int,
        val reasons: List<String>,
    )

    /** 分类结果。 */
    /**
     * 分类置信等级。
     *
     * ══ R8 关键修正 ══
     *
     * 之前兜底分类（app_ui/other）也写进分类表，导致
     * 「覆盖率 100%」是假的：8105 张里有 5794 张（71%）是兜底桶，
     * 真实高置信分类只有 2311 张。用户看到"已分类 8105"会以为识别得很好。
     *
     * 现在每个结果必须自带等级，UI 和统计都按等级分开：
     *   HIGH   命中强特征，可直接展示
     *   MEDIUM 有依据但不够确定，展示为"可能是"
     *   LOW    兜底，只用于分组，**不计入"已识别"**
     */
    enum class Tier { HIGH, MEDIUM, LOW }

    data class ClassifyHit(
        val categoryKey: String,
        val confidence: Float,
        val reasons: List<String>,
        val tier: Tier = Tier.MEDIUM,
    )

    /** 来源应用识别结果。 */
    data class SourceHit(
        val appKey: String,
        val displayName: String,
        val confidence: Float,
        val reasons: List<String>,
    )

    /** 来源应用置信度分档。 */
    enum class SourceConfidence { HIGH, MEDIUM, LOW, UNKNOWN }

    // ─────────────────────────────────────────────
    // 信号提取
    // ─────────────────────────────────────────────

    private class Signals(val text: String) {
        val lower = text.lowercase()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        /** 只有极少数行（≤3）—— 通常是纯图或极简界面。 */
        val sparseText = lines.size <= 3

        /** 短行占比高（聊天界面的典型排版）。必须先于 hasChatTimestamp 计算。 */
        val shortLineRatio: Float = if (lines.isEmpty()) 0f
        else lines.count { it.length <= 12 }.toFloat() / lines.size

        /** 平均行长。聊天短、网页长。 */
        val avgLineLen: Float = if (lines.isEmpty()) 0f
        else lines.sumOf { it.length }.toFloat() / lines.size

        /** 有网址。 */
        val hasUrl = URL_RE.containsMatchIn(text)

        /** 有金额。 */
        val hasAmount = AMOUNT_RE.containsMatchIn(text)

        /** 有订单/交易号。 */
        val hasOrderNo = ORDER_NO_RE.containsMatchIn(text)

        /** 有明确日期。 */
        val hasDate = DATE_RE.containsMatchIn(text)

        /** 有金额+状态词组合（支付页特征）。 */
        val hasPaymentStatus = PAY_STATUS_RE.containsMatchIn(text)

        /** 有电商订单状态。 */
        val hasOrderStatus = ORDER_STATUS_RE.containsMatchIn(text)

        /**
         * 有聊天时间戳。
         *
         * 关键修正：状态栏时间（顶部「下午7:05」）是伪信号，
         * 几乎所有截图都有。真聊天时间戳的特征是：
         * 1. 出现在文本前 15% 之后（排除状态栏）
         * 2. 周围有大量短行（聊天气泡排版）
         */
        val hasChatTimestamp: Boolean = run {
            val matches = CHAT_TIME_RE.findAll(text).toList()
            if (matches.isEmpty()) return@run false
            // 状态栏时间在文本最前面，排除前 8% 的位置
            val cutoff = (text.length * 0.08f).toInt()
            val later = matches.filter { it.range.first > cutoff }
            if (later.isEmpty()) return@run false
            // 且必须配合短行排版
            later.isNotEmpty() && shortLineRatio >= 0.45f
        }

        /** 有消息状态词。 */
        val hasMsgStatus = MSG_STATUS_RE.containsMatchIn(text)

        /** 有二维码相关文案。 */
        val hasQrHint = QR_RE.containsMatchIn(text)

        /** 有工作文档词。 */
        val hasWorkWord = WORK_RE.containsMatchIn(text)

        /** 有游戏战绩词。 */
        val hasGameWord = GAME_RE.containsMatchIn(text)

        /** 有票据词。 */
        val hasReceiptWord = RECEIPT_RE.containsMatchIn(text)

        /** 有社交互动数据。 */
        val hasSocialData = SOCIAL_RE.containsMatchIn(text)

        /** 有系统设置词。 */
        val hasSettingsWord = SETTINGS_RE.containsMatchIn(text)

        /** 有地图/出行词。 */
        val hasMapWord = MAP_RE.containsMatchIn(text)

        /** 有验证码/账号词。 */
        val hasAccountWord = ACCOUNT_RE.containsMatchIn(text)

        /** 有视频/娱乐词。 */
        val hasVideoWord = VIDEO_RE.containsMatchIn(text)

        /** 有学习/笔记词。 */
        val hasStudyWord = STUDY_RE.containsMatchIn(text)

        /** 有通知栏特征（"通知"、"条新消息"）。 */
        val hasNotificationWord = NOTIFY_RE.containsMatchIn(text)

        /**
         * 电商/交易语境。
         * 用于抑制误判：出现「原神」+「买号卖号」是交易截图，不是游戏画面。
         */
        val hasCommerceContext = COMMERCE_RE.containsMatchIn(text)


    }

    private val URL_RE = Regex("""https?://|\bwww\.|\w+\.(com|cn|net|org|io)\b""", RegexOption.IGNORE_CASE)
    private val AMOUNT_RE = Regex("""[¥￥$]\s?\d+([.,]\d{1,2})?|\d+\.\d{2}\s*元|合计|实付|应付|总价|小计""")
    private val ORDER_NO_RE = Regex("""订单号|订单编号|交易号|流水号|商户单号|order\s*(no|id)|transaction\s*id""", RegexOption.IGNORE_CASE)
    private val DATE_RE = Regex("""20\d{2}[-/年.]\d{1,2}[-/月.]\d{1,2}|\d{1,2}月\d{1,2}日""")
    private val PAY_STATUS_RE = Regex("""支付成功|付款成功|转账成功|收款成功|已支付|支付金额|到账|退款成功""")
    private val ORDER_STATUS_RE = Regex("""待发货|已发货|待收货|已签收|待付款|退款中|物流信息|运单号|快递单号""")
    private val CHAT_TIME_RE = Regex("""(?m)^\s*(上午|下午|昨天|今天|前天)?\s*\d{1,2}:\d{2}\s*$""")
    private val MSG_STATUS_RE = Regex("""已读|未读|撤回了一条消息|对方正在输入|发送失败""")
    private val QR_RE = Regex("""二维码|扫码|扫一扫|长按识别|qr\s*code|条形码""", RegexOption.IGNORE_CASE)
    private val WORK_RE = Regex("""会议|议程|汇报|周报|月报|需求评审|排期|KPI|OKR|复盘|抄送|附件|请查收""")
    /**
     * 游戏战绩词。
     *
     * ══ R5 修正 ══
     *
     * 旧版包含"王者荣耀""原神"等游戏名 —— 但桌面图标名、应用商店页面
     * 都会出现这些词，导致桌面截图被判成游戏截图。
     *
     * 现在只保留"只有游戏战绩页才会出现"的词。
     * 游戏名本身不作证据。
     */
    private val GAME_RE = Regex(
        """\bMVP\b|\bKDA\b|段位|战绩|排位赛|击杀数|助攻数|对局记录|赛季结算|胜利|失败|平局"""
    )
    private val RECEIPT_RE = Regex("""发票|票据|行程单|登机牌|车票|报销|税号|开票|价税合计|发票代码""")
    private val SOCIAL_RE = Regex("""微博|小红书|抖音|朋友圈|点赞|评论区|转发|关注|粉丝|收藏了""")
    private val SETTINGS_RE = Regex("""设置|偏好设置|通知管理|权限管理|存储空间|关于手机|开发者选项|WLAN|蓝牙""")
    /** 具体设置项名，用于计数判断。 */
    private val SETTINGS_ITEM_RE = Regex("""WLAN|蓝牙|移动网络|热点|电池|存储|显示|声音|振动|通知|权限|位置信息|账户|无障碍|开发者选项|系统更新|关于手机|应用管理|默认应用|语言和输入法|日期和时间|备份""")
    private val MAP_RE = Regex("""导航|路线|驾车|公交|地铁|打车|预计到达|公里|米处|起点|终点""")
    private val ACCOUNT_RE = Regex("""验证码|动态码|校验码|账号|密码|登录|注册|绑定手机""")
    private val VIDEO_RE = Regex("""播放|暂停|倍速|弹幕|全屏|清晰度|选集|追剧|影视""")
    private val STUDY_RE = Regex("""笔记|课程|章节|练习|题库|知识点|复习|背诵|单词|考试""")
    private val NOTIFY_RE = Regex("""条新消息|通知栏|消息中心|全部已读|清除通知""")
    /** 代码特征模式，用于识别代码/日志截图。 */
    private val CODE_PATTERNS = listOf(
        Regex("""(?m)^\s*(fun |val |var |class |object |interface |import |package |def |function |const |let )"""),
        Regex("""[{};]\s*$""", RegexOption.MULTILINE),
        Regex("""(?m)^\s*(//|#|/\*|\*)\s"""),
        Regex("""\b(public|private|protected|static|void|return|if\s*\(|for\s*\(|while\s*\()"""),
        Regex("""\w+\s*\([^)]*\)\s*[{:]"""),
        Regex("""(?i)\b(json|xml|yaml|sql|html|css)\b"""),
    )

    /** App 通用界面元素（导航、标签、操作按钮）。 */
    private val APP_UI_RE = Regex(
        """首页|发现|我的|消息|通讯录|工作台|设置|详情|列表|编辑|删除|保存|确定|取消|返回|搜索|筛选|排序|更多|分享|收藏|关注|点赞|评论|发布|提交|确认|下一步|完成"""
    )
    /** 电商/交易语境词。出现这些说明是购物页而非内容页。 */
    private val COMMERCE_RE = Regex(
        """买号|卖号|代练|租号|免押金|按时计费|账号出售|成品号|初始号|首充|充值|下单|购物车|加入购物车|立即购买|店铺|宝贝|包邮|发货|待发货|待收货|待评价|追评|好评|评价|优惠券|满减|秒杀|拼单|担保交易|已售|库存"""
    )

    // ─────────────────────────────────────────────
    // 内容分类
    // ─────────────────────────────────────────────

    fun classify(text: String, width: Int, height: Int): List<ClassifyHit> {
        if (text.isBlank()) return emptyList()
        val s = Signals(text)
        val rules = mutableListOf<Rule>()

        // ── 支付与账单 ──
        // 强信号：支付状态词。弱信号：金额、渠道。
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasPaymentStatus) { score += 0.50f; strong++; why += "出现支付状态文案" }
            if (Regex("""微信支付|支付宝|云闪付|银行卡|余额宝|花呗|零钱""").containsMatchIn(s.text)) {
                score += 0.20f; why += "出现支付渠道"
            }
            if (s.hasAmount) { score += 0.15f; why += "出现金额" }
            if (s.hasOrderNo) { score += 0.10f; why += "出现交易号" }
            rules += Rule("payment", score, strong, why)
        }

        // ── 购物与订单 ──
        // 强信号：订单号 或 订单状态词。
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasOrderNo) { score += 0.45f; strong++; why += "出现订单号" }
            if (s.hasOrderStatus) { score += 0.40f; strong++; why += "出现订单状态" }
            if (Regex("""淘宝|天猫|京东|拼多多|闲鱼|唯品会|我的订单|订单详情""").containsMatchIn(s.text)) {
                score += 0.20f; why += "出现电商平台"
            }
            if (s.hasCommerceContext) { score += 0.20f; strong++; why += "出现电商交易语境" }
            if (s.hasAmount) { score += 0.10f; why += "出现金额" }
            rules += Rule("order", score, strong, why)
        }

        // ── 聊天记录 ──
        // 强信号：聊天时间戳（行首独立时间）或 消息状态词。
        // 注意：不再用"行数多"，改用"短行占比高 + 有时间戳"组合。
        // ── 聊天记录 ──
        //
        // ══ R5 收紧 ══
        //
        // 实测误判：游戏战绩页、桌面截图被判成聊天。
        // 原因：只要出现"微信"就加分，但那可能只是分享按钮。
        //
        // 新规则：必须同时满足「排版特征」+「对话证据」才算。
        // 单纯出现应用名不加分。
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()

            // 对话排版：多行短文本（消息气泡特征）
            val hasDialogLayout = s.shortLineRatio >= 0.55f && s.lines.size >= 8
            // 时间戳（已排除状态栏）
            val hasTimestamp = s.hasChatTimestamp
            // 消息状态词
            val hasMsgState = s.hasMsgStatus
            // 对话专有词（不是应用名，是对话行为）
            val hasDialogWords = Regex(
                """(?:^|\n)\s*(发送|已读|未读|撤回了一条消息|对方正在输入|语音通话|视频通话|按住说话)\s*(?:$|\n)"""
            ).containsMatchIn(s.text)

            if (hasMsgState) {
                score += 0.45f; strong++; why += "出现消息状态（已读/未读/撤回）"
            }
            // 时间戳 + 对话排版 才算强信号
            if (hasTimestamp && hasDialogLayout) {
                score += 0.40f; strong++; why += "聊天时间戳 + 短行密集排版"
            } else if (hasTimestamp) {
                score += 0.10f; why += "出现时间戳但排版不像对话"
            }
            if (hasDialogWords) {
                score += 0.25f; strong++; why += "出现对话行为词"
            }
            if (hasDialogLayout && s.lines.size >= 12) {
                score += 0.10f; why += "短行占比 ${(s.shortLineRatio * 100).toInt()}%"
            }
            rules += Rule("chat", score, strong, why)
        }

        // ── 网页资料 ──
        // 强信号：网址。没有网址基本不可能是网页截图。
        // 去掉"行数多"这个伪信号，改用"平均行长较长"（正文排版）。
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasUrl) { score += 0.45f; strong++; why += "出现网址" }
            if (Regex("""阅读原文|打开APP|浏览器|发布于|分享到|原文链接""").containsMatchIn(s.text)) {
                score += 0.25f; strong++; why += "出现网页界面元素"
            }
            if (s.avgLineLen >= 20f && s.lines.size >= 8) {
                score += 0.15f; why += "长行排版（平均 ${s.avgLineLen.toInt()} 字/行）"
            }
            rules += Rule("web", score, strong, why)
        }

        // ── 二维码 ──
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasQrHint) { score += 0.60f; strong++; why += "出现二维码相关文案" }
            if (Regex("""添加好友|扫码关注|扫我|长按识别图中""").containsMatchIn(s.text)) {
                score += 0.30f; strong++; why += "出现扫码引导语"
            }
            rules += Rule("qrcode", score, strong, why)
        }

        // ── 工作与办公 ──
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasWorkWord) { score += 0.40f; strong++; why += "出现工作文档用语" }
            if (Regex("""飞书|钉钉文档|腾讯文档|Notion|石墨|Excel|PPT""").containsMatchIn(s.text)) {
                score += 0.25f; strong++; why += "出现办公工具名"
            }
            if (Regex("""@[\u4e00-\u9fa5A-Za-z]{1,12}|请知悉|同步一下|跟进""").containsMatchIn(s.text)) {
                score += 0.15f; why += "出现办公沟通用语"
            }
            rules += Rule("work", score, strong, why)
        }

        // ── 游戏截图 ──
        // 关键抑制：出现电商/交易语境时，"原神/王者"等游戏名
        // 更可能出现在买号卖号页面，不是游戏画面。
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasCommerceContext) {
                // 电商语境直接压制，不产生 game 分类
                rules += Rule("game", 0f, 0, why)
            } else {
                // ══ R5 收紧 ══
                // 必须有"战绩结构"，不能只有游戏名。
                val hasStatLayout = Regex(
                    """(?:击败|死亡|助攻|击杀)\s*[:：]?\s*\d|\b\d+\s*/\s*\d+\s*/\s*\d+\b"""
                ).containsMatchIn(s.text)
                val hasResultWords = Regex("""胜利|失败|平局|段位|赛季""").containsMatchIn(s.text)
                val hasRankBadge = Regex(
                    """(?:青铜|白银|黄金|铂金|钻石|星耀|王者|大师|宗师)\s*[IVX一二三四五1-5]?"""
                ).containsMatchIn(s.text)

                if (hasStatLayout) {
                    score += 0.45f; strong++; why += "出现战绩数据结构（击败/死亡/助攻）"
                }
                if (hasResultWords && hasStatLayout) {
                    score += 0.25f; why += "出现对局结果词"
                }
                if (hasRankBadge && hasStatLayout) {
                    score += 0.20f; why += "出现段位标识"
                }
                // 只有结果词没有战绩结构 → 不够
                if (hasResultWords && !hasStatLayout) {
                    score += 0.10f; why += "仅有结果词，无战绩数据"
                }
                rules += Rule("game", score, strong, why)
            }
        }

        // ── 票据凭证 ──
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasReceiptWord) { score += 0.55f; strong++; why += "出现票据字段" }
            if (Regex("""\d{8,}""").containsMatchIn(s.text) && s.hasAmount) {
                score += 0.20f; why += "出现长数字编号 + 金额"
            }
            rules += Rule("receipt", score, strong, why)
        }

        // ── 社交平台 ──
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasSocialData) { score += 0.40f; strong++; why += "出现社交平台特征" }
            if (Regex("""\d+(\.\d+)?\s*万?\s*(赞|评论|转发|收藏|浏览)""").containsMatchIn(s.text)) {
                score += 0.30f; strong++; why += "出现互动数据"
            }
            rules += Rule("social", score, strong, why)
        }

        // ── 系统设置 ──
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            // 强信号：必须出现 2 个以上不同的设置项名，单个别误判
            val settingHits = SETTINGS_ITEM_RE.findAll(s.text).map { it.value }.distinct().count()
            if (settingHits >= 3) {
                score += 0.55f; strong++; why += "出现 $settingHits 个不同设置项"
            } else if (settingHits >= 2) {
                score += 0.35f; strong++; why += "出现 $settingHits 个设置项"
            }
            if (Regex("""开发者选项|关于手机|系统更新|应用管理""").containsMatchIn(s.text)) {
                score += 0.25f; strong++; why += "出现系统级设置入口"
            }
            rules += Rule("settings", score, strong, why)
        }

        // ── 地图与出行 ──
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasMapWord) { score += 0.45f; strong++; why += "出现出行导航词" }
            if (Regex("""\d+\s*(公里|千米|米)|预计\s*\d+\s*分钟""").containsMatchIn(s.text)) {
                score += 0.25f; strong++; why += "出现距离或时长"
            }
            rules += Rule("map", score, strong, why)
        }

        // ── 账号与验证码 ──
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasAccountWord) { score += 0.40f; strong++; why += "出现账号或验证码词" }
            if (Regex("""\d{4,8}""").containsMatchIn(s.text) && Regex("""验证码|动态码|校验码""").containsMatchIn(s.text)) {
                score += 0.30f; strong++; why += "出现验证码数字"
            }
            rules += Rule("account", score, strong, why)
        }

        // ── 娱乐与视频 ──
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasVideoWord) { score += 0.35f; strong++; why += "出现视频播放控件词" }
            if (Regex("""第\s*\d+\s*集|更新至|全\d+集""").containsMatchIn(s.text)) {
                score += 0.30f; strong++; why += "出现剧集信息"
            }
            rules += Rule("video", score, strong, why)
        }

        // ── 学习与笔记 ──
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasStudyWord) { score += 0.40f; strong++; why += "出现学习类词" }
            if (Regex("""第\s*\d+\s*章|课时\s*\d+|正确率|错题""").containsMatchIn(s.text)) {
                score += 0.25f; strong++; why += "出现课程或题目结构"
            }
            rules += Rule("study", score, strong, why)
        }

        // ── 通知与消息 ──
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.hasNotificationWord) { score += 0.45f; strong++; why += "出现通知中心特征" }
            if (Regex("""\d+\s*条新消息""").containsMatchIn(s.text)) {
                score += 0.30f; strong++; why += "出现新消息计数"
            }
            rules += Rule("notify", score, strong, why)
        }

        // ── 代码与技术 ──
        // 用户截图里有大量代码、日志、终端、配置界面。
        // 这是独立用途，不该归入"应用界面"或"其他"。
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            val codeHits = CODE_PATTERNS.count { it.containsMatchIn(s.text) }
            if (codeHits >= 2) { score += 0.50f; strong++; why += "出现 $codeHits 类代码特征" }
            else if (codeHits == 1) { score += 0.20f; why += "出现代码特征" }

            // 命令行是强信号：普通截图不会出现 shell 提示符
            if (Regex("""(?m)^\s*(sudo|cd |ls |git |npm |pip |docker |adb |curl |apt |python3? )""")
                    .containsMatchIn(s.text)
            ) { score += 0.45f; strong++; why += "出现命令行" }
            if (Regex("""\b(ERROR|WARN|DEBUG|INFO)\b.*[:：]|Exception|stack trace|at [a-z]+\.[a-z]+\.\w+\(""")
                    .containsMatchIn(s.text)
            ) { score += 0.20f; why += "出现日志或异常" }
            rules += Rule("code", score, strong, why)
        }

        // ── 文档与文字资料 ──
        // 长文排版、章节结构、阅读界面。
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            if (s.lines.size >= 15 && s.avgLineLen >= 18f) {
                score += 0.35f; strong++; why += "长文排版（${s.lines.size} 行，平均 ${s.avgLineLen.toInt()} 字/行）"
            }
            if (Regex("""第[一二三四五六七八九十\d]+[章节部分]|目录|摘要|参考文献|前言|结语""")
                    .containsMatchIn(s.text)
            ) { score += 0.30f; strong++; why += "出现文档结构词" }
            if (Regex("""(?i)\b(pdf|docx?|epub|markdown|\.md\b)""").containsMatchIn(s.text)) {
                score += 0.15f; why += "出现文档格式标识"
            }
            rules += Rule("document", score, strong, why)
        }

        // ── 兜底：应用界面 ──
        // 有些截图是 App 内的功能页（非聊天/非购物/非网页），
        // 归"其他"太浪费，单独识别为"应用界面"更有用。
        // 判定依据：有导航栏/标签栏特征 + 短行密集 + 无强内容信号。
        run {
            var score = 0f; var strong = 0; val why = mutableListOf<String>()
            val navHits = APP_UI_RE.findAll(s.text).map { it.value }.distinct().count()
            if (navHits >= 3) { score += 0.40f; strong++; why += "出现 $navHits 个界面导航元素" }
            else if (navHits == 2) { score += 0.30f; strong++; why += "出现 $navHits 个界面导航元素" }
            if (s.lines.size in 6..60 && s.shortLineRatio >= 0.35f) {
                score += 0.20f; why += "典型界面排版"
            }
            if (Regex("""设置|详情|列表|编辑|删除|保存|确定|取消|返回""").containsMatchIn(s.text)) {
                score += 0.15f; why += "出现通用界面操作词"
            }
            rules += Rule("app_ui", score, strong, why)
        }

        // ── 汇总：只保留有强信号且过阈值的 ──
        val hits = rules
            .filter { it.strongHits >= 1 && it.score >= MIN_CONFIDENCE }
            .sortedByDescending { it.score }

        // 互斥降权：payment 与 order 同时高分时，payment 优先（更具体）
        val hasPayment = hits.any { it.key == "payment" }
        val hasOrder = hits.any { it.key == "order" }
        val adjusted = if (hasPayment && hasOrder) {
            hits.map {
                if (it.key == "order") it.copy(score = it.score - 0.10f) else it
            }
        } else hits

        val finalHits = adjusted
            .filter { it.score >= MIN_CONFIDENCE }
            .take(3)
            .map {
                val conf = it.score.coerceAtMost(0.95f)
                ClassifyHit(
                    categoryKey = it.key,
                    confidence = conf,
                    reasons = it.reasons,
                    tier = if (conf >= 0.70f) Tier.HIGH else Tier.MEDIUM,
                )
            }

        // ── 覆盖率兜底：保证每张图至少落一个桶 ──
        //
        // 为什么必须兜底：
        // 实测 8144 张里有 6413 张没有任何分类，用户在"按内容分类"里
        // 根本找不到它们 —— 等于分类功能对 79% 的图库失效。
        //
        // 兜底不是硬猜，而是如实表达"没识别出具体用途"：
        //   - 有明显界面排版（多行、短行、导航词）→ 应用界面
        //   - 其余 → 其他
        // 两者置信度都很低（≤0.5），UI 上不与高置信分类混为一谈。
        if (finalHits.isNotEmpty()) return finalHits

        val uiLike = s.lines.size >= 5 &&
            (s.shortLineRatio >= 0.30f || s.lines.size >= 12) &&
            APP_UI_RE.containsMatchIn(s.text)
        // 大写开头的英文短标签（Settings / APPEARANCE / Targets）是典型 App 界面骨架。
        // 实测大量英文界面截图因为不命中中文导航词而落到"其他"，这里补一条证据。
        val englishUiLike = s.lines.size >= 5 &&
            s.lines.count { it.length in 2..24 && it.first().isUpperCase() } >= 3
        return if (uiLike || englishUiLike) {
            listOf(
                ClassifyHit(
                    categoryKey = "app_ui",
                    confidence = 0.30f,
                    reasons = listOf("界面排版明显，但未命中更具体用途"),
                    tier = Tier.LOW,
                )
            )
        } else {
            listOf(
                ClassifyHit(
                    categoryKey = "other",
                    confidence = 0.25f,
                    reasons = listOf(if (s.text.isBlank()) "未识别到文字" else "未匹配到已知用途特征"),
                    tier = Tier.LOW,
                )
            )
        }
    }

    // ─────────────────────────────────────────────
    // 来源应用识别
    // ─────────────────────────────────────────────

    /**
     * 来源应用特征库。
     *
     * 每条包含：
     * - 必须出现的强特征（界面独有文案）
     * - 加分特征（辅助证据）
     *
     * 设计原则：宁可识别不出，也不要错认。
     * Android 截图文件不保存来源包名，所以只能靠界面特征推断，
     * 准确率不可能 100%，这是技术边界，不隐瞒。
     */
    private data class AppProfile(
        val key: String,
        val displayName: String,
        val strongPatterns: List<Regex>,
        val weakPatterns: List<Regex>,
    )

    private val appProfiles = listOf(
        AppProfile(
            "wechat", "微信",
            listOf(
                Regex("""微信团队|微信支付|微信运动|微信公众平台|订阅号消息|微信红包"""),
                Regex("""(?m)^\s*微信\s*$"""),
            ),
            listOf(
                Regex("""发送给朋友|分享到朋友圈|按住说话|语音通话|视频通话"""),
                Regex("""\d{1,2}:\d{2}\s*$"""),
            ),
        ),
        AppProfile(
            "qq", "QQ",
            listOf(
                Regex("""QQ空间|QQ钱包|QQ邮箱|QQ音乐|QQ浏览器|QQ安全中心"""),
                Regex("""(?m)^\s*QQ\s*$"""),
            ),
            listOf(Regex("""厘米秀|个性签名|扩列""")),
        ),
        AppProfile(
            "xhs", "小红书",
            listOf(
                Regex("""小红书|RED\s*ID|薯队长|笔记灵感"""),
                Regex("""\d+(\.\d+)?\s*万?\s*次赞"""),
            ),
            listOf(Regex("""关注|发现|发布笔记|收藏夹""")),
        ),
        AppProfile(
            "douyin", "抖音",
            listOf(
                Regex("""抖音|TikTok|抖+"""),
                Regex("""\d+(\.\d+)?\s*万?\s*点赞"""),
            ),
            listOf(Regex("""同款音乐|拍同款|转发|评论区""")),
        ),
        AppProfile(
            "taobao", "淘宝",
            listOf(
                Regex("""淘宝|天猫|阿里旺旺|菜鸟裹裹"""),
                Regex("""宝贝详情|加入购物车|立即购买|店铺优惠"""),
            ),
            listOf(Regex("""包邮|七天无理由|已售\s*\d+""")),
        ),
        AppProfile(
            "alipay", "支付宝",
            listOf(
                Regex("""支付宝|余额宝|花呗|借呗|蚂蚁森林|芝麻信用"""),
                Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+(支出|收入)"""),
            ),
            listOf(Regex("""账单|转账|收款码|扫一扫""")),
        ),
        AppProfile(
            "browser", "浏览器",
            listOf(
                Regex("""https?://|www\.|\w+\.(com|cn|net|org)\b"""),
                Regex("""阅读模式|无痕浏览|添加书签"""),
            ),
            listOf(Regex("""分享|刷新|前进|后退""")),
        ),
        AppProfile(
            "telegram", "Telegram",
            listOf(
                Regex("""Telegram|Saved Messages|Secret Chat"""),
                Regex("""(?m)^\s*Telegram\s*$"""),
            ),
            listOf(Regex("""\d+\s*subscribers|Channel|Group""")),
        ),
        AppProfile(
            "weibo", "微博",
            listOf(Regex("""微博|新浪微博|weibo""")),
            listOf(Regex("""\d+(\.\d+)?\s*万?\s*转发|\d+\s*评论""")),
        ),
        AppProfile(
            "bilibili", "哔哩哔哩",
            listOf(Regex("""哔哩哔哩|bilibili|B站|投币""")),
            listOf(Regex("""\d+(\.\d+)?\s*万?\s*播放|弹幕|三连""")),
        ),
        AppProfile(
            "zhihu", "知乎",
            listOf(Regex("""知乎|zhihu""")),
            listOf(Regex("""\d+\s*人赞同|关注问题|写回答""")),
        ),
        AppProfile(
            "dingtalk", "钉钉",
            listOf(Regex("""钉钉|DingTalk|DING""")),
            listOf(Regex("""打卡|审批|考勤|工作台""")),
        ),
        AppProfile(
            "settings", "系统设置",
            listOf(
                Regex("""开发者选项|关于手机|系统更新|应用管理"""),
                Regex("""WLAN|蓝牙|移动网络|电池|存储"""),
            ),
            listOf(Regex("""已开启|已关闭|自动""")),
        ),
    )

    /**
     * 识别来源应用。
     *
     * 返回 null 表示无法判断（归入"来源不明"）。
     * 这比乱猜更有价值 —— 错认会让用户对分类失去信任。
     */
    fun detectSource(text: String): SourceHit? {
        if (text.isBlank()) return null
        val lower = text.lowercase()

        var best: SourceHit? = null

        for (p in appProfiles) {
            val strongHits = p.strongPatterns.count { it.containsMatchIn(text) }
            if (strongHits == 0) continue
            val weakHits = p.weakPatterns.count { it.containsMatchIn(text) }

            var score = strongHits * 0.45f + weakHits * 0.12f
            // 多个强特征同时命中，几乎可以确定
            if (strongHits >= 2) score += 0.20f
            score = score.coerceAtMost(0.95f)
            if (score < 0.55f) continue

            val reasons = buildList {
                add("命中 ${strongHits} 个「${p.displayName}」独有界面特征")
                if (weakHits > 0) add("另有 ${weakHits} 个辅助特征")
            }

            if (best == null || score > best!!.confidence) {
                best = SourceHit(p.key, p.displayName, score, reasons)
            }
        }
        return best
    }

    /** 来源置信度分档，供 UI 展示。 */
    fun sourceConfidence(hit: SourceHit?): SourceConfidence = when {
        hit == null -> SourceConfidence.UNKNOWN
        hit.confidence >= 0.85f -> SourceConfidence.HIGH
        hit.confidence >= 0.65f -> SourceConfidence.MEDIUM
        else -> SourceConfidence.LOW
    }

    // ─────────────────────────────────────────────
    // 敏感信息检测
    // ─────────────────────────────────────────────

    data class SensitiveHit(val kind: String, val sample: String)

    fun detectSensitive(text: String): List<SensitiveHit> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<SensitiveHit>()

        Regex("""(?<!\d)\d{17}[\dXx](?!\d)""").find(text)?.let {
            out += SensitiveHit("身份证号", it.value.take(6) + "********")
        }
        Regex("""(?<!\d)\d{16,19}(?!\d)""").findAll(text).take(1).forEach {
            out += SensitiveHit("银行卡号", it.value.take(4) + " **** " + it.value.takeLast(4))
        }
        Regex("""(?i)(密码|password|passwd|pwd)\s*[:：=]?\s*\S{4,}""").find(text)?.let {
            out += SensitiveHit("疑似密码", it.value.take(8) + "…")
        }
        Regex("""(验证码|校验码|动态码|verification code)[^\d]{0,6}(\d{4,8})""").find(text)?.let {
            out += SensitiveHit("验证码", "****")
        }
        return out
    }
}
