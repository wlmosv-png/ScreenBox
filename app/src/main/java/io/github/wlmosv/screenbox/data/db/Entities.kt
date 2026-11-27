package io.github.wlmosv.screenbox.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 截图主表。
 *
 * 关键设计：
 * 1. mediaStoreId 不唯一 —— 同一张图在 MediaStore 重建后 id 会变，
 *    真正稳定的是 contentUri + dateModified + fileSize 的组合指纹。
 * 2. contentUri 只是缓存，每次访问前必须校验可用性，不许当永久地址。
 * 3. sha256 / phash 为空表示尚未计算，不是"没有重复"。
 * 4. protectionStatus 优先级最高，任何自动任务不得覆盖。
 */

enum class OcrStatus {
    NOT_STARTED,
    QUEUED,
    PROCESSING,
    SUCCESS,
    PARTIAL,
    FAILED,
    UNSUPPORTED,
}

enum class ClassifyStatus {
    NOT_STARTED,
    QUEUED,
    DONE,
    FAILED,
}

enum class ProtectionStatus {
    /** 普通状态，可进入清理建议。 */
    NORMAL,

    /** 用户收藏。 */
    FAVORITE,

    /** 用户显式保护，任何自动建议都不得触碰。 */
    PROTECTED,

    /** 系统认为需要复核（例如识别到敏感信息），但未被用户确认。 */
    REVIEW_REQUIRED,

    /** 已加入待清理清单，等待用户最终确认。 */
    PENDING_DELETE,

    /** 已从 MediaStore 删除。 */
    DELETED,

    /** MediaStore 里找不到该记录了。 */
    MISSING,
}

enum class ScreenshotConfidence {
    /** 系统截图目录 + 截图命名，双重证据。 */
    HIGH,

    /** 仅路径或仅命名命中。 */
    MEDIUM,

    /** 只靠尺寸/比例推断，不得进入自动清理建议。 */
    LOW,
}

@Entity(
    tableName = "screenshots",
    indices = [
        Index(value = ["contentUri"], unique = true),
        Index(value = ["sha256"]),
        Index(value = ["perceptualHash"]),
        Index(value = ["dateModified"]),
        Index(value = ["protectionStatus"]),
        Index(value = ["ocrStatus"]),
        Index(value = ["mediaStoreId"]),
    ],
)
data class ScreenshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    @ColumnInfo(name = "mediaStoreId") val mediaStoreId: Long,
    @ColumnInfo(name = "contentUri") val contentUri: String,
    @ColumnInfo(name = "displayName") val displayName: String,
    @ColumnInfo(name = "relativePath") val relativePath: String,
    @ColumnInfo(name = "mimeType") val mimeType: String,
    @ColumnInfo(name = "fileSize") val fileSize: Long,
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,

    @ColumnInfo(name = "dateAdded") val dateAdded: Long,
    @ColumnInfo(name = "dateModified") val dateModified: Long,

    /** 文件内容摘要，仅用于完全重复判定。null = 未计算。 */
    @ColumnInfo(name = "sha256") val sha256: String? = null,

    /** 感知哈希（64bit 十六进制），仅用于视觉近似。null = 未计算。 */
    @ColumnInfo(name = "perceptualHash") val perceptualHash: String? = null,

    @ColumnInfo(name = "screenshotConfidence") val screenshotConfidence: ScreenshotConfidence,

    @ColumnInfo(name = "ocrStatus") val ocrStatus: OcrStatus = OcrStatus.NOT_STARTED,
    @ColumnInfo(name = "classifyStatus") val classifyStatus: ClassifyStatus = ClassifyStatus.NOT_STARTED,
    @ColumnInfo(name = "protectionStatus") val protectionStatus: ProtectionStatus = ProtectionStatus.NORMAL,

    /**
     * OCR 队列优先级。数值越小越先处理。
     * 0=用户正在查看  1=最近新增  2=收藏  3=保护  4=近期历史  9=其他历史
     */
    @ColumnInfo(name = "ocrPriority") val ocrPriority: Int = 9,

    /** OCR 尝试次数，用于退避与"永久失败"判定。 */
    @ColumnInfo(name = "ocrAttempts") val ocrAttempts: Int = 0,

    /**
     * 图像熵（0..1，越高越复杂）。
     *
     * 用途：区分"近乎空白"和"内容丰富但无文字"。
     * 纯色/渐变的误触截屏熵很低；照片、游戏画面熵高。
     * null 表示尚未计算。
     */
    @ColumnInfo(name = "imageEntropy") val imageEntropy: Float? = null,

    /** 主色调数量（量化后）。用于辅助判断是否单色页面。 */
    @ColumnInfo(name = "colorVariance") val colorVariance: Float? = null,

    @ColumnInfo(name = "isFavorite") val isFavorite: Boolean = false,

    /** 用户手动指定的分类优先于自动分类。 */
    @ColumnInfo(name = "userCategoryId") val userCategoryId: Long? = null,

    /** 最近一次被用户打开查看的时间，用于"长期未查看"排序（不作为删除依据）。 */
    @ColumnInfo(name = "lastViewedAt") val lastViewedAt: Long? = null,

    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long,
)

