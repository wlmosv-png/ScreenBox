package io.github.wlmosv.screenbox

import io.github.wlmosv.screenbox.data.db.FeatureTag
import io.github.wlmosv.screenbox.domain.rules.FeatureTagEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 特征标签引擎测试。
 *
 * 每条用例对应一个真实踩过的坑，不是凑数。
 */
class FeatureTagEngineTest {

    private fun input(
        w: Int = 1080,
        h: Int = 2400,
        size: Long = 300_000,
        entropy: Float? = 0.7f,
        variance: Float? = 0.1f,
        ocr: String = "SUCCESS",
        attempts: Int = 0,
        text: String = "普通截图内容",
        dup: String? = null,
    ) = FeatureTagEngine.Input(w, h, size, entropy, variance, ocr, attempts, text, dup)

    private fun keys(hits: List<FeatureTagEngine.Hit>) = hits.map { it.tag }.toSet()

    @Test
    fun `普通竖屏截图不算长截图`() {
        // 1080x2400 = 2.22:1，这是最常见的手机比例，不是长截图
        val hits = FeatureTagEngine.compute(input(w = 1080, h = 2400))
        assertFalse("2.2:1 不应判为长截图", FeatureTag.LONG_SCREENSHOT in keys(hits))
    }

    @Test
    fun `真正的长截图能识别`() {
        val hits = FeatureTagEngine.compute(input(w = 1080, h = 8000))
        assertTrue("3:1 以上应判为长截图", FeatureTag.LONG_SCREENSHOT in keys(hits))
    }

    @Test
    fun `白底文字截图不算近乎空白`() {
        // 关键：低熵不等于无用。白底黑字熵不高，但内容重要。
        val hits = FeatureTagEngine.compute(
            input(entropy = 0.3f, variance = 0.05f, text = "这是一段重要文字内容")
        )
        assertFalse("有文字时不能判近乎空白", FeatureTag.NEAR_BLANK in keys(hits))
    }

    @Test
    fun `真正空白图判近乎空白`() {
        val hits = FeatureTagEngine.compute(input(entropy = 0.05f, variance = 0.001f, text = ""))
        assertTrue(FeatureTag.NEAR_BLANK in keys(hits))
    }

    @Test
    fun `低熵加无文字才判低信息量`() {
        val withText = FeatureTagEngine.compute(input(entropy = 0.3f, variance = 0.05f, text = "有字"))
        assertFalse(FeatureTag.LOW_INFO in keys(withText))

        val noText = FeatureTagEngine.compute(input(entropy = 0.3f, variance = 0.05f, text = ""))
        assertTrue(FeatureTag.LOW_INFO in keys(noText))
    }

    @Test
    fun `大文件阈值`() {
        val small = FeatureTagEngine.compute(input(size = 500_000))
        assertFalse(FeatureTag.LARGE_FILE in keys(small))

        val big = FeatureTagEngine.compute(input(size = 3L * 1024 * 1024))
        assertTrue(FeatureTag.LARGE_FILE in keys(big))
    }

    @Test
    fun `完全重复来自重复组而不是猜测`() {
        val exact = FeatureTagEngine.compute(input(dup = "EXACT"))
        assertTrue(FeatureTag.EXACT_DUPLICATE in keys(exact))

        val visual = FeatureTagEngine.compute(input(dup = "VISUAL"))
        assertTrue(FeatureTag.VISUAL_SIMILAR in keys(visual))
        assertFalse("视觉相似不等于完全重复", FeatureTag.EXACT_DUPLICATE in keys(visual))
    }

    @Test
    fun `未识别文字打 OCR 标签`() {
        val notStarted = FeatureTagEngine.compute(input(ocr = "NOT_STARTED", text = ""))
        assertTrue(FeatureTag.OCR_UNRECOGNIZED in keys(notStarted))

        val failed = FeatureTagEngine.compute(input(ocr = "FAILED", text = ""))
        assertTrue(FeatureTag.OCR_UNRECOGNIZED in keys(failed))
    }

    @Test
    fun `横屏不等于旋转`() {
        // 1298 张横屏图曾被标成"旋转图片"，实为误标
        val hits = FeatureTagEngine.compute(input(w = 2400, h = 1080))
        assertTrue(FeatureTag.LANDSCAPE in keys(hits))
        assertTrue(
            "不应再有 ROTATED 这个枚举",
            FeatureTag.entries.none { it.name == "ROTATED" }
        )
    }

    @Test
    fun `每条标签都带可读理由`() {
        val hits = FeatureTagEngine.compute(
            input(w = 1080, h = 8000, size = 3L * 1024 * 1024, dup = "EXACT")
        )
        assertTrue(hits.isNotEmpty())
        hits.forEach {
            assertTrue("理由不能为空", it.reason.isNotBlank())
            assertTrue("置信度必须在 0..1", it.confidence in 0f..1f)
        }
    }

    @Test
    fun `边界尺寸不会崩溃`() {
        val zero = FeatureTagEngine.compute(input(w = 0, h = 0, size = 0))
        assertEquals("零尺寸不应产生尺寸类标签",
            0, zero.count { it.tag == FeatureTag.LONG_SCREENSHOT || it.tag == FeatureTag.LARGE_SIZE })
    }
}