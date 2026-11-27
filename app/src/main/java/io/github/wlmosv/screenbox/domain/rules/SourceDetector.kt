package io.github.wlmosv.screenbox.domain.rules

/**
 * 来源应用识别（v3）。
 *
 * ══ 为什么重写 ══
 *
 * v2 只做全文本正则匹配，实测 4870/4991 = 98% 归入"来源不明"。
 * 抽查真实截图后发现两个问题：
 *
 * 1. 只在全文里找"微信/小红书"这种大厂名字
 *    但用户的截图大量是自研 App（TGAutoSign/WPAForge）、
 *    系统设置、游戏、GitHub 等，根本不在词表里。
 *
 * 2. 没有利用「界面结构」
 *    截图有明确的空间结构：顶部标题栏 / 中间内容 / 底部导航。
 *    OCR 文本顺序大致对应这个结构，开头 200 字符 ≈ 标题栏区域。
 *    标题栏里的信息权重应该远高于正文。
 *
 * ══ v3 的策略 ══
 *
 * 1. 分区加权：头部（标题栏）命中权重 ×2，尾部（导航栏）命中 ×1.5
 * 2. 补充真实应用特征（系统设置、浏览器、聊天、游戏库等通用类型）
 * 3. 引入"界面骨架"信号：状态栏 + 导航栏组合能判断"是某类 App"
 * 4. 保留"宁可不明也不乱猜"原则，但把临界值调优
 */
object SourceDetector {

    data class Hit(
        val appKey: String,
        val displayName: String,
        val confidence: Float,
        val evidence: List<String>,
    )

    enum class Level { HIGH, MEDIUM, LOW, UNKNOWN }

    /**
     * 一个应用的识别特征。
     *
     * header: 只在头部区域（前 25%）匹配 —— 这些是标题栏/品牌标识
     * anywhere: 全文匹配 —— 这些是强干扰词
     * body: 只在正文匹配，排除头部
     */
    private class Profile(
        val key: String,
        val name: String,
        val header: List<Regex>,
        val anywhere: List<Regex>,
        val body: List<Regex> = emptyList(),
        /**
         * 全文强证据。命中即给高分（0.55/项，最多 2 项）。
         *
         * 为什么需要它：
         * 有些证据本身极强，但 OCR 的位置不固定 ——
         * 例如浏览器地址栏，可能出现在第 1 行，也可能在第 5 行
         * （状态栏 + 信号 + 电量先占了几行）。
         * 只靠 header 会漏掉大量浏览器截图。
         */
        val strongAnywhere: List<Regex> = emptyList(),
    )

