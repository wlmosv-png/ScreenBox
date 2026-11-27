package io.github.wlmosv.screenbox

import io.github.wlmosv.screenbox.data.db.TaskStateEntity
import io.github.wlmosv.screenbox.domain.query.SearchQueryParser
import io.github.wlmosv.screenbox.domain.query.ShotFilter
import io.github.wlmosv.screenbox.domain.query.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5 扩展测试：任务状态机 + 计数一致性 + 筛选边界。
 *
 * 这些是过去三轮真实踩过的坑，每条对应一个已修复的 bug。
 */
class TaskStateMachineTest {

    // ─────────────────────────────────────────────
    // 计数不变量（R3/R4 重复累加的教训）
    // ─────────────────────────────────────────────

    @Test
    fun `剩余数计算不会为负`() {
        val st = TaskStateEntity(
            kind = "test",
            totalCount = 10,
            doneCount = 10,
            failedCount = 0,
            skippedCount = 0,
        )
        assertEquals(0, st.remaining)
    }

    @Test
    fun `done 超过 total 时剩余数夹到 0 而不是负数`() {
        // R3 的 bug：累加导致 done=153810 而 total=159248 时剩余算错
        val st = TaskStateEntity(
            kind = "test",
            totalCount = 100,
            doneCount = 150,   // 脏数据
            failedCount = 0,
            skippedCount = 0,
        )
        assertEquals("剩余数不能为负", 0, st.remaining)
    }

    @Test
    fun `失败和跳过也计入已完成`() {
        val st = TaskStateEntity(
            kind = "test",
            totalCount = 10,
            doneCount = 6,
            failedCount = 2,
            skippedCount = 2,
        )
        assertEquals("6+2+2 = 10，剩余应为 0", 0, st.remaining)
    }

    @Test
    fun `部分完成时剩余数正确`() {
        val st = TaskStateEntity(
            kind = "test",
            totalCount = 100,
            doneCount = 60,
            failedCount = 10,
            skippedCount = 5,
        )
        assertEquals(25, st.remaining)
    }

    @Test
    fun `最后两张失败时任务仍能到达终态`() {
        // R4 的 bug：剩两张时算术值失真，永远不到 COMPLETED
        val st = TaskStateEntity(
            kind = "test",
            totalCount = 100,
            doneCount = 98,
            failedCount = 2,
            skippedCount = 0,
        )
        assertEquals("最后两张都失败，剩余应为 0", 0, st.remaining)
    }

    @Test
    fun `空任务剩余为 0`() {
        val st = TaskStateEntity(kind = "test", totalCount = 0)
        assertEquals(0, st.remaining)
    }

    // ─────────────────────────────────────────────
    // 筛选边界（全选必须覆盖完整结果）
    // ─────────────────────────────────────────────

    @Test
    fun `多分类筛选是 OR 语义`() {
        val f = ShotFilter(contentCategories = setOf("chat", "game"))
        assertEquals("两个分类应都保留", 2, f.contentCategories.size)
        assertFalse("有内容时不应为空筛选", f.isEmpty)
    }

    @Test
    fun `时间窗口不重叠`() {
        val now = 1_700_000_000_000L
        val recent = TimeWindow.lastDays(7, now)
        val older = TimeWindow.olderThan(7, now)
        assertNotNull(recent.fromMs)
        assertNull(recent.toMs)
        assertNull(older.fromMs)
        assertNotNull(older.toMs)
        // 最近7天的下界 = 7天前的上界
        assertEquals(recent.fromMs, older.toMs)
    }

    @Test
    fun `排除条件单独存在也非空`() {
        val q = SearchQueryParser.parse("-广告")
        assertNotNull("只有排除词也应有效", q)
        assertEquals(1, q!!.excludes.size)
        assertTrue(q.andGroups.isEmpty())
    }

    @Test
    fun `短语与关键词可组合`() {
        val q = SearchQueryParser.parse("\"订单已发货\" 微信")
        assertNotNull(q)
        assertEquals(1, q!!.phrases.size)
        assertEquals(1, q.andGroups.size)
    }

    @Test
    fun `OR 组内变体不重复`() {
        val q = SearchQueryParser.parse("测试 OR 测试")
        assertNotNull(q)
        // 变体应去重
        val group = q!!.andGroups.firstOrNull()
        assertNotNull(group)
        assertEquals("相同词不应重复展开", group!!.distinct().size, group.size)
    }

    // ─────────────────────────────────────────────
    // 保护优先级（删除安全的底线）
    // ─────────────────────────────────────────────

    @Test
    fun `保护状态枚举值齐全`() {
        val all = io.github.wlmosv.screenbox.data.db.ProtectionStatus.entries
        assertTrue("必须有 PROTECTED", all.any { it.name == "PROTECTED" })
        assertTrue("必须有 FAVORITE", all.any { it.name == "FAVORITE" })
        assertTrue("必须有 DELETED", all.any { it.name == "DELETED" })
        assertTrue("必须有 MISSING", all.any { it.name == "MISSING" })
    }

    @Test
    fun `OCR 状态包含 QUEUED（R4 死锁根因）`() {
        val all = io.github.wlmosv.screenbox.data.db.OcrStatus.entries.map { it.name }
        assertTrue("QUEUED 必须存在，否则取批查询会漏", all.contains("QUEUED"))
        assertTrue(all.contains("PROCESSING"))
        assertTrue(all.contains("NOT_STARTED"))
        assertTrue(all.contains("FAILED"))
    }

    // ─────────────────────────────────────────────
    // 分类不误判（R5 修复验证）
    // ─────────────────────────────────────────────

    @Test
    fun `桌面图标名不当游戏截图`() {
        // 旧版 GAME_RE 含"王者荣耀"，桌面截图会误判
        val hits = io.github.wlmosv.screenbox.domain.rules.ScreenshotClassifier
            .classify("文件管理 微信 王者荣耀 设置 相机 相册", 1080, 2376)
        assertTrue(
            "只有图标名的桌面不应判为游戏，实际 $hits",
            hits.none { it.categoryKey == "game" }
        )
    }

    @Test
    fun `战绩结构才判游戏`() {
        val hits = io.github.wlmosv.screenbox.domain.rules.ScreenshotClassifier
            .classify("主页 战绩 英雄 击败 10 死亡 2 助攻 8 胜利 MVP 排位赛", 1080, 2376)
        assertTrue(
            "有战绩结构应判为游戏，实际 $hits",
            hits.any { it.categoryKey == "game" }
        )
    }

    @Test
    fun `只有应用名不判聊天`() {
        // 旧版只要出现"微信"就加分
        val hits = io.github.wlmosv.screenbox.domain.rules.ScreenshotClassifier
            .classify("分享到微信 分享到 QQ 复制链接", 1080, 2376)
        assertTrue(
            "只有分享按钮不应判为聊天，实际 $hits",
            hits.none { it.categoryKey == "chat" }
        )
    }

    @Test
    fun `命令行判为代码`() {
        val hits = io.github.wlmosv.screenbox.domain.rules.ScreenshotClassifier
            .classify("sudo apt install\ncd /workspace\nls -la\nimport androidx.core", 1080, 2376)
        assertTrue(
            "命令行应判为代码，实际 $hits",
            hits.any { it.categoryKey == "code" }
        )
    }
}