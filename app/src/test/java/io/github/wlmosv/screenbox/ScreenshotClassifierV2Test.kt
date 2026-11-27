package io.github.wlmosv.screenbox

import io.github.wlmosv.screenbox.domain.rules.ScreenshotClassifierV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2 分类器测试。
 *
 * 用例全部来自真实数据观察（R9 评估集），不是编的。
 * 每条对应一个实测统计结论。
 */
class ScreenshotClassifierV2Test {

    private fun keys(t: String, w: Int = 1080, h: Int = 2400): Set<String> =
        ScreenshotClassifierV2.classify(t, w, h).map { it.categoryKey }.toSet()

    // ─────────────────────────────────────────────
    // 几何信号（本轮最大的提升点）
    // ─────────────────────────────────────────────

    @Test
    fun `横屏判为游戏 即使没有任何文字`() {
        // 实测：游戏截图 97% 横屏，其他类型 99% 竖屏
        assertEquals(setOf("game"), keys("", 2400, 1080))
    }

    @Test
    fun `横屏加游戏词给 HIGH 置信度`() {
        // 实测精度 90.7%
        val hits = ScreenshotClassifierV2.classify("MVP 战绩 段位 胜利", 2400, 1080)
        assertEquals(ScreenshotClassifierV2.Tier.HIGH, hits.first().tier)
        assertEquals(0.90f, hits.first().confidence, 0.01f)
    }

    @Test
    fun `横屏无游戏词只给 MEDIUM 置信度`() {
        // 实测精度只有 68.9%，不能标 HIGH
        val hits = ScreenshotClassifierV2.classify("", 2400, 1080)
        assertEquals(ScreenshotClassifierV2.Tier.MEDIUM, hits.first().tier)
    }

    @Test
    fun `竖屏不会因几何被判为游戏`() {
        assertTrue("竖屏不应仅凭尺寸判 game", "game" !in keys("", 1080, 2400))
    }

    @Test
    fun `接近方形的图不判横屏`() {
        // 1080x1200，比例 1.11 < 1.2
        assertTrue("近似方形不应判 game", "game" !in keys("", 1200, 1080))
    }

    // ─────────────────────────────────────────────
    // 二维码：49.3% vs <1% 的高区分度信号
    // ─────────────────────────────────────────────

    @Test
    fun `扫码文案判为二维码`() {
        assertEquals(setOf("qrcode"), keys("请使用微信扫码支付 ¥12.50"))
    }

    @Test
    fun `二维码三个字也判二维码`() {
        assertEquals(setOf("qrcode"), keys("二维码已过期，请刷新"))
    }

    // ─────────────────────────────────────────────
    // 订单
    // ─────────────────────────────────────────────

    @Test
    fun `订单号字段判为订单`() {
        assertEquals(setOf("order"), keys("订单号 123456789 待发货"))
    }

    @Test
    fun `订单加金额判为订单`() {
        assertEquals(setOf("order"), keys("我的订单 实付 ¥299.00"))
    }

    @Test
    fun `只有订单两字没有金额不判订单`() {
        // 避免"查看订单"这类按钮文案误判
        assertTrue("order" !in keys("查看订单 返回 首页"))
    }

    // ─────────────────────────────────────────────
    // 社交：需要多次互动词
    // ─────────────────────────────────────────────

    @Test
    fun `多处评论点赞判为社交`() {
        val t = "评论 12 点赞 34 回复 5 转发 2"
        assertEquals(setOf("social"), keys(t))
    }

    @Test
    fun `单次互动词不判社交`() {
        assertTrue("social" !in keys("点击评论"))
    }

    // ─────────────────────────────────────────────
    // 聊天：只认强证据
    // ─────────────────────────────────────────────

    @Test
    fun `已读撤回判为聊天`() {
        assertEquals(setOf("chat"), keys("你好 已读 撤回了一条消息"))
    }

    @Test
    fun `普通短句不判聊天`() {
        // 实测 chat 与 textshot 在短行统计上无法区分，不能硬猜
        assertTrue("chat" !in keys("今天天气不错\n我们出去走走吧"))
    }

    // ─────────────────────────────────────────────
    // 核心原则：不硬猜
    // ─────────────────────────────────────────────

    @Test
    fun `无特征文本返回空 不塞兜底分类`() {
        val hits = ScreenshotClassifierV2.classify("这是一段普通文字", 1080, 2400)
        assertTrue("无法判断时必须返回空列表，不能兜底成 app_ui/other", hits.isEmpty())
    }

    @Test
    fun `空白文本返回空`() {
        assertTrue(ScreenshotClassifierV2.classify("", 1080, 2400).isEmpty())
    }

    @Test
    fun `结果最多一个标签 避免标签泛滥`() {
        val hits = ScreenshotClassifierV2.classify(
            "订单号 123 扫码支付 评论 点赞 回复 已读",
            1080, 2400,
        )
        assertTrue("最多返回 1 个标签，实际 ${hits.size}", hits.size <= 1)
    }

    @Test
    fun `每条结果都有可读理由和置信度`() {
        val hits = ScreenshotClassifierV2.classify("扫码支付", 1080, 2400)
        assertTrue(hits.isNotEmpty())
        hits.forEach {
            assertTrue("理由不能为空", it.reason.isNotBlank())
            assertTrue("置信度必须在 0..1", it.confidence in 0f..1f)
        }
    }

    @Test
    fun `高置信结果标记为 HIGH tier`() {
        val hits = ScreenshotClassifierV2.classify("扫码", 1080, 2400)
        assertEquals(ScreenshotClassifierV2.Tier.HIGH, hits.first().tier)
    }

    @Test
    fun `竖屏游戏战绩词不判游戏`() {
        // 实测：竖屏 + 战绩词精度 0%，该规则已移除
        val hits = ScreenshotClassifierV2.classify("MVP 战绩 段位 排位赛", 1080, 2400)
        assertTrue("竖屏不能仅凭战绩词判游戏", hits.isEmpty())
    }
}