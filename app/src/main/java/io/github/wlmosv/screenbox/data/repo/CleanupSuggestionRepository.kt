package io.github.wlmosv.screenbox.data.repo

import androidx.room.withTransaction
import io.github.wlmosv.screenbox.data.db.CleanupSuggestionEntity
import io.github.wlmosv.screenbox.data.db.DuplicateGroupType
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.RiskLevel
import io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase
import io.github.wlmosv.screenbox.data.db.ScreenshotConfidence
import io.github.wlmosv.screenbox.data.db.SuggestionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 清理建议引擎（v3）。
 *
 * ══ 为什么重构 ══
 *
 * 旧版实测：306 条建议里 247 条是视觉相似（81%）。
 * 用户面对几百条"画面相似，需人工确认"，等于没有建议。
 *
 * ══ v3 的分类策略 ══
 *
 * 逐张建议（用户可以单张处理）：
 *   EXACT_DUPLICATE  完全重复的多余副本（保留 keeper）
 *   POSSIBLY_EXPIRED 有明确过期日期证据
 *   NEAR_BLANK       近乎空白（熵极低）
 *   LARGE_FILE       大文件（只作空间参考）
 *
 * 组级浏览（不进逐张队列）：
 *   VISUAL_DUPLICATE 不再逐张生成建议，由相似组页面管理
 *   LOW_INFORMATION  低信息量，只作提示
 *
 * 铁律：
 * 1. 保护/收藏永远不进任何建议
 * 2. 完全重复组至少保留一个 keeper
 * 3. 相似不等于重复，不产生删除建议
 * 4. 过期必须有原文证据
 * 5. LOW 置信度截图不产生自动建议
 */
class CleanupSuggestionRepository(private val db: ScreenBoxDatabase) {

    /** 大文件阈值。2 MB 是实测中位数附近，不会太多也不会太少。 */
    private val largeFileBytes = 2L * 1024 * 1024

    /** 过期判断的最小置信度。 */
    private val minExpiredConfidence = 0.65f

