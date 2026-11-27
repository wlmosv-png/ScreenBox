package io.github.wlmosv.screenbox.domain.rules


/**
 * 布局结构分析引擎。
 *
 * ══ 为什么需要它（R8 核心）══
 *
 * 现有分类器只看 OCR 文本内容，本质是关键词匹配。
 * 实测结果：8105 张"有分类"里 5794 张（71%）是兜底桶（app_ui/other），
 * 真实高置信分类只有 2311 张。这不是覆盖率问题，是**方法问题**。
 *
 * 关键洞察：OCR 已经保存了每个文字块的像素坐标（blockBounds），
 * 8096/8144 张都有。这些坐标免费携带了大量结构信息：
 *
 *   聊天记录   → 文字块左右交错分布（气泡），时间戳居中
 *   应用界面   → 顶部标题栏 + 底部导航栏 + 中部内容，三带分明
 *   网页/文章  → 单栏、行宽一致、行距均匀
 *   商品列表   → 多列并排（网格）
 *   系统设置   → 单列、左标签右值、行高一致
 *   二维码     → 中央大块空白 + 周边极少文字
 *
 * 这些特征**不需要视觉模型**，不需要额外推理，纯几何计算。
 * 比关键词可靠：聊天记录不一定出现"微信"二字，但气泡一定是左右交错的。
 *
 * ══ 设计原则 ══
 *
 * 1. 纯函数，无 Android 依赖，可单测
 * 2. 只输出**可解释的几何特征**，不直接给分类结论
 * 3. 特征必须归一化（按图片宽高），不同分辨率可比
 * 4. 拿不到坐标就返回空特征，调用方降级，不硬猜
 */
object LayoutAnalyzer {

    /** 一个文字块的归一化矩形（0..1）。 */
    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    /**
     * 布局特征。
     *
     * 全部归一化到 0..1，与图片分辨率无关。
     */
    data class Features(
        /** 文字块总数。太少说明是纯图。 */
        val boxCount: Int,
        /** 文字覆盖面积占比。判断是否文字密集。 */
        val textAreaRatio: Float,
        /** 行宽变异系数（标准差/均值）。单栏文章低，多列网格高。 */
        val lineWidthCv: Float,
        /** 左右交错度：文字块中心偏离中线的分布。聊天气泡高。 */
        val leftRightAlternation: Float,
        /** 顶部带（前 15%）文字占比。有标题栏则 > 0。 */
        val topBandRatio: Float,
        /** 底部带（后 15%）文字占比。有导航栏则 > 0。 */
        val bottomBandRatio: Float,
        /** 中部带文字占比。 */
        val middleBandRatio: Float,
        /** 行高一致性（变异系数）。列表类界面低。 */
        val lineHeightCv: Float,
        /** 左侧对齐率：左边缘落在少数几个 x 上的比例。列表/文章高。 */
        val leftAlignRatio: Float,
        /** 中央空白度：中心区域（40%-60%）无文字的比例。二维码/图片高。 */
        val centerBlankness: Float,
        /** 多列度：同一纵向区间内并排出现多个块的比例。商品网格高。 */
        val multiColumnRatio: Float,
        /** 平均行高（归一化）。小字截图低。 */
        val avgLineHeight: Float,
        /** 文字块纵向跨度。长截图接近 1.0。 */
        val verticalSpan: Float,
    ) {
        /** 是否有效（块数够多才可信）。 */
        val valid: Boolean get() = boxCount >= 3
    }