    /**
     * 特征库。
     *
     * 分两类：
     * A. 具体应用（微信/QQ/淘宝…）—— 品牌特征明确
     * B. 通用来源类型（系统设置/浏览器/聊天工具/游戏库）—— 界面骨架明确
     *
     * 用户大量截图属于 B 类，这是 v2 完全没覆盖的部分。
     */
    private val profiles = listOf(

        // ── A. 具体应用 ──

        Profile(
            "wechat", "微信",
            header = listOf(
                Regex("""(?m)^\s*微信\s*$"""),
                Regex("""^微信[·\s]"""),
                Regex("""微信(团队|支付|运动|红包|公众平台)"""),
            ),
            anywhere = listOf(
                Regex("""微信团队|微信支付|微信运动|微信公众平台|订阅号消息"""),
            ),
            body = listOf(
                Regex("""发送给朋友|分享到朋友圈|按住说话|语音通话|视频通话"""),
                Regex("""撤回了一条消息|对方正在输入"""),
            ),
        ),

        Profile(
            "qq", "QQ",
            header = listOf(
                Regex("""(?m)^\s*QQ\s*$"""),
                Regex("""^QQ[·\s]"""),
            ),
            anywhere = listOf(
                Regex("""QQ空间|QQ钱包|QQ邮箱|QQ安全中心"""),
            ),
            body = listOf(Regex("""厘米秀|个性签名|扩列|群文件""")),
        ),

        Profile(
            "telegram", "Telegram",
            header = listOf(
                Regex("""(?i)\bTelegram\b"""),
                Regex("""Saved Messages|Secret Chat"""),
            ),
            anywhere = listOf(
                Regex("""(?i)telegram\.org|t\.me/"""),
                Regex("""\d+\s*(members|subscribers|在线)"""),
            ),
        ),

        Profile(
            "xhs", "小红书",
            header = listOf(Regex("""小红书|RED\s*ID|薯队长""")),
            anywhere = listOf(Regex("""\d+(\.\d+)?\s*万?\s*次赞|笔记灵感""")),
            body = listOf(Regex("""发布笔记|关注|发现|购物车""")),
        ),

        Profile(
            "douyin", "抖音",
            header = listOf(Regex("""抖音|TikTok""")),
            anywhere = listOf(Regex("""\d+(\.\d+)?\s*万?\s*点赞|拍同款|同款音乐""")),
        ),

        Profile(
            "taobao", "淘宝",
            header = listOf(Regex("""淘宝|天猫""")),
            anywhere = listOf(
                Regex("""宝贝详情|加入购物车|立即购买|店铺优惠"""),
                Regex("""阿里旺旺|菜鸟裹裹"""),
            ),
        ),

        Profile(
            "alipay", "支付宝",
            header = listOf(Regex("""支付宝|余额宝|花呗|借呗""")),
            anywhere = listOf(
                Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+(支出|收入)"""),
                Regex("""蚂蚁森林|芝麻信用|收款码"""),
            ),
        ),

        Profile(
            "weibo", "微博",
            header = listOf(Regex("""微博|新浪微博|weibo""")),
            anywhere = listOf(Regex("""\d+(\.\d+)?\s*万?\s*转发|\d+\s*评论""")),
        ),

        Profile(
            "bilibili", "哔哩哔哩",
            header = listOf(Regex("""哔哩哔哩|bilibili|B站""")),
            anywhere = listOf(Regex("""\d+(\.\d+)?\s*万?\s*播放|弹幕|投币|三连""")),
        ),

        Profile(
            "zhihu", "知乎",
            header = listOf(Regex("""知乎""")),
            anywhere = listOf(Regex("""\d+\s*人赞同|写回答|关注问题""")),
        ),

        Profile(
            "boss", "BOSS直聘",
            header = listOf(Regex("""BOSS直聘|BOSS\s*直聘""")),
            anywhere = listOf(Regex("""首次验证通过即注册|直聊|投递简历|沟通中""")),
        ),

        Profile(
            "github", "GitHub",
            header = listOf(Regex("""(?i)\bgithub\b""")),
            anywhere = listOf(
                Regex("""(?i)README\.md|pull request|commit|fork|star"""),
                Regex("""(?i)浏览代码|提交|issues"""),
            ),
        ),

        Profile(
            "dingtalk", "钉钉",
            header = listOf(Regex("""钉钉|DingTalk""")),
            anywhere = listOf(Regex("""打卡|审批|考勤|工作台|DING""")),
        ),

        // ── B. 通用来源类型（用户大量截图属于这里）──

        Profile(
            "sys_settings", "系统设置",
            header = listOf(
                Regex("""(?m)^\s*设置\s*$"""),
                Regex("""WiFi|WLAN|无线和网络|连接与共享"""),
                Regex("""中国电信|中国移动|中国联通.*网关"""),
            ),
            anywhere = listOf(
                Regex("""开发者选项|关于手机|系统更新|应用管理|默认应用"""),
            ),
            body = listOf(
                Regex("""信号强度|WiFi\s*名称|安全类型|IP\s*地址|子网掩码"""),
                Regex("""已开启|已关闭|自动连接|省电模式"""),
            ),
            strongAnywhere = listOf(
                // 英文系统设置界面（实测样本里有大量英文 ROM）
                Regex("""(?i)\b(developer options|about phone|system update|app management|default apps)\b"""),
                Regex("""(?i)\b(wlan|bluetooth|battery|storage|display|sound|vibration)\b.*\n.*\b(on|off)\b"""),
            ),
        ),

        Profile(
            "browser", "浏览器",
            header = listOf(
                Regex("""^https?://|^www\."""),
                Regex("""(?i)chrome|edge|firefox|via\s*浏览器"""),
            ),
            anywhere = listOf(
                Regex("""https?://\w+\.\w+|\.com/|\.cn/|\.org/"""),
            ),
            body = listOf(
                Regex("""阅读模式|无痕浏览|添加书签|新建标签页|分享|刷新"""),
                Regex("""(?m)^\s*\d{1,2}:\d{2}\s*$"""),
            ),
            strongAnywhere = listOf(
                // 带协议头的完整网址：浏览器地址栏的强证据
                Regex("""(?i)https?://[a-z0-9.-]+\.[a-z]{2,}"""),
                // 裸域名：OCR 常把 https:// 丢掉，只剩 host.tld
                // 刻意排除 io/co/me 这类短 TLD —— 代码里的包名
                // （io.github.xxx）会被误判成浏览器。
                Regex("""(?i)\b[a-z0-9][a-z0-9-]{1,40}\.(com|cn|net|org|xyz|wiki|top|site|info|edu|gov)(/|\b)"""),
                Regex("""(?i)reader mode|无痕模式|网页版|add blockers?"""),
            ),
        ),

        Profile(
            "chat_generic", "聊天工具",
            header = listOf(
                Regex("""条新消息|置顶消息"""),
                Regex("""群聊|群消息|聊天信息"""),
            ),
            anywhere = listOf(
                Regex("""撤回了一条消息|对方正在输入|发送失败"""),
                Regex("""刚刚|\d+\s*分钟前|\d+\s*小时前|昨天\s*\d{1,2}:\d{2}"""),
            ),
            body = listOf(
                Regex("""(?m)^\s*\d{1,2}:\d{2}\s*$"""),
                Regex("""选择|复制|转发|删除|撤回"""),
            ),
        ),

        Profile(
            "game", "游戏应用",
            header = listOf(
                Regex("""(?i)\bFPS\b"""),
                Regex("""战绩|段位|英雄|皮肤|MVP"""),
            ),
            anywhere = listOf(
                Regex("""(?i)\bKA?D(A)?\b|\d+\s*杀|\d+\s*助攻"""),
                Regex("""胜利|失败|平局|对局|赛季"""),
            ),
        ),

        // ── 说明：不内置任何具体开发者项目名 ──
        //
        // 之前这里硬编码了 TGAutoSign / WPAForge 两个个人项目的识别规则。
        // 这违反产品原则：一个通用工具不能把某个开发者的私有项目
        // 当成产品级分类，那台手机上跑得好，别人装上去就是纯噪音。
        //
        // 正确做法：
        //   1. 内置规则只放**通用应用类别**（聊天/浏览器/系统设置/游戏/办公）
        //   2. 具体小众应用交给用户在"来源纠正"里手动指定
        //   3. 用户确认过的来源优先级最高，且不被自动任务覆盖
        //
        // 这样换任何一台手机都不会出现"来源不明里混着一堆陌生项目名"。

        // ── 通用技术工具界面 ──
        // 不点名任何具体应用，只认"这是某种开发者工具/终端类界面"。
        // 依据是界面骨架：日志流、命令行、参数配置、状态面板。
        Profile(
            "dev_tool", "开发工具",
            header = listOf(
                Regex("""(?i)\b(log|logs|debug|terminal|console|shell)\b"""),
                Regex("""(?i)\b(config|settings|parameters|options)\b"""),
            ),
            anywhere = listOf(
                Regex("""(?i)\b(signed|unsigned|targets?|accounts?|interval|retry)\b"""),
                Regex("""(?i)tail\s+-f|--version|--v\b"""),
            ),
            strongAnywhere = listOf(
                // 终端提示符：几乎只有开发者工具界面会出现
                Regex("""(?m)^\s*[\$#]\s+\S+"""),
                Regex("""(?i)adb\s+(shell|devices|install)"""),
                Regex("""(?i)\bstack\s*trace|exception|logcat\b"""),
                Regex("""(?i)\bapi\s*(key|token)\b"""),
            ),
        ),

        Profile(
            "office", "办公文档",
            header = listOf(
                Regex("""(?i)wps|office|word|excel|ppt|飞书|腾讯文档|notion"""),
            ),
            anywhere = listOf(
                Regex("""会议|议程|汇报|周报|月报|需求评审|排期|OKR|复盘"""),
            ),
        ),
    )

