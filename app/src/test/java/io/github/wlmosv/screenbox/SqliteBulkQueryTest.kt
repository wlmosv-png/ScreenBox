package io.github.wlmosv.screenbox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 真实 SQLite 批量操作测试。
 *
 * ══ 为什么用 sqlite-jdbc 而不是 Robolectric ══
 *
 * Robolectric 4.13 依赖 conscrypt 的 x86_64 原生库，
 * 在 aarch64 设备（本项目构建环境）上直接 UnsatisfiedLinkError，跑不起来。
 *
 * 但这里要验证的核心是 **DAO 生成的 SQL 在真实数据量下的行为**，
 * 不是 Android 框架本身。所以直接用 sqlite-jdbc 建同构表，
 * 跑与 DAOs.kt 完全一致的 SQL，验证：
 *   1. IN (?) 在 8000+ 参数下是否正常
 *   2. 分批逻辑是否等价于一次性查询
 *   3. 保护项过滤是否严格
 *   4. 统计口径是否一致
 *
 * 局限（如实说明）：这不覆盖 Room 的代码生成，只覆盖 SQL 语义。
 * Room 生成的 SQL 由 KSP 编译期校验 + 真机测试覆盖。
 */
class SqliteBulkQueryTest {

    private lateinit var conn: Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            // 与 ScreenshotEntity 关键字段同构
            st.execute(
                """
                CREATE TABLE screenshots (
                  id INTEGER PRIMARY KEY,
                  mediaStoreId INTEGER NOT NULL,
                  contentUri TEXT NOT NULL,
                  fileSize INTEGER NOT NULL,
                  dateModified INTEGER NOT NULL,
                  ocrStatus TEXT NOT NULL,
                  protectionStatus TEXT NOT NULL,
                  isFavorite INTEGER NOT NULL DEFAULT 0,
                  screenshotConfidence TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    @After
    fun tearDown() {
        conn.close()
    }

    /** 与 DAO 完全一致的过滤条件。 */
    private val selectableSql = """
        SELECT id FROM screenshots
        WHERE id IN (%s)
          AND protectionStatus NOT IN ('PROTECTED','FAVORITE','DELETED','MISSING')
          AND isFavorite = 0
    """.trimIndent()

    private fun seed(count: Int) {
        conn.autoCommit = false
        conn.prepareStatement(
            "INSERT INTO screenshots(id,mediaStoreId,contentUri,fileSize,dateModified," +
                "ocrStatus,protectionStatus,isFavorite,screenshotConfidence) " +
                "VALUES(?,?,?,?,?,'SUCCESS',?,0,'HIGH')"
        ).use { ps ->
            for (i in 1..count) {
                ps.setLong(1, i.toLong())
                ps.setLong(2, i.toLong())
                ps.setString(3, "content://media/external/images/media/$i")
                ps.setLong(4, 100_000L)
                ps.setLong(5, 1_700_000_000_000L + i)
                // 每 500 张一个受保护项
                ps.setString(6, if (i % 500 == 0) "PROTECTED" else "NORMAL")
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.commit()
        conn.autoCommit = true
    }

    /** 一次性查（验证 SQLite 在真实规模下的变量上限行为）。 */
    private fun queryOnce(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val ph = ids.joinToString(",") { "?" }
        conn.prepareStatement(selectableSql.format(ph)).use { ps ->
            ids.forEachIndexed { i, id -> ps.setLong(i + 1, id) }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Long>()
                while (rs.next()) out += rs.getLong(1)
                return out
            }
        }
    }

    /** 分批查（与 DAOs.kt 的 SQL_CHUNK 逻辑一致）。 */
    private fun queryChunked(ids: List<Long>, chunk: Int = 900): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<Long>()
        ids.chunked(chunk).forEach { out += queryOnce(it) }
        return out
    }

    private fun allIds(n: Int): List<Long> = (1L..n.toLong()).toList()

    // ─────────────────────────────────────────────
    // 核心：大数据量 IN 查询
    // ─────────────────────────────────────────────

    @Test
    fun `8000 张一次性 IN 查询在 SQLite 3_46 上正常返回`() {
        seed(8000)
        val ids = allIds(8000)
        val result = queryOnce(ids)
        // 8000/500 = 16 张受保护
        assertEquals("应返回 7984 条（排除 16 张受保护）", 7984, result.size)
    }

    @Test
    fun `分批查询与一次性查询结果完全等价`() {
        seed(8000)
        val ids = allIds(8000)
        val once = queryOnce(ids).toSet()
        val chunked = queryChunked(ids).toSet()
        assertEquals("分批与一次性必须完全一致", once, chunked)
        assertEquals(7984, chunked.size)
    }

    @Test
    fun `保护项在所有规模下都被排除`() {
        seed(3000)
        for (n in listOf(500, 999, 1000, 2000, 3000)) {
            val result = queryOnce(allIds(n)).toSet()
            assertTrue("n=$n 时 500 不得被选中", 500L !in result)
            assertTrue("n=$n 时 1000 不得被选中", 1000L !in result)
            assertTrue("n=$n 时 1500 不得被选中", 1500L !in result)
        }
    }

    @Test
    fun `空列表返回空而不是报错`() {
        seed(100)
        assertEquals(0, queryOnce(emptyList()).size)
        assertEquals(0, queryChunked(emptyList()).size)
    }

    @Test
    fun `边界 900 与 901 分块正确`() {
        seed(1000)
        // 1..900 中含 id=500（受保护），故 899 条
        assertEquals("900 条单块（排除 1 张受保护）", 899, queryChunked(allIds(900)).size)
        // 901 = 900 + 1，第二块只有 1 条，受保护项仍是 500
        assertEquals("901 条跨块（排除 1 张受保护）", 900, queryChunked(allIds(901)).size)
    }

    // ─────────────────────────────────────────────
    // 统计口径
    // ─────────────────────────────────────────────

    @Test
    fun `countActive 排除已删除与丢失`() {
        conn.createStatement().use { st ->
            st.execute("INSERT INTO screenshots VALUES(1,1,'u1',100,0,'SUCCESS','NORMAL',0,'HIGH')")
            st.execute("INSERT INTO screenshots VALUES(2,2,'u2',100,0,'SUCCESS','DELETED',0,'HIGH')")
            st.execute("INSERT INTO screenshots VALUES(3,3,'u3',100,0,'SUCCESS','MISSING',0,'HIGH')")
            st.execute("INSERT INTO screenshots VALUES(4,4,'u4',100,0,'SUCCESS','NORMAL',0,'HIGH')")
        }
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT COUNT(*) FROM screenshots WHERE protectionStatus NOT IN ('DELETED','MISSING')"
            ).use { rs ->
                rs.next()
                assertEquals("有效数应为 2", 2, rs.getInt(1))
            }
        }
    }

    @Test
    fun `收藏项不被选中`() {
        conn.createStatement().use { st ->
            st.execute("INSERT INTO screenshots VALUES(1,1,'u1',100,0,'SUCCESS','NORMAL',1,'HIGH')")
            st.execute("INSERT INTO screenshots VALUES(2,2,'u2',100,0,'SUCCESS','NORMAL',0,'HIGH')")
        }
        val result = queryOnce(listOf(1, 2))
        assertEquals("只有 id=2", listOf(2L), result)
    }

    @Test
    fun `sumBytes 在大数据量下正确`() {
        seed(8000)
        val ids = allIds(8000)
        val total = ids.chunked(900).sumOf { chunk ->
            val ph = chunk.joinToString(",") { "?" }
            conn.prepareStatement(
                "SELECT COALESCE(SUM(fileSize),0) FROM screenshots WHERE id IN ($ph)"
            ).use { ps ->
                chunk.forEachIndexed { i, id -> ps.setLong(i + 1, id) }
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        assertEquals("8000 × 100000", 8000L * 100_000, total)
    }

    // ─────────────────────────────────────────────
    // 性能：给出真实耗时（不是"明显变快"）
    // ─────────────────────────────────────────────

    @Test
    fun `8000 张分批查询耗时在可接受范围`() {
        seed(8000)
        val ids = allIds(8000)

        // 预热
        queryChunked(ids)

        val t0 = System.nanoTime()
        repeat(3) { queryChunked(ids) }
        val avgMs = (System.nanoTime() - t0) / 3 / 1_000_000.0

        println("8000 张 filterSelectable 平均耗时: %.2f ms".format(avgMs))
        assertTrue("8000 张查询应在 500ms 内完成，实际 %.1fms".format(avgMs), avgMs < 500)
    }
}