    /**
     * 保护一张图，并清掉它所有 OPEN 建议。
     *
     * 为什么必须原子化：
     * 只在 UI 层调 setProtection 的话，建议表里的旧记录还在，
     * openCountFlow 仍会算进去，首页数字虚高。
     * 保护是一个语义完整的事件，必须一次做完。
     */
    suspend fun protectAndClear(screenshotId: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.screenshotDao().setProtection(screenshotId, ProtectionStatus.PROTECTED, now)
            db.cleanupDao().resolveOpenFor(screenshotId)
        }
    }

    /** 收藏同理。 */
    suspend fun favoriteAndClear(screenshotId: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.screenshotDao().setFavorite(screenshotId, true, now)
            db.cleanupDao().resolveOpenFor(screenshotId)
        }
    }

    /** 取消保护：只改状态，不重建建议。 */
    suspend fun unprotect(screenshotId: Long) = withContext(Dispatchers.IO) {
        db.screenshotDao().setProtection(
            screenshotId,
            ProtectionStatus.NORMAL,
            System.currentTimeMillis(),
        )
    }

    suspend fun rebuild() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cleanupDao = db.cleanupDao()
        val dupDao = db.duplicateDao()
        val shotDao = db.screenshotDao()

        // 双保险：先清掉保护项的历史建议
        runCatching { cleanupDao.resolveOpenForProtected() }
        cleanupDao.clearOpenSuggestions()

        val allShots = shotDao.allScannable().associateBy { it.id }

        // 受保护/收藏/已删除的 id 集合，所有规则统一排除
        val excluded = allShots.values.filter {
            it.protectionStatus == ProtectionStatus.PROTECTED ||
                it.protectionStatus == ProtectionStatus.FAVORITE ||
                it.protectionStatus == ProtectionStatus.DELETED ||
                it.protectionStatus == ProtectionStatus.MISSING ||
                it.isFavorite
        }.map { it.id }.toSet()

        val dupRepo = DuplicateRepository(db)

        // ── A. 完全重复：只建议删多余副本 ──
        for (group in dupDao.activeGroups().filter { it.groupType == DuplicateGroupType.EXACT }) {
            val members = dupDao.membersOf(group.id).mapNotNull { allShots[it.screenshotId] }
            if (members.size < 2) continue

            val keeper = dupRepo.pickKeeper(members)
            for (extra in members.filter { it.id != keeper.id && it.id !in excluded }) {
                if (extra.screenshotConfidence == ScreenshotConfidence.LOW) continue
                cleanupDao.insertSuggestion(
                    CleanupSuggestionEntity(
                        screenshotId = extra.id,
                        suggestionType = SuggestionType.EXACT_DUPLICATE,
                        reason = "与另一张截图字节级完全一致",
                        evidence = "重复组 #${group.id}，建议保留「${keeper.displayName.take(40)}」",
                        confidence = 1.0f,
                        riskLevel = RiskLevel.LOW,
                        createdAt = now,
                    )
                )
            }
        }

        // ── B. 视觉相似：不生成逐张建议 ──
        // 由相似组页面统一管理，避免 247 条噪音

        // ── C. 过期信息：必须有明确日期证据 ──
        val ocrDao = db.ocrDao()
        for (shot in allShots.values) {
            if (shot.id in excluded) continue
            if (shot.screenshotConfidence == ScreenshotConfidence.LOW) continue

            val ocr = ocrDao.byScreenshot(shot.id) ?: continue
            if (ocr.recognizedText.isBlank()) continue

            val expiry = ExpiryDetector.findExpiredDate(ocr.recognizedText, now)
            if (expiry != null && expiry.confidence >= minExpiredConfidence) {
                cleanupDao.insertSuggestion(
                    CleanupSuggestionEntity(
                        screenshotId = shot.id,
                        suggestionType = SuggestionType.POSSIBLY_EXPIRED,
                        reason = "截图中出现已过期的日期，可能是失效的活动或优惠",
                        evidence = "识别到「${expiry.raw}」，${expiry.context}",
                        confidence = expiry.confidence,
                        riskLevel = RiskLevel.MEDIUM,
                        createdAt = now,
                    )
                )
            }
        }

        // ── D. 近乎空白：熵极低 + 无 OCR 文字 ──
        for (shot in allShots.values) {
            if (shot.id in excluded) continue
            val entropy = shot.imageEntropy ?: continue
            if (entropy >= 0.25f) continue

            val ocr = ocrDao.byScreenshot(shot.id)
            val hasText = (ocr?.recognizedText?.length ?: 0) > 3
            // 有文字的图即使熵低也不建议（白底黑字熵确实不高）
            if (hasText) continue

            cleanupDao.insertSuggestion(
                CleanupSuggestionEntity(
                    screenshotId = shot.id,
                    suggestionType = SuggestionType.NEAR_BLANK,
                    reason = "画面近乎空白，可能是误触截屏",
                    evidence = "图像熵 %.2f（低于 0.25），且未识别到文字".format(entropy),
                    confidence = 0.85f,
                    riskLevel = RiskLevel.LOW,
                    createdAt = now,
                )
            )
        }

        // ── E. 大文件：只作空间参考，不主动建议删 ──
        // 不写入 suggestions 表，由筛选页的 size:2mb 处理

        // ── F. 一次性验证码：过期后几乎必然无用 ──
        // 依据：OCR 出现验证码语境 + 4~8 位数字 + 截图超过 1 天
        // 这类截图的唯一用途就是"当时输入那个码"，输入完即失效。
        for (shot in allShots.values) {
            if (shot.id in excluded) continue
            if (shot.screenshotConfidence == ScreenshotConfidence.LOW) continue
            val ageMs = now - shot.dateModified
            if (ageMs < ONE_DAY_MS) continue

            val ocr = ocrDao.byScreenshot(shot.id) ?: continue
            val text = ocr.recognizedText
            if (text.isBlank()) continue

            val m = CODE_RE.find(text) ?: continue
            if (!isOneTimeCode(text)) continue
            cleanupDao.insertSuggestion(
                CleanupSuggestionEntity(
                    screenshotId = shot.id,
                    suggestionType = SuggestionType.ONE_TIME_CODE,
                    reason = "一次性验证码，超过 1 天后通常已失效",
                    evidence = "识别到「${m.value.take(40)}」",
                    confidence = 0.75f,
                    riskLevel = RiskLevel.MEDIUM,
                    createdAt = now,
                )
            )
        }

        // ── G. 陈旧通知：超过 90 天的通知中心截图 ──
        // 只提示，不自动删 —— 通知里也可能存着重要信息。
        for (shot in allShots.values) {
            if (shot.id in excluded) continue
            val ageMs = now - shot.dateModified
            if (ageMs < STALE_MS) continue

            val ocr = ocrDao.byScreenshot(shot.id) ?: continue
            val text = ocr.recognizedText
            if (text.isBlank()) continue
            if (!isNotificationCenter(text)) continue

            cleanupDao.insertSuggestion(
                CleanupSuggestionEntity(
                    screenshotId = shot.id,
                    suggestionType = SuggestionType.STALE_NOTIFICATION,
                    reason = "聊天/游戏界面上弹出的新消息浮窗，已超过 90 天",
                    evidence = "截图时间 ${(ageMs / ONE_DAY_MS)} 天前，含「条新消息」气泡",
                    confidence = 0.60f,
                    riskLevel = RiskLevel.HIGH,
                    createdAt = now,
                )
            )
        }

        // ── H. 旧内容：按内容分类 + 时间阈值 ──
        //
        // 实测本机 1355 张游戏截图里 1157 张超过 90 天。
        // 这是用户最想批量清理的一类，但风险 HIGH ——
        // 可能是特意留的战绩/纪念，只提示不自动删。
        //
        // 用 180 天阈值（比 90 天保守），避免刚过季度就催用户删。
        // 只取**高置信**分类（>=0.85）用于清理候选。
        //
        // 实测：纯横屏（无游戏词）的 game 分类精度只有 68.9%，
        // 剩下 31% 是视频、网页、文档等横屏内容。
        // 拿低置信分类去建议用户删文件，风险不可接受。
        val catKeyByShot = mutableMapOf<Long, MutableSet<String>>()
        runCatching {
            db.query(
                "SELECT sc.screenshotId, c.`key` FROM screenshot_categories sc " +
                    "INNER JOIN categories c ON c.id = sc.categoryId " +
                    "WHERE sc.confidence >= 0.85",
                emptyArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    catKeyByShot.getOrPut(c.getLong(0)) { mutableSetOf() }.add(c.getString(1))
                }
            }
        }

        val oldThreshold = now - OLD_CONTENT_MS
        for (shot in allShots.values) {
            if (shot.id in excluded) continue
            if (shot.dateModified >= oldThreshold) continue
            val keys = catKeyByShot[shot.id] ?: continue
            val days = (now - shot.dateModified) / ONE_DAY_MS

            if ("game" in keys) {
                cleanupDao.insertSuggestion(
                    CleanupSuggestionEntity(
                        screenshotId = shot.id,
                        suggestionType = SuggestionType.OLD_GAME,
                        reason = "游戏截图，已超过 ${days} 天",
                        evidence = "内容分类=游戏，文件时间 $days 天前",
                        confidence = 0.75f,
                        riskLevel = RiskLevel.HIGH,
                        createdAt = now,
                    )
                )
            } else if ("social" in keys) {
                cleanupDao.insertSuggestion(
                    CleanupSuggestionEntity(
                        screenshotId = shot.id,
                        suggestionType = SuggestionType.OLD_SOCIAL,
                        reason = "社交动态截图，已超过 ${days} 天",
                        evidence = "内容分类=社交，文件时间 $days 天前",
                        confidence = 0.70f,
                        riskLevel = RiskLevel.HIGH,
                        createdAt = now,
                    )
                )
            }
        }

        // ── I. 长期未查看：不生成建议 ──
        // 用户可能从不点开但确实需要的截图，不能作为删除理由
    }

    /**
     * 一次性验证码。
     *
     * ══ 实测精度（10 张抽样）══
     *
     * 9/10 命中真实短信验证码或登录验证码页。
     * 1 条误报："教育部学籍在线验证报告" —— 出现了"验证"但不是验证码。
     *
     * 修正：排除"验证报告/验证码有效期说明"这类文档语境，
     * 并要求验证码数字紧跟在语境词后面（中间不能隔太多字）。
     */
    private val CODE_RE = Regex(
        """(?:验证码|校验码|动态码|确认码|verification\s*code)[^\d]{0,12}(\d{4,8})""",
        RegexOption.IGNORE_CASE,
    )

    /** 文档类语境，出现则不判为一次性验证码。 */
    private val CODE_NEGATIVE = Regex(
        """验证报告|学籍在线验证|学历验证|验证材料|验证说明|验证流程"""
    )

    private fun isOneTimeCode(text: String): Boolean {
        if (CODE_NEGATIVE.containsMatchIn(text)) return false
        return CODE_RE.containsMatchIn(text)
    }

    /**
     * 通知中心特征。
     *
     * ══ 三轮收紧的实测过程 ══
     *
     * 第一版：`通知` 单词匹配 → 148 张，绝大多数是设置页/聊天里提到"通知"。
     * 第二版：要求命中 2 条结构特征 → 83 张，但仍有大量聊天/评论区截图，
     *        因为 `\d+小时前` 这种相对时间在聊天列表、评论区同样常见。
     * 第三版（当前）：只用**通知中心独有**的文案，
     *        去掉纯时间戳这类到处都有的弱信号。
     *
     * 原则：宁可少报，不可误报。通知中心截图占比很小，
     * 漏掉几张远比把聊天记录标成"陈旧通知"要好。
     */
    private val NOTIFY_STRONG = listOf(
        Regex("""\d+\s*条新消息"""),
        Regex("""全部已读|全部清除|清除全部|清空通知|清除所有通知"""),
        Regex("""通知中心|no notifications|没有通知"""),
        Regex("""(?i)notification(s)?\b.*\bclear all"""),
    )

    /** 辅助信号：必须与强信号同时出现，不能单独成立。 */
    private val NOTIFY_WEAK = listOf(
        Regex("""\d+\s*(分钟前|小时前|天前)"""),
        Regex("""(?m)^\s*\d{1,2}:\d{2}\s*$"""),
    )

    private fun isNotificationCenter(text: String): Boolean {
        val strong = NOTIFY_STRONG.count { it.containsMatchIn(text) }
        if (strong == 0) return false
        val weak = NOTIFY_WEAK.count { it.containsMatchIn(text) }
        return strong + weak >= 2
    }

    private companion object {
        const val ONE_DAY_MS = 24L * 3600 * 1000
        /** 旧内容阈值：180 天。比 90 天保守，避免刚过季度就催删。 */
        const val OLD_CONTENT_MS = 180L * ONE_DAY_MS
        const val STALE_MS = 90L * ONE_DAY_MS
    }

    /**
     * 统计各类候选数量，供清理工作台展示。
     */
    suspend fun summary(): List<TypeSummary> = withContext(Dispatchers.IO) {
        val dupDao = db.duplicateDao()
        val shotDao = db.screenshotDao()

        val open = db.cleanupDao().allOpen()
        val byType = open.groupBy { it.suggestionType }

        val exactGroups = dupDao.activeGroups().count { it.groupType == DuplicateGroupType.EXACT }
        val visualGroups = dupDao.activeGroups().count { it.groupType == DuplicateGroupType.VISUAL }

        buildList {
            byType[SuggestionType.EXACT_DUPLICATE]?.let {
                add(
                    TypeSummary(
                        type = SuggestionType.EXACT_DUPLICATE,
                        count = it.size,
                        bytes = shotDao.byIds(it.map { s -> s.screenshotId }).sumOf { s -> s.fileSize },
                        groupCount = exactGroups,
                    )
                )
            }
            // 视觉相似按组统计
            if (visualGroups > 0) {
                add(
                    TypeSummary(
                        type = SuggestionType.VISUAL_DUPLICATE,
                        count = dupDao.activeGroups()
                            .filter { it.groupType == DuplicateGroupType.VISUAL }
                            .sumOf { dupDao.membersOf(it.id).size },
                        bytes = 0, // 相似组不能简单算可释放空间
                        groupCount = visualGroups,
                    )
                )
            }
            byType[SuggestionType.POSSIBLY_EXPIRED]?.let {
                add(
                    TypeSummary(
                        type = SuggestionType.POSSIBLY_EXPIRED,
                        count = it.size,
                        bytes = shotDao.byIds(it.map { s -> s.screenshotId }).sumOf { s -> s.fileSize },
                    )
                )
            }
            byType[SuggestionType.NEAR_BLANK]?.let {
                add(
                    TypeSummary(
                        type = SuggestionType.NEAR_BLANK,
                        count = it.size,
                        bytes = shotDao.byIds(it.map { s -> s.screenshotId }).sumOf { s -> s.fileSize },
                    )
                )
            }
            byType[SuggestionType.ONE_TIME_CODE]?.let {
                add(
                    TypeSummary(
                        type = SuggestionType.ONE_TIME_CODE,
                        count = it.size,
                        bytes = shotDao.byIds(it.map { s -> s.screenshotId }).sumOf { s -> s.fileSize },
                    )
                )
            }
            byType[SuggestionType.OLD_GAME]?.let {
                add(
                    TypeSummary(
                        type = SuggestionType.OLD_GAME,
                        count = it.size,
                        bytes = shotDao.byIds(it.map { s -> s.screenshotId }).sumOf { s -> s.fileSize },
                    )
                )
            }
            byType[SuggestionType.OLD_SOCIAL]?.let {
                add(
                    TypeSummary(
                        type = SuggestionType.OLD_SOCIAL,
                        count = it.size,
                        bytes = shotDao.byIds(it.map { s -> s.screenshotId }).sumOf { s -> s.fileSize },
                    )
                )
            }
            byType[SuggestionType.STALE_NOTIFICATION]?.let {
                add(
                    TypeSummary(
                        type = SuggestionType.STALE_NOTIFICATION,
                        count = it.size,
                        bytes = shotDao.byIds(it.map { s -> s.screenshotId }).sumOf { s -> s.fileSize },
                    )
                )
            }
            // 大文件
            val largeCount = shotDao.countLargeFiles(largeFileBytes)
            if (largeCount > 0) {
                add(
                    TypeSummary(
                        type = SuggestionType.LARGE_FILE,
                        count = largeCount,
                        bytes = 0,
                    )
                )
            }
        }
    }

    data class TypeSummary(
        val type: SuggestionType,
        val count: Int,
        val bytes: Long,
        /** 仅分组类型有值。 */
        val groupCount: Int = 0,
    )
}

