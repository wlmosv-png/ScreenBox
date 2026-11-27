package io.github.wlmosv.screenbox

import io.github.wlmosv.screenbox.data.db.OcrStatus
import io.github.wlmosv.screenbox.domain.query.SavedFilterCodec
import io.github.wlmosv.screenbox.domain.query.SearchQueryParser
import io.github.wlmosv.screenbox.domain.query.ShotFilter
import io.github.wlmosv.screenbox.domain.query.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存/恢复筛选条件的回归测试。
 *
 * 这些用例都来自真实使用场景：用户把「微信 + 90 天前 + 大于 2MB」存下来，
 * 下次打开必须一模一样，而不是"看起来像"。
 */
class SavedFilterCodecTest {

    private val full = ShotFilter(
        contentCategories = setOf("chat", "order"),
        sourceApps = setOf("wechat", "qq"),
        featureTags = setOf("long_shot"),
        timeWindow = TimeWindow("90 天以前", null, 1_700_000_000_000L),
        minBytes = 2L * 1024 * 1024,
        maxBytes = 50L * 1024 * 1024,
        favorite = false,
        protected = true,
        ocrStatuses = setOf(OcrStatus.SUCCESS, OcrStatus.PARTIAL),
        textQuery = "订单号 A|B=100%",
        exactDuplicateOnly = true,
        similarOnly = false,
        redundantOnly = true,
    )

    @Test
    fun `round trip keeps every dimension`() {
        val decoded = SavedFilterCodec.decode(SavedFilterCodec.encode(full))
        requireNotNull(decoded)
        assertEquals(full.contentCategories, decoded.contentCategories)
        assertEquals(full.sourceApps, decoded.sourceApps)
        assertEquals(full.featureTags, decoded.featureTags)
        assertEquals(full.timeWindow.fromMs, decoded.timeWindow.fromMs)
        assertEquals(full.timeWindow.toMs, decoded.timeWindow.toMs)
        assertEquals(full.minBytes, decoded.minBytes)
        assertEquals(full.maxBytes, decoded.maxBytes)
        assertEquals(full.favorite, decoded.favorite)
        assertEquals(full.protected, decoded.protected)
        assertEquals(full.ocrStatuses, decoded.ocrStatuses)
        assertEquals(full.textQuery, decoded.textQuery)
        assertTrue(decoded.exactDuplicateOnly)
        assertTrue(!decoded.similarOnly)
        assertTrue(decoded.redundantOnly)
    }

    @Test
    fun `text query with separators survives`() {
        val f = ShotFilter(textQuery = "a|b=c&d \"引号\"")
        val decoded = SavedFilterCodec.decode(SavedFilterCodec.encode(f))
        assertEquals("a|b=c&d \"引号\"", decoded?.textQuery)
    }

    @Test
    fun `empty payload is rejected instead of producing empty filter`() {
        assertNull(SavedFilterCodec.decode(""))
        assertNull(SavedFilterCodec.decode("   "))
    }

    @Test
    fun `unknown keys from future versions are ignored`() {
        val payload = "v1|cats=chat|future=42|srcs=wechat"
        val decoded = SavedFilterCodec.decode(payload)
        requireNotNull(decoded)
        assertEquals(setOf("chat"), decoded.contentCategories)
        assertEquals(setOf("wechat"), decoded.sourceApps)
    }

    @Test
    fun `empty filter round trips as empty`() {
        val decoded = SavedFilterCodec.decode(SavedFilterCodec.encode(ShotFilter()))
        requireNotNull(decoded)
        assertTrue(decoded.isEmpty)
    }

    // ── 搜索语法解析 ──

    @Test
    fun `duplicate token does not break OR grouping`() {
        // 旧实现用 indexOf(t)，重复词永远返回第一个下标，导致分组错乱。
        val q = requireNotNull(SearchQueryParser.parse("微信 微信 OR QQ"))
        // 语义是 (微信) AND (微信 OR QQ)：两组，第二组同时含两个词。
        // 旧实现用 indexOf 定位 OR，第二个"微信"会被当成第一个，分组塌成一组。
        assertEquals(2, q.andGroups.size)
        assertTrue(q.andGroups[0].contains("微信"))
        assertTrue(q.andGroups[1].any { it.contains("QQ") })
        assertTrue(q.andGroups[1].any { it.contains("微信") })
    }

    @Test
    fun `space means AND`() {
        val q = requireNotNull(SearchQueryParser.parse("微信 转账"))
        assertEquals(2, q.andGroups.size)
    }

    @Test
    fun `exclusion is parsed separately`() {
        val q = requireNotNull(SearchQueryParser.parse("聊天 -广告"))
        assertEquals(1, q.andGroups.size)
        assertEquals(listOf("广告"), q.excludes)
    }

    @Test
    fun `quoted phrase is kept whole`() {
        val q = requireNotNull(SearchQueryParser.parse("\"订单已发货\""))
        assertEquals(listOf("订单已发货"), q.phrases)
    }

    @Test
    fun `structured query is recognized`() {
        assertTrue(requireNotNull(SearchQueryParser.parse("2025-03-15")).looksStructured)
        assertTrue(requireNotNull(SearchQueryParser.parse("¥199")).looksStructured)
        val cands = requireNotNull(SearchQueryParser.parse("¥199")).structuredCandidates()
        assertTrue(cands.contains("199"))
    }
}
