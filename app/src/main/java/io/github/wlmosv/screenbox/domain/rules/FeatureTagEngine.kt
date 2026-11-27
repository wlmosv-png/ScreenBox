package io.github.wlmosv.screenbox.domain.rules

import io.github.wlmosv.screenbox.data.db.FeatureTag

/**
 * 图片特征标签引擎。
 *
 * ══ 为什么需要它 ══
 *
 * 内容分类（categories）回答「这张图是什么」，
 * 来源应用（source_apps）回答「从哪来」，
 * 但用户还会问：**「这张图有什么特点？能不能快速挑出来？」**
 *
 * 长截图、二维码、大文件、近乎空白、完全重复……
 * 这些不是内容用途，是图片自身属性。
 * 把它们塞进内容分类会导致：一张「微信长截图」既属于聊天记录又属于长截图，
 * 分类体系立刻崩塌。
 *
 * 所以特征标签是**独立维度**，可任意组合：
 *   来源=微信 且 特征=长截图 且 时间=最近30天
 *
 * ══ 铁律 ══
 *
 * 1. **低信息量 ≠ 无用**。白底文字截图熵不高，但可能非常重要。
 *    特征标签只描述属性，不构成删除建议。
 * 2. **每条标签必须有可解释的证据**。UI 要能说清「为什么打这个标签」。
 * 3. **拿不准就不打**。宁可少一个标签，不要污染筛选结果。
 *
 * ══ 信号来源 ══
 *
 * 全部来自数据库已有字段，不需要重新解码图片：
 *   width / height        尺寸
 *   fileSize              体积
 *   imageEntropy          JPEG/PNG 灰度熵（分析任务已算）
 *   colorVariance         颜色方差（分析任务已算）
 *   ocrStatus / ocrAttempts
 *   recognizedText        识别文本
 *   duplicateGroupType    重复组归属
 */
object FeatureTagEngine {

    /**
     * 长截图判定。
     *
     * ══ 为什么门槛这么高 ══
     *
     * 手机竖屏正常比例约 2.2:1（1080×2400）。
     * 最初按 h >= w×2 判定，实测 8137 张里命中 6653 张（82%）——
     * 把每一张普通截图都算成长截图，标签等于没有。
     *
     * 真正的"长截图"是滚动拼接出来的，比例明显超出正常屏幕：
     * 实测 ratio >= 3 只有 58 张，这才是可信数量。
     * 同时要求绝对高度 ≥ 2400，避免小图巧合。
     */
    private const val LONG_RATIO = 3.0f
    private const val LONG_MIN_HEIGHT = 2400

    /** 大尺寸：像素总量阈值（约 4 MP，主流 1080x2400 为 2.6MP）。 */
    private const val LARGE_PIXELS = 4_000_000L

    /** 大文件阈值：2 MB。 */
    private const val LARGE_FILE_BYTES = 2L * 1024 * 1024

    /** 近乎空白：熵下限。 */
    private const val ENTROPY_NEAR_BLANK = 0.25f

    /** 近乎空白：颜色方差上限。 */
    private const val VARIANCE_NEAR_BLANK = 0.02f

    /** 低信息量：熵上限。 */
    private const val ENTROPY_LOW_INFO = 0.40f

    /** 小字体密度阈值：每百万像素的字符数。 */
    private const val SMALL_TEXT_DENSITY = 200.0f

    /** 小字体最少字符数，避免短文本误判。 */
    private const val SMALL_TEXT_MIN_CHARS = 120

    /** 横屏判定：宽 > 高 × 1.2。 */
    private const val LANDSCAPE_RATIO = 1.2f

    /**
     * 一张图的分析输入。
     *
     * 刻意做成纯数据类，不依赖 Android，方便单测。
     */
    data class Input(
        val width: Int,
        val height: Int,
        val fileSize: Long,
        val imageEntropy: Float?,
        val colorVariance: Float?,
        val ocrStatus: String,
        val ocrAttempts: Int,
        val recognizedText: String,
        /** EXACT / VISUAL / null，来自 duplicate_groups。 */
        val duplicateGroupType: String?,
    )

    /** 一条命中结果。 */
    data class Hit(
        val tag: FeatureTag,
        /** 面向用户的证据说明。 */
        val reason: String,
        /** 0..1，越高越确定。 */
        val confidence: Float,
    )

