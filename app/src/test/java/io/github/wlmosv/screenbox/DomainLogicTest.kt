package io.github.wlmosv.screenbox

import io.github.wlmosv.screenbox.domain.extract.EntityExtractor
import io.github.wlmosv.screenbox.domain.query.SearchQueryParser
import io.github.wlmosv.screenbox.domain.query.ShotFilter
import io.github.wlmosv.screenbox.domain.query.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑单元测试（不依赖 Android）。
 *
 * 覆盖：
 * - 搜索语法解析（AND / OR / 短语 / 排除）
 * - 筛选条件组合
 * - 结构化实体提取的准确性（关键：不能误判）
 * - 过期日期检测的保守性
 */
class DomainLogicTest {

    // ─────────────────────────────────────────────
    // 搜索语法
    // ─────────────────────────────────────────────

    @Test
    fun `单关键词解析为一个 AND 组`() {
        val q = SearchQueryParser.parse("微信")
        assertNotNull(q)
        assertEquals(1, q!!.andGroups.size)
        assertEquals("微信", q.andGroups[0].first())
        assertTrue(q.excludes.isEmpty())
    }

    @Test
    fun `空格分隔产生多个 AND 组`() {
        val q = SearchQueryParser.parse("微信 转账")
        assertNotNull(q)
        assertEquals(2, q!!.andGroups.size)
        assertEquals("微信", q.andGroups[0].first())
        assertEquals("转账", q.andGroups[1].first())
    }

    @Test
    fun `OR 产生单个多元素组`() {
        val q = SearchQueryParser.parse("微信 OR QQ")
        assertNotNull(q)
        assertEquals(1, q!!.andGroups.size)
        // 组内应包含变体（微信、QQ、qq 等）
        assertTrue("组内应有多个变体，实际 ${q.andGroups[0]}", q.andGroups[0].size >= 2)
        assertTrue(q.andGroups[0].any { it == "微信" })
        assertTrue(q.andGroups[0].any { it.equals("QQ", ignoreCase = true) })
    }

    @Test
    fun `引号产生精确短语`() {
        val q = SearchQueryParser.parse("\"订单已发货\"")
        assertNotNull(q)
        assertEquals(1, q!!.phrases.size)
        assertEquals("订单已发货", q.phrases[0])
        assertTrue(q.andGroups.isEmpty())
    }

    @Test
    fun `减号产生排除词`() {
        val q = SearchQueryParser.parse("聊天 -广告")
        assertNotNull(q)
        assertEquals(1, q!!.andGroups.size)
        assertEquals(1, q.excludes.size)
        assertEquals("广告", q.excludes[0])
    }

    @Test
    fun `空输入返回 null`() {
        assertNull(SearchQueryParser.parse(""))
        assertNull(SearchQueryParser.parse("   "))
    }

    @Test
    fun `日期查询被识别为结构化`() {
        val q = SearchQueryParser.parse("2025-03-15")
        assertNotNull(q)
        assertTrue("应识别为结构化查询", q!!.looksStructured)
        val cands = q.structuredCandidates()
        assertTrue("应产出 2025-03-15，实际 $cands", cands.contains("2025-03-15"))
    }

    @Test
    fun `金额查询被识别为结构化`() {
        val q = SearchQueryParser.parse("¥199")
        assertNotNull(q)
        assertTrue(q!!.looksStructured)
        assertTrue(q.structuredCandidates().contains("199"))
    }

    @Test
    fun `普通中文不被识别为结构化`() {
        val q = SearchQueryParser.parse("聊天记录")
        assertNotNull(q)
        assertTrue("普通词不应走结构化", !q!!.looksStructured)
    }

    @Test
    fun `全角转半角`() {
        assertEquals("ABC123", SearchQueryParser.toHalfWidth("ＡＢＣ１２３"))
    }

    // ─────────────────────────────────────────────
    // 筛选条件
    // ─────────────────────────────────────────────

    @Test
    fun `空筛选被判定为空`() {
        assertTrue(ShotFilter().isEmpty)
    }

    @Test
    fun `任一条件让筛选非空`() {
        assertTrue(!ShotFilter(contentCategories = setOf("game")).isEmpty)
        assertTrue(!ShotFilter(sourceApps = setOf("wechat")).isEmpty)
        assertTrue(!ShotFilter(minBytes = 1024).isEmpty)
        assertTrue(!ShotFilter(favorite = true).isEmpty)
        assertTrue(!ShotFilter(exactDuplicateOnly = true).isEmpty)
        assertTrue(!ShotFilter().withText("测试").isEmpty)
    }

    @Test
    fun `切换分类是幂等可逆的`() {
        val f0 = ShotFilter()
        val f1 = f0.toggleCategory("game")
        assertTrue(f1.contentCategories.contains("game"))
        val f2 = f1.toggleCategory("game")
        assertTrue(f2.contentCategories.isEmpty())
    }

    @Test
    fun `时间窗口描述正确`() {
        val older = TimeWindow.olderThan(90)
        assertEquals("90 天以前", older.label)
        assertNull(older.fromMs)
        assertNotNull(older.toMs)
    }

    // ─────────────────────────────────────────────
    // 实体提取（关键：不能误判）
    // ─────────────────────────────────────────────