@Entity(
    tableName = "ocr",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshotId"], unique = true)],
)
data class OcrEntity(
    @PrimaryKey @ColumnInfo(name = "screenshotId") val screenshotId: Long,
    @ColumnInfo(name = "recognizedText") val recognizedText: String,
    @ColumnInfo(name = "language") val language: String,
    @ColumnInfo(name = "recognitionStatus") val recognitionStatus: OcrStatus,
    @ColumnInfo(name = "recognizedAt") val recognizedAt: Long,
    @ColumnInfo(name = "engineVersion") val engineVersion: String,
    @ColumnInfo(name = "errorMessage") val errorMessage: String? = null,
    /** 文本块坐标（JSON），长截图定位用；不参与搜索。 */
    @ColumnInfo(name = "blockBounds") val blockBounds: String? = null,
    /**
     * 行级文字坐标（JSON），R8 新增。
     *
     * 块级坐标太粗：一个 block 常是整段文字、宽度横跨全屏，
     * 聊天气泡左右交错、商品多列网格这些结构特征会被抹平。
     * 行级坐标才能支撑真实的布局分析。
     */
    @ColumnInfo(name = "lineBounds") val lineBounds: String? = null,
)

/**
 * FTS4 全文索引表。
 * 中文不分词 —— 用 unigram 兜底 + LIKE 补位，见 SearchRepository。
 */
@Entity(tableName = "ocr_fts")
data class OcrFtsEntity(
    @PrimaryKey @ColumnInfo(name = "screenshotId") val screenshotId: Long,
    @ColumnInfo(name = "text") val text: String,
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["key"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 稳定标识，如 "chat" / "order" / "payment"。多语言文案另存。 */
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "displayName") val displayName: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "isSystem") val isSystem: Boolean = true,
    @ColumnInfo(name = "sortOrder") val sortOrder: Int = 0,
)

@Entity(
    tableName = "screenshot_categories",
    primaryKeys = ["screenshotId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["categoryId"]), Index(value = ["screenshotId"])],
)
data class ScreenshotCategoryEntity(
    @ColumnInfo(name = "screenshotId") val screenshotId: Long,
    @ColumnInfo(name = "categoryId") val categoryId: Long,
    @ColumnInfo(name = "confidence") val confidence: Float,
    /** "auto" | "user" */
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "isUserConfirmed") val isUserConfirmed: Boolean = false,
)

/** 标签分组。特征标签是系统根据图片属性自动计算的，用户标签是用户手动加的。 */
enum class TagGroup(val label: String) {
    /** 图片自身特征：长截图、二维码、空白、重复等。由分析引擎自动计算。 */
    FEATURE("图片特征"),

    /** 用户自定义标签。用户手动添加，自动任务不得覆盖。 */
    CUSTOM("用户标签"),
}

/**
 * 图片自身特征标签。
 *
 * ══ 设计原则 ══
 *
 * 这些是**独立维度**，不塞进内容用途分类。
 * 一张「微信聊天记录长截图」同时拥有：
 *   内容用途 = 聊天记录
 *   来源应用 = 微信
 *   图片特征 = 长截图
 *   OCR 状态 = 已识别
 *
 * 特征标签必须能组合筛选，且每个都有明确的判断依据。
 * 低信息量 / 近乎空白 **不等于无用**，只作提示，不构成删除建议。
 */
enum class FeatureTag(val key: String, val displayName: String) {
    /** 长截图：高度超过宽度 2 倍以上。 */
    LONG_SCREENSHOT("long_screenshot", "长截图"),

    /** 二维码：识别到二维码相关文本，或视觉特征命中。 */
    QRCODE("qrcode", "二维码"),

    /** 大尺寸：像素总量超过阈值。 */
    LARGE_SIZE("large_size", "大尺寸"),

    /** 大文件：文件体积超过阈值。 */
    LARGE_FILE("large_file", "大文件"),

    /** 近乎空白：熵极低且颜色方差极低，通常是误触截屏。 */
    NEAR_BLANK("near_blank", "近乎空白"),

