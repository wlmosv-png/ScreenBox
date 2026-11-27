package io.github.wlmosv.screenbox

import io.github.wlmosv.screenbox.domain.rules.LayoutAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局分析引擎测试。
 *
 * ══ 说明（诚实交代）══
 *
 * 实测发现：现有数据库只有**块级**坐标，一个 block 常是整段文字、
 * 宽度横跨全屏，导致聊天气泡、多列网格等结构特征被抹平。
 * 真实数据上 chat 与 app_ui 的交错度中位数都是 0.5 左右，无法区分。
 *
 * 因此本引擎的定位是**几何特征提取器**，不是分类器。
 * 它的价值要等行级坐标回填后才会体现。
 *
 * 这些测试验证的是「特征计算本身正确」，不是「能分类」。
 * 不写假的准确率断言。
 */
class LayoutAnalyzerTest {

    private fun json(vararg boxes: IntArray): String =
        boxes.joinToString(",", "[", "]") { b ->
            """{"l":${b[0]},"t":${b[1]},"r":${b[2]},"b":${b[3]}}"""
        }

    @Test
    fun `无坐标返回 null 让调用方降级`() {
        assertNull(LayoutAnalyzer.analyze(null, 1080, 2400))
        assertNull(LayoutAnalyzer.analyze("", 1080, 2400))
        assertNull(LayoutAnalyzer.analyze("[]", 1080, 2400))
        assertNull(LayoutAnalyzer.analyze(json(intArrayOf(0, 0, 10, 10)), 0, 2400))
    }

    @Test
    fun `非法矩形被跳过而不是崩溃`() {
        val bad = """[{"l":100,"t":100,"r":50,"b":50},{"l":-1,"t":0,"r":10,"b":10}]"""
        assertNull("全部非法应返回 null", LayoutAnalyzer.analyze(bad, 1080, 2400))
    }

    @Test
    fun `块数太少标记为无效`() {
        val f = LayoutAnalyzer.analyze(json(intArrayOf(10, 10, 200, 60)), 1080, 2400)
        assertNotNull(f)
        assertTrue("2 块以下不可信", !f!!.valid)
    }

    @Test
    fun `左右交错的聊天布局交错度高`() {
        // 左气泡 + 右气泡交替
        val chat = json(
            intArrayOf(40, 200, 400, 260),
            intArrayOf(680, 300, 1040, 360),
            intArrayOf(40, 400, 380, 460),
            intArrayOf(700, 500, 1040, 560),
            intArrayOf(40, 600, 420, 660),
            intArrayOf(660, 700, 1040, 760),
        )
        val f = LayoutAnalyzer.analyze(chat, 1080, 2400)!!
        assertTrue("交错度应显著", f.leftRightAlternation > 0.5f)
    }

    @Test
    fun `单栏文章交错度低`() {
        // 全部左对齐、等宽
        val article = json(
            intArrayOf(80, 200, 1000, 240),
            intArrayOf(80, 280, 1000, 320),
            intArrayOf(80, 360, 1000, 400),
            intArrayOf(80, 440, 1000, 480),
            intArrayOf(80, 520, 1000, 560),
        )
        val f = LayoutAnalyzer.analyze(article, 1080, 2400)!!
        assertTrue("单栏不应高交错", f.leftRightAlternation < 0.2f)
        assertTrue("单栏应高左对齐", f.leftAlignRatio > 0.8f)
    }

    @Test
    fun `多列网格能识别出并排`() {
        // 两列并排，重复 5 行
        val boxes = mutableListOf<IntArray>()
        for (i in 0 until 5) {
            val y = 200 + i * 200
            boxes += intArrayOf(60, y, 500, y + 150)
            boxes += intArrayOf(580, y, 1020, y + 150)
        }
        val f = LayoutAnalyzer.analyze(json(*boxes.toTypedArray()), 1080, 2400)!!
        assertTrue("多列度应显著", f.multiColumnRatio > 0.3f)
    }

    @Test
    fun `中央空白度对无中心文字的图更高`() {
        // 只有四角文字
        val corners = json(
            intArrayOf(40, 60, 300, 120),
            intArrayOf(780, 60, 1040, 120),
            intArrayOf(40, 2200, 300, 2300),
            intArrayOf(780, 2200, 1040, 2300),
        )
        val f = LayoutAnalyzer.analyze(corners, 1080, 2400)!!
        assertTrue("中心无字应空白度高", f.centerBlankness > 0.8f)
    }

    @Test
    fun `顶部底部带比例反映标题栏与导航栏`() {
        val boxes = mutableListOf<IntArray>()
        // 顶部标题栏
        boxes += intArrayOf(100, 50, 900, 110)
        // 中部内容
        for (i in 0 until 8) {
            val y = 400 + i * 200
            boxes += intArrayOf(80, y, 1000, y + 60)
        }
        // 底部导航
        boxes += intArrayOf(100, 2280, 900, 2340)
        val f = LayoutAnalyzer.analyze(json(*boxes.toTypedArray()), 1080, 2400)!!
        assertTrue("应有顶部带文字", f.topBandRatio > 0f)
        assertTrue("应有底部带文字", f.bottomBandRatio > 0f)
        assertTrue("中部应占多数", f.middleBandRatio > 0.5f)
    }

    @Test
    fun `特征全部归一化在 0 到 1`() {
        val f = LayoutAnalyzer.analyze(
            json(
                intArrayOf(40, 200, 400, 260),
                intArrayOf(680, 300, 1040, 360),
                intArrayOf(40, 400, 1000, 460),
            ),
            1080, 2400,
        )!!
        listOf(
            f.textAreaRatio, f.leftRightAlternation, f.topBandRatio,
            f.bottomBandRatio, f.middleBandRatio, f.leftAlignRatio,
            f.centerBlankness, f.multiColumnRatio, f.verticalSpan,
        ).forEach {
            assertTrue("特征必须在 0..1，实际 $it", it in 0f..1f)
        }
        assertEquals("三带比例之和应为 1", 1f, f.topBandRatio + f.bottomBandRatio + f.middleBandRatio, 0.01f)
    }

    @Test
    fun `长截图纵向跨度接近 1`() {
        val boxes = mutableListOf<IntArray>()
        for (i in 0 until 20) {
            val y = 50 + i * 400
            boxes += intArrayOf(80, y, 1000, y + 60)
        }
        val f = LayoutAnalyzer.analyze(json(*boxes.toTypedArray()), 1080, 8000)!!
        assertTrue("长截图纵向跨度应大", f.verticalSpan > 0.8f)
    }
}