    /**
     * 识别来源。
     *
     * @param text OCR 全文
     * @return 命中结果；null 表示无法判断
     */
    fun detect(text: String): Hit? {
        if (text.isBlank()) return null

        val total = text.length
        // 头部 = 标题栏区域（前 25%，最少 60 字）
        val headerEnd = maxOf(60, (total * 0.25f).toInt()).coerceAtMost(total)
        val header = text.substring(0, headerEnd)
        // 尾部 = 底部导航区域（后 20%）
        val tailStart = maxOf(0, (total * 0.8f).toInt())
        val tail = text.substring(tailStart)
        val body = if (total > headerEnd) text.substring(headerEnd) else ""

        var best: Hit? = null

        for (p in profiles) {
            var score = 0f
            val why = mutableListOf<String>()

            // 头部命中：权重最高（标题栏就是品牌标识）
            val headerHits = p.header.count { it.containsMatchIn(header) }
            if (headerHits > 0) {
                score += 0.55f * headerHits.coerceAtMost(2)
                why += "标题栏命中 ${headerHits} 项「${p.name}」特征"
            }

            // 全文命中
            val anyHits = p.anywhere.count { it.containsMatchIn(text) }
            if (anyHits > 0) {
                score += 0.30f * anyHits.coerceAtMost(2)
                why += "正文命中 ${anyHits} 项特征"
            }

            // 全文强证据（位置无关，单条即可定案）
            val strongHits = p.strongAnywhere.count { it.containsMatchIn(text) }
            if (strongHits > 0) {
                score += 0.55f * strongHits.coerceAtMost(2)
                why += "强特征命中 $strongHits 项"
            }

            // 尾部命中（导航栏）
            val tailHits = p.body.count { it.containsMatchIn(tail) }
            if (tailHits > 0) {
                score += 0.15f * tailHits.coerceAtMost(2)
                why += "底部导航命中 ${tailHits} 项"
            }

            // 正文命中（弱）
            val bodyHits = p.body.count { it.containsMatchIn(body) }
            if (bodyHits > 0) {
                score += 0.08f * bodyHits.coerceAtMost(3)
            }

            score = score.coerceAtMost(0.95f)
            if (score < 0.50f) continue

            if (best == null || score > best!!.confidence) {
                best = Hit(p.key, p.name, score, why)
            }
        }
        return best
    }

    fun level(hit: Hit?): Level = when {
        hit == null -> Level.UNKNOWN
        hit.confidence >= 0.80f -> Level.HIGH
        hit.confidence >= 0.60f -> Level.MEDIUM
        else -> Level.LOW
    }
}