    /**
     * 解析 OCR 保存的 blockBounds JSON 并计算布局特征。
     *
     * @param json 形如 `[{"l":44,"t":70,"r":226,"b":114}, ...]`
     * @param imgWidth 原图宽
     * @param imgHeight 原图高
     * @return 无坐标或坐标非法时返回 null，调用方降级
     */
    fun analyze(json: String?, imgWidth: Int, imgHeight: Int): Features? {
        if (json.isNullOrBlank() || imgWidth <= 0 || imgHeight <= 0) return null

        val w = imgWidth.toFloat()
        val h = imgHeight.toFloat()

        // 刻意不用 org.json：那是 Android 专有 API，JVM 单测里会 NPE。
        // 这里的格式是固定且简单的（我们自己写的），手写解析足够且可测。
        val boxes = ArrayList<Box>()
        for (obj in splitObjects(json)) {
            val l = intField(obj, "l")
            val t = intField(obj, "t")
            val r = intField(obj, "r")
            val b = intField(obj, "b")
            if (l < 0 || t < 0 || r <= l || b <= t) continue
            boxes += Box(
                left = (l / w).coerceIn(0f, 1f),
                top = (t / h).coerceIn(0f, 1f),
                right = (r / w).coerceIn(0f, 1f),
                bottom = (b / h).coerceIn(0f, 1f),
            )
        }
        if (boxes.isEmpty()) return null

        // ── 文字覆盖面积 ──
        val area = boxes.sumOf { (it.width * it.height).toDouble() }.toFloat()
        val textAreaRatio = area.coerceIn(0f, 1f)

        // ── 行宽变异系数 ──
        val widths = boxes.map { it.width }
        val meanW = widths.average().toFloat()
        val lineWidthCv = if (meanW > 1e-4f) stdDev(widths) / meanW else 0f

        // ── 行高变异系数 ──
        val heights = boxes.map { it.height }
        val meanH = heights.average().toFloat()
        val lineHeightCv = if (meanH > 1e-4f) stdDev(heights) / meanH else 0f

        // ── 左右交错度 ──
        // 聊天气泡：一部分块明显靠左，一部分明显靠右，中间少。
        // 计算方法：统计"中心在左 1/3"和"中心在右 1/3"的块各占多少，
        // 两者都显著时交错度高。
        val leftCount = boxes.count { it.centerX < 0.4f }
        val rightCount = boxes.count { it.centerX > 0.6f }
        val leftRightAlternation =
            (leftCount.toFloat() / boxes.size) * (rightCount.toFloat() / boxes.size) * 4f

        // ── 三带分布 ──
        val topBand = boxes.count { it.centerY < 0.15f }
        val bottomBand = boxes.count { it.centerY > 0.85f }
        val middleBand = boxes.size - topBand - bottomBand
        val n = boxes.size.toFloat()
        val topBandRatio = topBand / n
        val bottomBandRatio = bottomBand / n
        val middleBandRatio = middleBand / n

        // ── 左对齐率 ──
        // 把左边缘量化到 20 个桶，最大的桶占比即对齐率。
        val leftBuckets = boxes.groupingBy { (it.left * 20).toInt() }.eachCount()
        val leftAlignRatio = (leftBuckets.values.maxOrNull() ?: 0) / n

        // ── 中央空白度 ──
        // 中心区域（x 0.4-0.6, y 0.3-0.7）被文字覆盖的比例，取反。
        val centerBoxes = boxes.filter {
            it.centerX in 0.4f..0.6f && it.centerY in 0.3f..0.7f
        }
        val centerCovered = centerBoxes.sumOf { (it.width * it.height).toDouble() }.toFloat()
        // 中心区域面积 = 0.2 * 0.4 = 0.08
        val centerBlankness = (1f - (centerCovered / 0.08f)).coerceIn(0f, 1f)

        // ── 多列度 ──
        // 纵向把图分 10 段，统计每段内是否有"并排且不重叠"的块。
        val segmentsWithColumns = (0 until 10).count { seg ->
            val lo = seg / 10f
            val hi = (seg + 1) / 10f
            val inSeg = boxes.filter { it.centerY in lo..hi }.sortedBy { it.left }
            var found = false
            for (i in inSeg.indices) {
                for (j in i + 1 until inSeg.size) {
                    // 水平不重叠且同一纵向区间 → 并排
                    if (inSeg[j].left >= inSeg[i].right - 0.02f) {
                        found = true
                        break
                    }
                }
                if (found) break
            }
            found
        }
        val multiColumnRatio = segmentsWithColumns / 10f

        val verticalSpan =
            if (boxes.size >= 2) (boxes.maxOf { it.bottom } - boxes.minOf { it.top }).coerceIn(0f, 1f)
            else 0f

        return Features(
            boxCount = boxes.size,
            textAreaRatio = textAreaRatio,
            lineWidthCv = lineWidthCv,
            leftRightAlternation = leftRightAlternation.coerceIn(0f, 1f),
            topBandRatio = topBandRatio,
            bottomBandRatio = bottomBandRatio,
            middleBandRatio = middleBandRatio,
            lineHeightCv = lineHeightCv,
            leftAlignRatio = leftAlignRatio,
            centerBlankness = centerBlankness,
            multiColumnRatio = multiColumnRatio,
            avgLineHeight = meanH,
            verticalSpan = verticalSpan,
        )
    }

    /** 把 `[{...},{...}]` 切成单个对象的字符串。 */
    private fun splitObjects(json: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        for (i in json.indices) {
            when (json[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += json.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return out
    }

    /** 从 `{"l":12,"t":34,...}` 里取整数字段，缺失返回 -1。 */
    private fun intField(obj: String, name: String): Int {
        val key = "\"" + name + "\":"
        val idx = obj.indexOf(key)
        if (idx < 0) return -1
        var i = idx + key.length
        while (i < obj.length && obj[i] == ' ') i++
        val begin = i
        if (i < obj.length && (obj[i] == '-' || obj[i] == '+')) i++
        while (i < obj.length && obj[i].isDigit()) i++
        if (i == begin) return -1
        return obj.substring(begin, i).toIntOrNull() ?: -1
    }

    private fun stdDev(xs: List<Float>): Float {
        if (xs.size < 2) return 0f
        val m = xs.average()
        val v = xs.sumOf { (it - m) * (it - m) } / xs.size
        return kotlin.math.sqrt(v).toFloat()
    }
}