    /** 低信息量：熵偏低且无 OCR 文字，可能是过渡页。 */
    LOW_INFO("low_info", "低信息量"),

    /** 完全重复：属于字节级完全一致的重复组。 */
    EXACT_DUPLICATE("exact_duplicate", "完全重复"),

    /** 视觉相似：属于视觉近似重复组。 */
    VISUAL_SIMILAR("visual_similar", "视觉相似"),

    /** OCR 未识别：从未成功识别出文字。 */
    OCR_UNRECOGNIZED("ocr_unrecognized", "OCR未识别"),

    /** OCR 低置信度：识别结果可信度不足，可能需要重新识别。 */
    OCR_LOW_CONFIDENCE("ocr_low_confidence", "OCR低置信度"),

    /** 小字体：截图中存在大量小字，长截图分段识别时需注意。 */
    SMALL_TEXT("small_text", "小字体"),

    /** 横屏图片：内容本身是横向的（游戏、视频、横版网页）。不等于被旋转。 */
    LANDSCAPE("landscape", "横屏"),
}

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "displayName") val displayName: String,
    @ColumnInfo(name = "group") val group: String,
    @ColumnInfo(name = "isSystem") val isSystem: Boolean = true,
    @ColumnInfo(name = "sortOrder") val sortOrder: Int = 0,
)

@Entity(
    tableName = "screenshot_tags",
    primaryKeys = ["screenshotId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tagId"])],
)
data class ScreenshotTagEntity(
    @ColumnInfo(name = "screenshotId") val screenshotId: Long,
    @ColumnInfo(name = "tagId") val tagId: Long,
)

enum class DuplicateGroupType {
    /** 字节完全一致。 */
    EXACT,

    /** 感知哈希近似。 */
    VISUAL,

    /** OCR 文本高度重合（同一页面多次截图）。 */
    CONTENT,
}

@Entity(tableName = "duplicate_groups")
data class DuplicateGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "groupType") val groupType: DuplicateGroupType,
    @ColumnInfo(name = "confidence") val confidence: Float,
    /** 用户手动解散的组不会被重新生成覆盖。 */
    @ColumnInfo(name = "dismissed") val dismissed: Boolean = false,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long,
)

@Entity(
    tableName = "duplicate_members",
    primaryKeys = ["groupId", "screenshotId"],
    foreignKeys = [
        ForeignKey(
            entity = DuplicateGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshotId"])],
)
data class DuplicateMemberEntity(
    @ColumnInfo(name = "groupId") val groupId: Long,
    @ColumnInfo(name = "screenshotId") val screenshotId: Long,
    @ColumnInfo(name = "similarityScore") val similarityScore: Float,
)

/**
 * 清理候选类型。
 *
 * ══ 设计原则 ══
 *
 * 每种类型必须有独立的判断依据和风险等级。
 * 视觉相似不再逐张生成建议（原来 247/306 = 81% 全是它，用户无法处理），
 * 改为组级浏览。
 */
enum class SuggestionType(val label: String) {
    /** 字节完全一致。唯一可以说"完全重复"的。 */
    EXACT_DUPLICATE("完全重复"),

    /** 视觉近似。仅作组级提示，不逐张建议删除。 */
    VISUAL_DUPLICATE("画面相似"),

    /** OCR 中识别到明确的过期日期，且已过期。 */
    POSSIBLY_EXPIRED("疑似过期"),

    /** 长期未查看。只作排序提示，不单独构成建议。 */
    LONG_UNTOUCHED("长期未查看"),

    /** 用户自己加入清单。 */
    USER_PICKED("手动选择"),

    /** 近乎空白：OCR 无文字 + 低熵图像特征。 */
    NEAR_BLANK("近乎空白"),

    /** 大文件：单张超过阈值，供空间释放参考。 */
    LARGE_FILE("大文件"),

    /** 低信息量：无文字且非照片类（误触截屏、纯色过渡页）。 */
    LOW_INFORMATION("低信息量"),

    /**
     * 一次性验证码。
     *
     * 依据：OCR 出现「验证码/校验码/动态码」+ 4~8 位数字，
     * 且截图时间已超过有效期窗口（默认 1 天）。
     * 这类截图过期后几乎必然无用，但必须给用户看原文证据。
     */
    ONE_TIME_CODE("一次性验证码"),

    /**
     * 过时的通知气泡截图。
     *
     * ══ 实测澄清 ══
     *
     * 这一类**不是**通知中心截图（实测 11 条里没有一条是通知中心）。
     * 真实内容是：聊天/游戏画面上弹出的"xx(2条新消息)"浮窗。
     * 这类截图在 90 天后基本失去价值，但名字必须如实。
     *
     * 风险等级 HIGH：可能是用户特意留证的对话上下文。
     */
    STALE_NOTIFICATION("过时通知气泡"),

    /**
     * 旧游戏截图。
     *
     * 依据：内容分类=游戏 且 文件时间超过 180 天。
     * 实测本机 1355 张游戏截图里 1157 张超过 90 天——
     * 这是用户最想批量清理的一类，但必须给时间阈值让用户自己定。
     * 风险 HIGH：可能是特意留的战绩/纪念。
     */
    OLD_GAME("旧游戏截图"),

    /**
     * 旧社交动态截图。
     *
     * 依据：内容分类=社交 且 超过 180 天。
     * 风险 HIGH：可能是留证。
     */
    OLD_SOCIAL("旧社交截图"),
}

enum class RiskLevel { LOW, MEDIUM, HIGH }

enum class SuggestionStatus {
    OPEN,
    IGNORED,
    ACCEPTED,
    RESOLVED,
}

@Entity(
    tableName = "cleanup_suggestions",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["screenshotId"]),
        Index(value = ["status"]),
        Index(value = ["suggestionType", "screenshotId"], unique = true),
    ],
)
data class CleanupSuggestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "screenshotId") val screenshotId: Long,
    @ColumnInfo(name = "suggestionType") val suggestionType: SuggestionType,
    /** 面向用户的可读理由，必须能说清"为什么"。 */
    @ColumnInfo(name = "reason") val reason: String,
    /** 支撑证据，例如重复组 id、过期日期原文。 */
    @ColumnInfo(name = "evidence") val evidence: String,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "riskLevel") val riskLevel: RiskLevel,
    @ColumnInfo(name = "status") val status: SuggestionStatus = SuggestionStatus.OPEN,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
)