    /**
     * 计算一张图的全部特征标签。
     *
     * 返回的标签可能为空（例如一张普通尺寸、有文字、无重复的截图）。
     * 空结果是正常的 —— 不是所有图都需要打标签。
     */
    fun compute(input: Input): List<Hit> {
        val out = mutableListOf<Hit>()

        val w = input.width.coerceAtLeast(0)
        val h = input.height.coerceAtLeast(0)
        val pixels = w.toLong() * h.toLong()
        val text = input.recognizedText
        val textLen = text.trim().length

        // ── 长截图 ──
        if (w > 0 && h > 0 && h >= LONG_MIN_HEIGHT && h >= w * LONG_RATIO) {
            val ratio = h.toFloat() / w
            out += Hit(
                FeatureTag.LONG_SCREENSHOT,
                "高度是宽度的 %.1f 倍（%dx%d）".format(ratio, w, h),
                0.95f,
            )
        }

        // ── 大尺寸 ──
        if (pixels >= LARGE_PIXELS) {
            out += Hit(
                FeatureTag.LARGE_SIZE,
                "%.1f 百万像素（%dx%d）".format(pixels / 1_000_000.0, w, h),
                0.90f,
            )
        }

        // ── 大文件 ──
        if (input.fileSize >= LARGE_FILE_BYTES) {
            out += Hit(
                FeatureTag.LARGE_FILE,
                "文件 %.1f MB".format(input.fileSize / 1024.0 / 1024.0),
                0.95f,
            )
        }

        // ── 近乎空白 ──
        val entropy = input.imageEntropy
        val variance = input.colorVariance
        if (entropy != null && entropy >= 0f && variance != null && variance >= 0f) {
            if (entropy < ENTROPY_NEAR_BLANK && variance < VARIANCE_NEAR_BLANK) {
                out += Hit(
                    FeatureTag.NEAR_BLANK,
                    "图像熵 %.2f、颜色方差 %.3f，画面近乎纯色".format(entropy, variance),
                    0.90f,
                )
            } else if (entropy < ENTROPY_LOW_INFO && textLen == 0) {
                // 熵低 + 无文字 → 低信息量；有文字的不打这个标签（白底文字截图）
                out += Hit(
                    FeatureTag.LOW_INFO,
                    "图像熵 %.2f 且未识别到文字".format(entropy),
                    0.70f,
                )
            }
        }

        // ── 重复 / 相似 ──
        when (input.duplicateGroupType) {
            "EXACT" -> out += Hit(
                FeatureTag.EXACT_DUPLICATE,
                "与其他截图字节级完全一致",
                1.0f,
            )
            "VISUAL" -> out += Hit(
                FeatureTag.VISUAL_SIMILAR,
                "与其他截图画面高度相似",
                0.80f,
            )
        }

        // ── OCR 状态 ──
        when (input.ocrStatus) {
            "NOT_STARTED", "QUEUED", "FAILED" -> out += Hit(
                FeatureTag.OCR_UNRECOGNIZED,
                "尚未成功识别文字",
                0.95f,
            )
            // PARTIAL 在本项目的语义是「跑过但没识别出文字」
            "PARTIAL" -> out += Hit(
                FeatureTag.OCR_LOW_CONFIDENCE,
                "已处理但未识别出可用文字",
                0.85f,
            )
            "SUCCESS" -> {
                if (textLen in 1..4) {
                    out += Hit(
                        FeatureTag.OCR_LOW_CONFIDENCE,
                        "仅识别到 %d 个字符，可能不可靠".format(textLen),
                        0.60f,
                    )
                }
            }
        }

        // ── 小字体（密度代理）──
        // 字符密度高 + 是长截图 → 极可能是小字长文，提示分段识别价值
        if (pixels > 0 && textLen >= SMALL_TEXT_MIN_CHARS) {
            val density = textLen.toFloat() / (pixels / 1_000_000f)
            if (density >= SMALL_TEXT_DENSITY) {
                out += Hit(
                    FeatureTag.SMALL_TEXT,
                    "每百万像素 %.0f 字符，文字密集".format(density),
                    0.65f,
                )
            }
        }

        // ── 横屏图片 ──
        // 注意：这是「内容本身是横的」，不等于「文件被旋转」。
        // 之前叫「旋转图片」是错的 —— 横屏截图并没有旋转元数据。
        if (w > 0 && h > 0 && w > h * LANDSCAPE_RATIO) {
            out += Hit(
                FeatureTag.LANDSCAPE,
                "横向画面（%dx%d）".format(w, h),
                0.80f,
            )
        }

        // ── 二维码 ──
        // 说明：没有引入条码识别库，这里只用 OCR 文本证据。
        // 纯二维码图片（无文字）不会命中 —— 宁可漏，不可错。
        if (QR_HINT.containsMatchIn(text)) {
            out += Hit(
                FeatureTag.QRCODE,
                "识别到二维码相关文案",
                0.85f,
            )
        }

        return out
    }

    private val QR_HINT = Regex(
        """二维码|扫码|扫一扫|长按识别|qr\s*code|条形码""",
        RegexOption.IGNORE_CASE,
    )
}