    @Test
    fun `完整日期被正确提取`() {
        val ents = EntityExtractor.extract("订单日期 2025-03-15 已发货")
        val date = ents.firstOrNull { it.kind == EntityExtractor.Kind.DATE }
        assertNotNull("应提取到日期", date)
        assertEquals("2025-03-15", date!!.normalized)
    }

    @Test
    fun `中文日期格式被归一化`() {
        val ents = EntityExtractor.extract("有效期至 2025年3月15日")
        val date = ents.firstOrNull { it.kind == EntityExtractor.Kind.DATE }
        assertNotNull(date)
        assertEquals("2025-03-15", date!!.normalized)
    }

    @Test
    fun `货币符号金额被提取`() {
        val ents = EntityExtractor.extract("实付 ¥199.00")
        val amt = ents.firstOrNull { it.kind == EntityExtractor.Kind.AMOUNT }
        assertNotNull(amt)
        assertEquals("199.00", amt!!.normalized)
    }

    @Test
    fun `零金额被过滤`() {
        val ents = EntityExtractor.extract("优惠0元")
        assertTrue(
            "0 元不应被当作有效金额",
            ents.none { it.kind == EntityExtractor.Kind.AMOUNT }
        )
    }

    @Test
    fun `无符号无单位的裸数字不被当作金额`() {
        val ents = EntityExtractor.extract("商品数量 3 件")
        assertTrue(
            "裸数字不应被当作金额",
            ents.none { it.kind == EntityExtractor.Kind.AMOUNT }
        )
    }

    @Test
    fun `订单号必须有语境词`() {
        val withCtx = EntityExtractor.extract("订单号：2025031512345678")
        assertTrue(withCtx.any { it.kind == EntityExtractor.Kind.ORDER_NO })

        val withoutCtx = EntityExtractor.extract("2025031512345678")
        assertTrue(
            "无语境词的长数字不应被当作订单号",
            withoutCtx.none { it.kind == EntityExtractor.Kind.ORDER_NO }
        )
    }

    @Test
    fun `手机号需要边界断言`() {
        val valid = EntityExtractor.extract("联系电话 13812345678")
        assertTrue(valid.any { it.kind == EntityExtractor.Kind.PHONE })

        // 从更长的数字串里不应截取出手机号
        val invalid = EntityExtractor.extract("编号 991381234567899")
        assertTrue(
            "长数字串内不应截取手机号",
            invalid.none { it.kind == EntityExtractor.Kind.PHONE }
        )
    }

    @Test
    fun `URL 被提取`() {
        val ents = EntityExtractor.extract("访问 https://example.com/path?x=1")
        assertTrue(ents.any { it.kind == EntityExtractor.Kind.URL })
    }

    @Test
    fun `邮箱被提取`() {
        val ents = EntityExtractor.extract("联系 test@example.com")
        assertTrue(ents.any { it.kind == EntityExtractor.Kind.EMAIL })
    }

    @Test
    fun `孤立时间不被提取`() {
        // 状态栏的 HH:mm 不应该产生 TIME 实体
        val ents = EntityExtractor.extract("10:30 微信 消息")
        assertTrue(
            "孤立时间不应被提取（状态栏噪音）",
            ents.none { it.kind == EntityExtractor.Kind.TIME }
        )
    }

    @Test
    fun `带语境的时间被提取`() {
        val ents = EntityExtractor.extract("下单时间 14:25")
        assertTrue(ents.any { it.kind == EntityExtractor.Kind.TIME })
    }

    @Test
    fun `空文本不产生实体`() {
        assertTrue(EntityExtractor.extract("").isEmpty())
        assertTrue(EntityExtractor.extract("   ").isEmpty())
    }

    // ─────────────────────────────────────────────
    // 过期检测（保守性）
    // ─────────────────────────────────────────────

    private val now = 1_700_000_000_000L // 2023-11-14

    @Test
    fun `过期语境加过期日期才判定`() {
        val r = ExpiryDetectorShim.findExpired("优惠券有效期至 2023-01-01", now)
        assertNotNull("应判定为过期", r)
    }

    @Test
    fun `只有日期没有过期词不判定`() {
        val r = ExpiryDetectorShim.findExpired("订单日期 2023-01-01", now)
        assertNull("无过期语境词不应判定", r)
    }

    @Test
    fun `否定语境词直接跳过`() {
        val r = ExpiryDetectorShim.findExpired("出生日期 2023-01-01 有效期至", now)
        assertNull("含否定语境词应跳过", r)
    }

    @Test
    fun `未过期的日期不判定`() {
        val r = ExpiryDetectorShim.findExpired("有效期至 2030-01-01", now)
        assertNull("未过期不应判定", r)
    }
}

/**
 * 桥接到 ExpiryDetector（它在 data.repo 包，依赖 Room 类型但逻辑是纯函数）。
 * 单元测试不加载 Room，所以这里直接复制调用签名做验证。
 */
object ExpiryDetectorShim {
    fun findExpired(text: String, nowMs: Long) =
        io.github.wlmosv.screenbox.data.repo.ExpiryDetector.findExpiredDate(text, nowMs)
}