enum class OperationType { DELETE, RESTORE }

enum class OperationStatus { PENDING, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED }

@Entity(tableName = "cleanup_operations")
data class CleanupOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "operationType") val operationType: OperationType,
    @ColumnInfo(name = "status") val status: OperationStatus,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "completedAt") val completedAt: Long? = null,
    @ColumnInfo(name = "successCount") val successCount: Int = 0,
    @ColumnInfo(name = "failureCount") val failureCount: Int = 0,
    @ColumnInfo(name = "totalCount") val totalCount: Int = 0,
    @ColumnInfo(name = "freedBytes") val freedBytes: Long = 0,
)

@Entity(
    tableName = "cleanup_operation_items",
    primaryKeys = ["operationId", "screenshotId"],
    foreignKeys = [
        ForeignKey(
            entity = CleanupOperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshotId"])],
)
data class CleanupOperationItemEntity(
    @ColumnInfo(name = "operationId") val operationId: Long,
    @ColumnInfo(name = "screenshotId") val screenshotId: Long,
    @ColumnInfo(name = "displayName") val displayName: String,
    @ColumnInfo(name = "fileSize") val fileSize: Long,
    /** "success" | "failed" | "skipped_missing" | "skipped_protected" | "user_cancelled" */
    @ColumnInfo(name = "result") val result: String? = null,
    @ColumnInfo(name = "errorMessage") val errorMessage: String? = null,
)

/**
 * 来源应用。
 *
 * 为什么不放在 categories 表里：
 * 来源是独立维度（一张"小红书"的"购物订单"截图同时属于两个维度），
 * 且来源有置信度和"来源不明"状态，语义与内容分类不同。
 */
@Entity(
    tableName = "source_apps",
    indices = [Index(value = ["key"], unique = true)],
)
data class SourceAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 稳定标识，如 "wechat" / "xhs" / "unknown"。 */
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "displayName") val displayName: String,
    /** 是否系统预置。 */
    @ColumnInfo(name = "isSystem") val isSystem: Boolean = true,
    @ColumnInfo(name = "sortOrder") val sortOrder: Int = 0,
)

/**
 * 图片与来源应用的关联。
 * 一张图只有一个来源（或来源不明），所以 screenshotId 唯一。
 */
@Entity(
    tableName = "screenshot_sources",
    primaryKeys = ["screenshotId"],
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceAppEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceAppId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshotId"], unique = true), Index(value = ["sourceAppId"])],
)
data class ScreenshotSourceEntity(
    @ColumnInfo(name = "screenshotId") val screenshotId: Long,
    @ColumnInfo(name = "sourceAppId") val sourceAppId: Long,
    /** 0..1 置信度。 */
    @ColumnInfo(name = "confidence") val confidence: Float,
    /** 识别依据，供 UI 展示"为什么判成这个来源"。 */
    @ColumnInfo(name = "evidence") val evidence: String,
    /** 用户手动修正过的，自动任务不得覆盖。 */
    @ColumnInfo(name = "isUserConfirmed") val isUserConfirmed: Boolean = false,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long = 0,
)