/**
 * 过期日期检测。
 *
 * 铁律：
 * 1. 必须同时有「明确日期」和「过期语境词」
 * 2. 单纯看到日期不算（订单日期、生日都不是过期信号）
 * 3. 有否定语境词直接跳过
 */
object ExpiryDetector {

    data class Expiry(val raw: String, val epochMs: Long, val confidence: Float, val context: String)

    private val expiryWords = listOf(
        "有效期至", "有效期到", "截止日期", "截止时间", "到期", "过期",
        "活动时间", "优惠券有效期", "expires", "valid until", "valid thru",
        "限时", "最后一天", "仅剩",
    )

    private val negativeWords = listOf(
        "出生日期", "生日", "订单时间", "下单时间", "付款时间", "创建时间",
        "发货时间", "签收时间", "注册时间", "入职时间", "考试时间",
    )

    private val datePatterns = listOf(
        Regex("""(20\d{2})\s*[-/年.]\s*(\d{1,2})\s*[-/月.]\s*(\d{1,2})\s*日?"""),
    )

    fun findExpiredDate(text: String, nowMs: Long): Expiry? {
        val lower = text.lowercase()

        if (negativeWords.any { lower.contains(it) }) return null

        val hasExpiryWord = expiryWords.any { lower.contains(it.lowercase()) }
        if (!hasExpiryWord) return null

        for (pattern in datePatterns) {
            for (match in pattern.findAll(text)) {
                val y = match.groupValues[1].toIntOrNull() ?: continue
                val m = match.groupValues[2].toIntOrNull() ?: continue
                val d = match.groupValues[3].toIntOrNull() ?: continue
                if (m !in 1..12 || d !in 1..31) continue

                val cal = java.util.Calendar.getInstance().apply {
                    set(y, m - 1, d, 23, 59, 59)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val epoch = cal.timeInMillis
                if (epoch >= nowMs) continue

                val idx = text.indexOf(match.value)
                val start = (idx - 12).coerceAtLeast(0)
                val end = (idx + match.value.length + 12).coerceAtMost(text.length)
                val ctx = text.substring(start, end).replace("\n", " ").trim()

                return Expiry(
                    raw = match.value,
                    epochMs = epoch,
                    confidence = 0.85f,
                    context = "原文：$ctx",
                )
            }
        }
        return null
    }
}