/**
 * OCR 结构化实体。
 *
 * 为什么独立成表而不是塞进 ocr 表：
 * 一张图可以有多个同类实体（多个日期、多个金额），一对多。
 *
 * 设计约束：
 * - raw 保留原文，normalized 用于搜索
 * - startPos 是实体在 OCR 全文中的起始位置，详情页可定位
 * - confidence 独立于 OCR 置信度
 */
@Entity(
    tableName = "structured_entities",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["screenshotId"]),
        Index(value = ["kind", "normalized"]),
    ],
)
data class StructuredEntityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "screenshotId") val screenshotId: Long,
    /** DATE / AMOUNT / ORDER_NO / PHONE / URL / EMAIL / TRACKING / TIME */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "raw") val raw: String,
    @ColumnInfo(name = "normalized") val normalized: String,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "startPos") val startPos: Int,
)

/** 扫描状态单例表，id 恒为 1。 */
@Entity(tableName = "scan_state")
data class ScanStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "lastFullScanAt") val lastFullScanAt: Long? = null,
    @ColumnInfo(name = "lastIncrementalScanAt") val lastIncrementalScanAt: Long? = null,
    @ColumnInfo(name = "lastScanDurationMs") val lastScanDurationMs: Long = 0,
    @ColumnInfo(name = "lastScannedCount") val lastScannedCount: Int = 0,
    @ColumnInfo(name = "isScanning") val isScanning: Boolean = false,
    @ColumnInfo(name = "scanPhase") val scanPhase: String? = null,
    @ColumnInfo(name = "scanProgressCurrent") val scanProgressCurrent: Int = 0,
    @ColumnInfo(name = "scanProgressTotal") val scanProgressTotal: Int = 0,
    /** 上次扫描时用的权限快照，权限变化后需要校准。 */
    @ColumnInfo(name = "permissionSnapshot") val permissionSnapshot: String? = null,
)

/**
 * 后台任务状态（OCR / 指纹）。
 *
 * 为什么必须落库：
 * 之前 OCR 进度只存在 OcrProgress 这个内存 StateFlow 里，
 * 加上 Worker 每轮传 maxCount=600，导致界面永远显示 "0 / 600"，
 * 而首页已识别数却在涨 —— 两个数字自相矛盾。
 *
 * 唯一可信来源必须是数据库。界面只读这张表，Worker 只写这张表。
 *
 * 不变量：totalCount >= doneCount + failedCount + skippedCount
 * 剩余 = totalCount - done - failed - skipped
 */
@Entity(tableName = "task_state")
data class TaskStateEntity(
    /** "ocr" | "fingerprint" */
    @PrimaryKey @ColumnInfo(name = "kind") val kind: String,
    /** IDLE / QUEUED / RUNNING / PAUSED / STOPPING / COMPLETED / FAILED */
    @ColumnInfo(name = "status") val status: String = "IDLE",
    /** 本次任务的基线总量（任务开始时的待处理数）。 */
    @ColumnInfo(name = "totalCount") val totalCount: Int = 0,
    @ColumnInfo(name = "doneCount") val doneCount: Int = 0,
    @ColumnInfo(name = "failedCount") val failedCount: Int = 0,
    @ColumnInfo(name = "skippedCount") val skippedCount: Int = 0,
    @ColumnInfo(name = "startedAt") val startedAt: Long? = null,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long = 0,
    /** 任务中途有新截图进来时的"任务集扩容"时间，UI 用它提示分母变了。 */
    @ColumnInfo(name = "setExpandedAt") val setExpandedAt: Long? = null,
    @ColumnInfo(name = "policy") val policy: String = "RECENT_FIRST",
    /** 最近一次错误，便于排查。 */
    @ColumnInfo(name = "lastError") val lastError: String? = null,
) {
    val remaining: Int get() = (totalCount - doneCount - failedCount - skippedCount).coerceAtLeast(0)
}

/**
 * 保存的筛选条件。
 * 用户配置一次"小红书 + 90天前 + 未收藏"，下次一键复现。
 */
@Entity(tableName = "saved_filters")
data class SavedFilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    /** 序列化后的筛选条件（JSON），字段随 UI 演进不必改表。 */
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
)

/** 搜索历史。 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey @ColumnInfo(name = "keyword") val keyword: String,
    @ColumnInfo(name = "hitCount") val hitCount: Int,
    @ColumnInfo(name = "lastUsedAt") val lastUsedAt: Long,
)
