package io.github.wlmosv.screenbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * SQLite 的绑定变量上限是 999（Android 默认编译值）。
 * 任何 `IN (:ids)` 查询都必须按这个粒度分批，否则会静默失败返回空结果。
 *
 * 这是 R6 实测踩到的 P0：全选 8137 张后选择栏直接消失，
 * 因为 filterSelectable 的 IN 列表超限，查询失败返回空。
 */
internal const val SQL_CHUNK = 900

@Dao
interface ScreenshotDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: ScreenshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ScreenshotEntity>)

    @Update
    suspend fun update(entity: ScreenshotEntity)

    @Query("SELECT * FROM screenshots WHERE id = :id")
    suspend fun byId(id: Long): ScreenshotEntity?

    @Query("SELECT * FROM screenshots WHERE contentUri = :uri LIMIT 1")
    suspend fun byUri(uri: String): ScreenshotEntity?

    @Query("SELECT * FROM screenshots WHERE mediaStoreId = :mediaStoreId LIMIT 1")
    suspend fun byMediaStoreId(mediaStoreId: Long): ScreenshotEntity?

    @Query("SELECT id, contentUri, dateModified, fileSize FROM screenshots")
    suspend fun allIdentityRows(): List<IdentityRow>

    @Query("SELECT * FROM screenshots ORDER BY dateModified DESC LIMIT :limit OFFSET :offset")
    fun paged(limit: Int, offset: Int): Flow<List<ScreenshotEntity>>

    /** 全部有效截图的 id（特征标签全量重建用）。 */
    @Query("SELECT id FROM screenshots WHERE protectionStatus NOT IN ('DELETED','MISSING')")
    suspend fun allActiveIds(): List<Long>

    /** 已有特征标签的图片数（判断是否需要首次全量回填）。 */
    @Query(
        """
        SELECT COUNT(DISTINCT st.screenshotId) FROM screenshot_tags st
        INNER JOIN tags t ON t.id = st.tagId
        WHERE t.`group` = 'FEATURE'
        """
    )
    suspend fun activeFeatureTagCount(): Int

    /** 已有内容分类的图片数（「已归类」的真实口径）。 */
    @Query(
        """
        SELECT COUNT(DISTINCT sc.screenshotId) FROM screenshot_categories sc
        INNER JOIN screenshots s ON s.id = sc.screenshotId
        WHERE s.protectionStatus NOT IN ('DELETED','MISSING')
        """
    )
    suspend fun categoryCoveredCount(): Int

    /** 还没有内容分类的有效图片数。 */
    @Query(
        """
        SELECT COUNT(*) FROM screenshots s
        WHERE s.protectionStatus NOT IN ('DELETED','MISSING')
          AND s.id NOT IN (SELECT screenshotId FROM screenshot_categories)
        """
    )
    suspend fun categoryMissingCount(): Int

    /**
     * 全应用统一口径：有效截图 = 排除 DELETED / MISSING。
     *
     * 为什么必须这样：
     * 之前首页用 COUNT(*) 含已删除，图库用排除后的数。
     * 用户删了 3 张，首页仍显示旧数字，会以为没删掉。
     * 现在所有统计都走这个口径。
     */
    @Query("SELECT COUNT(*) FROM screenshots WHERE protectionStatus NOT IN ('DELETED','MISSING')")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM screenshots WHERE protectionStatus NOT IN ('DELETED','MISSING')")
    suspend fun count(): Int

    @Query(
        """
        SELECT COUNT(*) FROM screenshots
        WHERE (ocrStatus = 'SUCCESS' OR ocrStatus = 'PARTIAL')
          AND protectionStatus NOT IN ('DELETED','MISSING')
        """
    )
    fun recognizedCountFlow(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM screenshots
        WHERE ocrStatus IN ('NOT_STARTED','QUEUED','FAILED')
          AND protectionStatus NOT IN ('DELETED','MISSING')
        """
    )
    fun pendingOcrCountFlow(): Flow<Int>

    @Query("SELECT COALESCE(SUM(fileSize),0) FROM screenshots WHERE protectionStatus NOT IN ('DELETED','MISSING')")
    fun totalBytesFlow(): Flow<Long>

    @Query(
        """
        SELECT * FROM screenshots
        WHERE (:query = '' OR displayName LIKE '%' || :query || '%')
        ORDER BY dateModified DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun pagedFiltered(query: String, limit: Int, offset: Int): Flow<List<ScreenshotEntity>>

    @Query("SELECT * FROM screenshots WHERE protectionStatus = :status ORDER BY dateModified DESC")
    fun byProtection(status: ProtectionStatus): Flow<List<ScreenshotEntity>>

    @Query("SELECT * FROM screenshots WHERE ocrStatus IN ('NOT_STARTED','FAILED') AND screenshotConfidence != 'LOW' LIMIT :limit")
    suspend fun nextOcrBatch(limit: Int): List<ScreenshotEntity>

    /**
     * 优先级队列取批。
     * 按 ocrPriority 升序，再按 dateModified 降序（新的优先）。
     * 排除重试次数过多的（>=3 视为永久失败，需用户手动重试）。
     */
    /**
     * 优先级队列取批。
     *
     * ══ 致命 bug 修复（R4）══
     *
     * 旧版只查 NOT_STARTED / FAILED，漏了 QUEUED。
     * 而 reprocess() 会把状态设成 QUEUED ——
     * 结果：一旦有图进入 QUEUED，就永远没人处理它，任务永久卡在"剩 N 张"。
     *
     * 这是"任务剩两张卡住"的直接原因。
     */
    @Query(
        """
        SELECT * FROM screenshots
        WHERE ocrStatus IN ('NOT_STARTED','QUEUED','FAILED')
          AND ocrAttempts < 3
          AND screenshotConfidence != 'LOW'
          AND protectionStatus NOT IN ('DELETED','MISSING')
        ORDER BY ocrPriority ASC, dateModified DESC
        LIMIT :limit
        """
    )
    suspend fun nextOcrBatchByPriority(limit: Int): List<ScreenshotEntity>

    @Query("UPDATE screenshots SET ocrPriority = :priority WHERE id = :id")
    suspend fun setOcrPriority(id: Long, priority: Int)

    /**
     * 按日期阈值批量回填优先级。
     * @param sinceMs 只有 dateModified >= sinceMs 的才设成 priority
     */
    @Query("UPDATE screenshots SET ocrPriority = :priority WHERE dateModified >= :sinceMs AND ocrStatus IN ('NOT_STARTED','FAILED')")
    suspend fun setOcrPriorityBulk(sinceMs: Long, priority: Int)

    @Query("UPDATE screenshots SET ocrAttempts = ocrAttempts + 1 WHERE id = :id")
    suspend fun bumpOcrAttempts(id: Long)

    @Query("SELECT COUNT(*) FROM screenshots WHERE ocrStatus IN ('NOT_STARTED','QUEUED','FAILED') AND ocrAttempts < 3 AND screenshotConfidence != 'LOW' AND protectionStatus NOT IN ('DELETED','MISSING')")
    suspend fun pendingOcrCount(): Int

    @Query("SELECT COUNT(*) FROM screenshots WHERE ocrStatus = 'FAILED'")
    suspend fun failedOcrCount(): Int

    @Query("SELECT COUNT(*) FROM screenshots WHERE ocrStatus IN ('SUCCESS','PARTIAL') AND protectionStatus NOT IN ('DELETED','MISSING')")
    suspend fun recognizedCount(): Int

    /** 最近完成 OCR 的几张，用于扫描过程可视化。 */
    @Query(
        """
        SELECT * FROM screenshots
        WHERE ocrStatus IN ('SUCCESS','PARTIAL')
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun recentlyProcessed(limit: Int): List<ScreenshotEntity>

    @Query("UPDATE screenshots SET ocrStatus = 'NOT_STARTED', ocrAttempts = 0 WHERE ocrStatus = 'FAILED'")
    suspend fun resetFailedOcr()

    /**
     * 进程被杀后，把残留的 PROCESSING 回退为 QUEUED。
     * 注意：回退到 QUEUED 而不是 NOT_STARTED，
     * 因为 QUEUED 表示"已排队但没做完"，优先级更高。
     * 现在 QUEUED 也会被 nextOcrBatchByPriority 捞出来，不会死锁。
     */
    @Query("UPDATE screenshots SET ocrStatus = 'QUEUED' WHERE ocrStatus = 'PROCESSING'")
    suspend fun resetStaleProcessing()

    @Query("SELECT * FROM screenshots WHERE sha256 IS NULL LIMIT :limit")
    suspend fun nextHashBatch(limit: Int): List<ScreenshotEntity>

    @Query("SELECT COUNT(*) FROM screenshots WHERE sha256 IS NULL")
    suspend fun countPendingHash(): Int

    @Query("SELECT * FROM screenshots WHERE perceptualHash IS NULL AND sha256 IS NOT NULL LIMIT :limit")
    suspend fun nextPhashBatch(limit: Int): List<ScreenshotEntity>

    @Query("UPDATE screenshots SET ocrStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun setOcrStatus(id: Long, status: OcrStatus, now: Long)

    @Query("UPDATE screenshots SET classifyStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun setClassifyStatus(id: Long, status: ClassifyStatus, now: Long)

    /** OCR 已完成（SUCCESS + PARTIAL）。 */
    @Query(
        """
        SELECT COUNT(*) FROM screenshots
        WHERE ocrStatus IN ('SUCCESS','PARTIAL')
          AND protectionStatus NOT IN ('DELETED','MISSING')
        """
    )
    suspend fun countOcrDone(): Int

    /** OCR 失败。 */
    @Query(
        """
        SELECT COUNT(*) FROM screenshots
        WHERE ocrStatus = 'FAILED'
          AND protectionStatus NOT IN ('DELETED','MISSING')
        """
    )
    suspend fun countOcrFailed(): Int


    @Query("SELECT COUNT(*) FROM screenshots WHERE protectionStatus = :status")
    fun countByProtectionFlow(status: ProtectionStatus): Flow<Int>

    @Query("SELECT COUNT(*) FROM screenshots WHERE fileSize >= :minBytes AND protectionStatus NOT IN ('DELETED','MISSING')")
    suspend fun countLargeFiles(minBytes: Long): Int

    /**
     * 从候选 id 里筛出"可被选中删除"的：
     * 排除 PROTECTED / FAVORITE / isFavorite / DELETED / MISSING。
     * 批量选择时必须过这一道，保证保护机制不被绕过。
     */
    @Query(
        """
        SELECT id FROM screenshots
        WHERE id IN (:ids)
          AND protectionStatus NOT IN ('PROTECTED','FAVORITE','DELETED','MISSING')
          AND isFavorite = 0
        """
    )
    suspend fun filterSelectableRaw(ids: List<Long>): List<Long>

    /**
     * 分批版 filterSelectable。
     *
     * ══ 为什么必须分批（R6 实测 P0）══
     *
     * SQLite 的绑定变量上限是 999。
     * 旧版直接把 8137 个 id 传进 `IN (:ids)`，查询直接失败返回空列表，
     * 于是"全选"变成"全不选"，选择栏消失，用户以为按钮坏了。
     * 这是静默失败 —— 没有任何报错，最难排查的一类。
     */
    suspend fun filterSelectable(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Long>(ids.size)
        ids.chunked(SQL_CHUNK).forEach { chunk -> out += filterSelectableRaw(chunk) }
        return out
    }

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM screenshots WHERE id IN (:ids)")
    suspend fun sumBytesRaw(ids: List<Long>): Long

    /** 分批版 sumBytes，同样受 SQLite 999 变量上限约束。 */
    suspend fun sumBytes(ids: List<Long>): Long {
        if (ids.isEmpty()) return 0L
        var total = 0L
        ids.chunked(SQL_CHUNK).forEach { chunk -> total += sumBytesRaw(chunk) }
        return total
    }

    /** 分页查询（真分页，用于截图页懒加载）。 */
    @Query("SELECT * FROM screenshots WHERE protectionStatus NOT IN ('DELETED','MISSING') ORDER BY dateModified DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ScreenshotEntity>

    @Query("SELECT COUNT(*) FROM screenshots WHERE protectionStatus NOT IN ('DELETED','MISSING')")
    suspend fun countActive(): Int

    @Query("SELECT * FROM screenshots WHERE protectionStatus = :status AND protectionStatus NOT IN ('DELETED','MISSING') ORDER BY dateModified DESC LIMIT :limit OFFSET :offset")
    suspend fun pageByProtection(status: ProtectionStatus, limit: Int, offset: Int): List<ScreenshotEntity>

    // ── 全选/反选需要"当前筛选条件下的全部 id"，不分页 ──

    @Query("SELECT id FROM screenshots WHERE protectionStatus = :status")
    suspend fun idsByProtection(status: ProtectionStatus): List<Long>

    @Query(
        "SELECT id FROM screenshots WHERE ocrStatus IN ('NOT_STARTED','QUEUED','FAILED') AND protectionStatus NOT IN ('DELETED','MISSING')"
    )
    suspend fun idsUnrecognized(): List<Long>

    @Query(
        "SELECT id FROM screenshots WHERE fileSize >= :minBytes AND protectionStatus NOT IN ('DELETED','MISSING')"
    )
    suspend fun idsLarge(minBytes: Long): List<Long>

    @Query("SELECT * FROM screenshots WHERE ocrStatus IN ('NOT_STARTED','QUEUED','FAILED') AND protectionStatus NOT IN ('DELETED','MISSING') ORDER BY dateModified DESC LIMIT :limit OFFSET :offset")
    suspend fun pageUnrecognized(limit: Int, offset: Int): List<ScreenshotEntity>

    @Query("SELECT * FROM screenshots WHERE fileSize >= :minBytes AND protectionStatus NOT IN ('DELETED','MISSING') ORDER BY fileSize DESC LIMIT :limit OFFSET :offset")
    suspend fun pageLarge(minBytes: Long, limit: Int, offset: Int): List<ScreenshotEntity>

    @Query("SELECT COUNT(*) FROM screenshots WHERE protectionStatus = :status")
    suspend fun countByProtection(status: ProtectionStatus): Int

    @Query("SELECT COUNT(*) FROM screenshots WHERE ocrStatus IN ('NOT_STARTED','QUEUED','FAILED') AND protectionStatus NOT IN ('DELETED','MISSING')")
    suspend fun countUnrecognized(): Int


    @Query("UPDATE screenshots SET sha256 = :sha, updatedAt = :now WHERE id = :id")
    suspend fun setSha256(id: Long, sha: String, now: Long)

    @Query("UPDATE screenshots SET perceptualHash = :phash, updatedAt = :now WHERE id = :id")
    suspend fun setPhash(id: Long, phash: String, now: Long)

    /** 待分析的图：缺指纹或缺熵值。 */
    @Query(
        """
        SELECT * FROM screenshots
        WHERE (sha256 IS NULL OR perceptualHash IS NULL OR imageEntropy IS NULL)
          AND protectionStatus NOT IN ('DELETED','MISSING')
          AND screenshotConfidence != 'LOW'
        LIMIT :limit
        """
    )
    suspend fun nextAnalysisBatch(limit: Int): List<ScreenshotEntity>

    @Query(
        """
        SELECT COUNT(*) FROM screenshots
        WHERE (sha256 IS NULL OR perceptualHash IS NULL OR imageEntropy IS NULL)
          AND protectionStatus NOT IN ('DELETED','MISSING')
          AND screenshotConfidence != 'LOW'
        """
    )
    suspend fun countPendingAnalysis(): Int

    @Query("UPDATE screenshots SET imageEntropy = :entropy, colorVariance = :variance, updatedAt = :now WHERE id = :id")
    suspend fun setImageComplexity(id: Long, entropy: Float, variance: Float, now: Long)

    /** 已完成分析（有有效熵值）。 */
    @Query(
        """
        SELECT COUNT(*) FROM screenshots
        WHERE imageEntropy IS NOT NULL AND imageEntropy >= 0
          AND protectionStatus NOT IN ('DELETED','MISSING')
        """
    )
    suspend fun countAnalyzed(): Int

    /** 分析失败（熵值标记为 -1，通常是解码失败）。 */
    @Query(
        """
        SELECT COUNT(*) FROM screenshots
        WHERE imageEntropy IS NOT NULL AND imageEntropy < 0
          AND protectionStatus NOT IN ('DELETED','MISSING')
        """
    )
    suspend fun countAnalysisFailed(): Int



    @Query("UPDATE screenshots SET protectionStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun setProtection(id: Long, status: ProtectionStatus, now: Long)

    @Query("UPDATE screenshots SET isFavorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: Long)

    @Query("UPDATE screenshots SET lastViewedAt = :at WHERE id = :id")
    suspend fun touchViewed(id: Long, at: Long)

    @Query("UPDATE screenshots SET userCategoryId = :categoryId, updatedAt = :now WHERE id = :id")
    suspend fun setUserCategory(id: Long, categoryId: Long?, now: Long)

    @Query("DELETE FROM screenshots WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM screenshots WHERE id IN (:ids)")
    suspend fun byIdsRaw(ids: List<Long>): List<ScreenshotEntity>

    /** 分批版 byIds。8136 个 id 一次性传入会超 SQLite 变量上限并返回空。 */
    suspend fun byIds(ids: List<Long>): List<ScreenshotEntity> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<ScreenshotEntity>(ids.size)
        ids.chunked(SQL_CHUNK).forEach { chunk -> out += byIdsRaw(chunk) }
        return out
    }

    @Query("SELECT DISTINCT relativePath FROM screenshots")
    suspend fun distinctPaths(): List<String>

    @Query("SELECT COUNT(*) FROM screenshots WHERE relativePath LIKE :prefix || '%'")
    suspend fun countUnderPath(prefix: String): Int

    @Query("SELECT * FROM screenshots WHERE dateModified >= :since ORDER BY dateModified DESC")
    suspend fun modifiedSince(since: Long): List<ScreenshotEntity>

    @Query("SELECT * FROM screenshots WHERE protectionStatus IN ('FAVORITE','PROTECTED')")
    suspend fun protectedAll(): List<ScreenshotEntity>

    @Query("SELECT * FROM screenshots WHERE screenshotConfidence != 'LOW' ORDER BY dateModified DESC")
    suspend fun allScannable(): List<ScreenshotEntity>

    @Transaction
    suspend fun markMissing(ids: List<Long>, now: Long) {
        for (id in ids) setProtection(id, ProtectionStatus.MISSING, now)
    }
}

data class IdentityRow(
    val id: Long,
    val contentUri: String,
    val dateModified: Long,
    val fileSize: Long,
)

@Dao
interface OcrDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OcrEntity)

    @Query("SELECT * FROM ocr WHERE screenshotId = :id")
    suspend fun byScreenshot(id: Long): OcrEntity?

    @Query("DELETE FROM ocr WHERE screenshotId = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(fts: OcrFtsEntity)

    @Query("DELETE FROM ocr_fts WHERE screenshotId = :id")
    suspend fun deleteFts(id: Long)

    /**
     * 中文用子串匹配而不是 FTS 分词。
     * FTS4 的 simple/pinyin 分词器都不做中文切词，直接 MATCH 会漏。
     * 这里用 instr() 做子串检索，配合索引表规模（万级）实测可接受。
     */
    @Query(
        """
        SELECT screenshotId FROM ocr_fts
        WHERE instr(lower(text), lower(:keyword)) > 0
        ORDER BY screenshotId DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchSubstring(keyword: String, limit: Int, offset: Int): List<Long>

    @Query(
        """
        SELECT COUNT(*) FROM ocr_fts
        WHERE instr(lower(text), lower(:keyword)) > 0
        """
    )
    suspend fun countSubstring(keyword: String): Int

    /** 有识别文本的图片 id（重分类的目标集）。 */
    @Query("SELECT screenshotId FROM ocr WHERE recognizedText != '' AND recognitionStatus IN ('SUCCESS','PARTIAL')")
    suspend fun screenshotIdsWithText(): List<Long>

    /** 批量取文本，避免逐条查询。 */
    @Query("SELECT screenshotId, recognizedText AS text FROM ocr WHERE screenshotId IN (:ids)")
    suspend fun textsOfRaw(ids: List<Long>): List<IdText>

    /** 分批版 textsOf。 */
    suspend fun textsOf(ids: List<Long>): List<IdText> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<IdText>(ids.size)
        ids.chunked(SQL_CHUNK).forEach { chunk -> out += textsOfRaw(chunk) }
        return out
    }

    @Query("SELECT COUNT(*) FROM ocr WHERE recognizedText != ''")
    suspend fun countWithText(): Int

    /** 写入行级坐标（R8）。 */
    @Query("UPDATE ocr SET lineBounds = :json WHERE screenshotId = :id")
    suspend fun setLineBounds(id: Long, json: String)

    /** 还没有行级坐标的截图 id + uri。 */
    @Query(
        """
        SELECT s.id AS id, s.contentUri AS uri FROM screenshots s
        INNER JOIN ocr o ON o.screenshotId = s.id
        WHERE o.lineBounds IS NULL
          AND s.protectionStatus NOT IN ('DELETED','MISSING')
        LIMIT :limit
        """
    )
    suspend fun idsMissingLineBounds(limit: Int): List<IdUri>

    /**
     * 已成功回填的条数。
     *
     * 注意排除空串：空串是「尝试过但读取失败」的哨兵值，
     * 与 NULL（还没做过）必须分开，否则失败图会被无限重试。
     */
    @Query("SELECT COUNT(*) FROM ocr WHERE lineBounds IS NOT NULL AND lineBounds != ''")
    suspend fun countWithLineBounds(): Int

    /** 尝试过但读取失败的条数（哨兵值空串）。 */
    @Query("SELECT COUNT(*) FROM ocr WHERE lineBounds = ''")
    suspend fun countFailedLineBounds(): Int

    /** 把失败项重置为未处理，供用户重试（例如补授权之后）。 */
    @Query("UPDATE ocr SET lineBounds = NULL WHERE lineBounds = ''")
    suspend fun resetFailedLineBounds(): Int

    /** 有 OCR 记录但仍缺行级坐标的条数（与回填目标集同一口径）。 */
    @Query(
        """
        SELECT COUNT(*) FROM ocr o
        INNER JOIN screenshots s ON s.id = o.screenshotId
        WHERE o.lineBounds IS NULL
          AND s.protectionStatus NOT IN ('DELETED','MISSING')
        """
    )
    suspend fun countMissingLineBounds(): Int
}

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<CategoryEntity>)

    @Query("SELECT * FROM categories ORDER BY sortOrder")
    fun all(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder")
    suspend fun allNow(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE `key` = :key LIMIT 1")
    suspend fun byKey(key: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelation(rel: ScreenshotCategoryEntity)

    @Query("DELETE FROM screenshot_categories WHERE screenshotId = :id AND source = 'auto'")
    suspend fun clearAutoRelations(id: Long)

    /** 清自动分类，但保留用户手动确认的。 */
    @Query("DELETE FROM screenshot_categories WHERE screenshotId = :id AND source = 'auto' AND isUserConfirmed = 0")
    suspend fun clearAutoRelationsKeepUser(id: Long)

    /** 按分类统计图片数（只算未删除的）。 */
    @Query(
        """
        SELECT c.`key` AS catKey, c.displayName AS displayName, COUNT(sc.screenshotId) AS cnt
        FROM categories c
        LEFT JOIN screenshot_categories sc ON sc.categoryId = c.id
        LEFT JOIN screenshots s ON s.id = sc.screenshotId
        WHERE s.protectionStatus IS NULL OR s.protectionStatus NOT IN ('DELETED','MISSING')
        GROUP BY c.id
        HAVING cnt > 0
        ORDER BY cnt DESC
        """
    )
    suspend fun countByCategory(): List<CatCount>

    @Query("SELECT * FROM screenshot_categories WHERE screenshotId = :id")
    suspend fun relationsFor(id: Long): List<ScreenshotCategoryEntity>

    /** 批量取分类关联（删除清单分组统计用，避免 N+1）。 */
    @Query("SELECT * FROM screenshot_categories WHERE screenshotId IN (:ids)")
    suspend fun relationsForIdsRaw(ids: List<Long>): List<ScreenshotCategoryEntity>

    /** 分批版 relationsForIds。 */
    suspend fun relationsForIds(ids: List<Long>): List<ScreenshotCategoryEntity> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<ScreenshotCategoryEntity>(ids.size)
        ids.chunked(SQL_CHUNK).forEach { chunk -> out += relationsForIdsRaw(chunk) }
        return out
    }

    /** 某分类下的全部图片 id。 */
    @Query("SELECT screenshotId FROM screenshot_categories WHERE categoryId = :categoryId")
    suspend fun screenshotIdsOfCategory(categoryId: Long): List<Long>

    /**
     * 按 OCR 文本模糊匹配图片 id。
     * 阶段 A 用于"按应用整理"的粗筛，阶段 B 会换成结构化来源识别。
     */
    @Query("SELECT DISTINCT screenshotId FROM ocr_fts WHERE instr(lower(text), lower(:needle)) > 0 LIMIT 5000")
    suspend fun screenshotIdsMatchingText(needle: String): List<Long>
}

@Dao
interface DuplicateDao {

    @Insert
    suspend fun insertGroup(group: DuplicateGroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<DuplicateMemberEntity>)

    @Query("DELETE FROM duplicate_groups")
    suspend fun clearAllGroups()

    @Query("SELECT * FROM duplicate_groups WHERE dismissed = 0")
    suspend fun activeGroups(): List<DuplicateGroupEntity>

    @Query("SELECT * FROM duplicate_groups WHERE dismissed = 0 ORDER BY groupType, id")
    fun activeGroupsFlow(): Flow<List<DuplicateGroupEntity>>

    @Query("SELECT COUNT(*) FROM duplicate_groups WHERE dismissed = 0 AND groupType = :type")
    fun countByTypeFlow(type: DuplicateGroupType): Flow<Int>

    @Query("SELECT * FROM duplicate_members WHERE groupId = :groupId")
    suspend fun membersOf(groupId: Long): List<DuplicateMemberEntity>

    @Query("SELECT * FROM duplicate_members WHERE screenshotId = :id")
    suspend fun groupsOfScreenshot(id: Long): List<DuplicateMemberEntity>

    @Query("SELECT * FROM duplicate_groups WHERE id = :groupId")
    suspend fun groupById(groupId: Long): DuplicateGroupEntity?

    @Query("UPDATE duplicate_groups SET dismissed = 1, updatedAt = :now WHERE id = :groupId")
    suspend fun dismiss(groupId: Long, now: Long)

    @Query("SELECT DISTINCT groupId FROM duplicate_members")
    suspend fun allGroupIds(): List<Long>

    /**
     * 完全重复组里"多出来的文件数"。
     * 口径：每组保留 1 张，其余都是多余的。
     * = 组内成员总数 - 组数
     */
    @Query(
        """
        SELECT COALESCE(SUM(cnt - 1), 0) FROM (
          SELECT COUNT(*) AS cnt FROM duplicate_members dm
          INNER JOIN duplicate_groups dg ON dg.id = dm.groupId
          WHERE dg.groupType = 'EXACT' AND dg.dismissed = 0
          GROUP BY dm.groupId
        )
        """
    )
    suspend fun exactExtraFileCount(): Int

    /**
     * 完全重复可释放空间。
     * 口径：每组按"保留最大的一张"，其余文件大小之和。
     * 这是保守估算，不包含相似组（相似不等于重复，不能算作可释放）。
     */
    @Query(
        """
        SELECT COALESCE(SUM(total - biggest), 0) FROM (
          SELECT SUM(s.fileSize) AS total, MAX(s.fileSize) AS biggest
          FROM duplicate_members dm
          INNER JOIN duplicate_groups dg ON dg.id = dm.groupId
          INNER JOIN screenshots s ON s.id = dm.screenshotId
          WHERE dg.groupType = 'EXACT' AND dg.dismissed = 0
            AND s.protectionStatus NOT IN ('PROTECTED','FAVORITE','DELETED','MISSING')
          GROUP BY dm.groupId
          HAVING COUNT(*) >= 2
        )
        """
    )
    suspend fun exactReclaimableBytes(): Long
}

@Dao
interface CleanupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSuggestion(s: CleanupSuggestionEntity): Long

    @Query("DELETE FROM cleanup_suggestions WHERE status = 'OPEN'")
    suspend fun clearOpenSuggestions()

    @Query("SELECT COUNT(*) FROM cleanup_suggestions WHERE status = 'OPEN'")
    fun openCountFlow(): Flow<Int>

    @Query(
        """
        SELECT s.* FROM cleanup_suggestions s
        INNER JOIN screenshots sc ON sc.id = s.screenshotId
        WHERE s.status = 'OPEN'
          AND sc.protectionStatus NOT IN ('PROTECTED','FAVORITE','DELETED','MISSING')
        ORDER BY s.riskLevel DESC, s.confidence DESC
        """
    )
    fun openSuggestionsFlow(): Flow<List<CleanupSuggestionEntity>>

    @Query("UPDATE cleanup_suggestions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: SuggestionStatus)

    @Query("SELECT * FROM cleanup_suggestions WHERE screenshotId = :screenshotId AND status = 'OPEN'")
    suspend fun openFor(screenshotId: Long): List<CleanupSuggestionEntity>

    @Query("SELECT * FROM cleanup_suggestions WHERE suggestionType = :type AND status = 'OPEN'")
    suspend fun openForType(type: SuggestionType): List<CleanupSuggestionEntity>

    @Query("SELECT * FROM cleanup_suggestions WHERE status = 'OPEN'")
    suspend fun allOpen(): List<CleanupSuggestionEntity>

    /** 把某张图的所有 OPEN 建议标记为已解决。保护/收藏时调用。 */
    @Query("UPDATE cleanup_suggestions SET status = 'RESOLVED' WHERE screenshotId = :screenshotId AND status = 'OPEN'")
    suspend fun resolveOpenFor(screenshotId: Long)

    /**
     * 双保险：把所有属于"受保护/收藏"图片的 OPEN 建议一次性标记为已解决。
     * 重建建议时调用，防止历史遗留数据造成统计虚高。
     */
    @Query(
        """
        UPDATE cleanup_suggestions SET status = 'RESOLVED'
        WHERE status = 'OPEN' AND screenshotId IN (
          SELECT id FROM screenshots
          WHERE protectionStatus IN ('PROTECTED','FAVORITE') OR isFavorite = 1
        )
        """
    )
    suspend fun resolveOpenForProtected(): Int

    @Query(
        """
        SELECT s.* FROM cleanup_suggestions s
        INNER JOIN screenshots sc ON sc.id = s.screenshotId
        WHERE s.status = 'OPEN' AND s.suggestionType = :type
          AND sc.protectionStatus NOT IN ('PROTECTED','FAVORITE','DELETED','MISSING')
        """
    )
    suspend fun openForTypeSafe(type: SuggestionType): List<CleanupSuggestionEntity>

    @Insert
    suspend fun insertOperation(op: CleanupOperationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperationItems(items: List<CleanupOperationItemEntity>)

    @Query("UPDATE cleanup_operations SET status = :status, completedAt = :at, successCount = :ok, failureCount = :fail, freedBytes = :freed WHERE id = :id")
    suspend fun finishOperation(id: Long, status: OperationStatus, at: Long, ok: Int, fail: Int, freed: Long)

    @Query("UPDATE cleanup_operation_items SET result = :result, errorMessage = :error WHERE operationId = :opId AND screenshotId = :shotId")
    suspend fun setItemResult(opId: Long, shotId: Long, result: String, error: String?)

    @Query("SELECT * FROM cleanup_operations ORDER BY createdAt DESC LIMIT :limit")
    fun recentOperations(limit: Int): Flow<List<CleanupOperationEntity>>

    @Query("SELECT * FROM cleanup_operation_items WHERE operationId = :opId")
    suspend fun itemsOf(opId: Long): List<CleanupOperationItemEntity>
}

@Dao
interface ScanStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: ScanStateEntity)

    @Query("SELECT * FROM scan_state WHERE id = 1")
    fun flow(): Flow<ScanStateEntity?>

    @Query("SELECT * FROM scan_state WHERE id = 1")
    suspend fun get(): ScanStateEntity?
}


@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<TagEntity>)

    @Query("SELECT * FROM tags ORDER BY `group`, sortOrder, name")
    fun all(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY `group`, sortOrder, name")
    suspend fun allNow(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelation(rel: ScreenshotTagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelations(list: List<ScreenshotTagEntity>)

    /** 清掉某张图的自动特征标签（系统标签），保留用户标签。 */
    @Query(
        """
        DELETE FROM screenshot_tags WHERE screenshotId = :id
          AND tagId IN (SELECT id FROM tags WHERE `group` = 'FEATURE')
        """
    )
    suspend fun clearFeatureTags(id: Long)

    @Query("DELETE FROM screenshot_tags WHERE screenshotId = :id")
    suspend fun clearAllTags(id: Long)

    @Query("SELECT tagId FROM screenshot_tags WHERE screenshotId = :id")
    suspend fun tagIdsOf(id: Long): List<Long>

    @Query(
        """
        SELECT t.`name` AS name, t.displayName AS displayName, COUNT(st.screenshotId) AS cnt
        FROM tags t
        LEFT JOIN screenshot_tags st ON st.tagId = t.id
        LEFT JOIN screenshots s ON s.id = st.screenshotId
        WHERE s.protectionStatus IS NULL OR s.protectionStatus NOT IN ('DELETED','MISSING')
        GROUP BY t.id
        HAVING cnt > 0
        ORDER BY cnt DESC
        """
    )
    suspend fun countByTag(): List<TagCount>

    /** 还没有特征标签的图（用于增量补充）。 */
    @Query(
        """
        SELECT s.id FROM screenshots s
        WHERE s.protectionStatus NOT IN ('DELETED','MISSING')
          AND NOT EXISTS (
            SELECT 1 FROM screenshot_tags st
            INNER JOIN tags t ON t.id = st.tagId
            WHERE st.screenshotId = s.id AND t.`group` = 'FEATURE'
          )
        LIMIT :limit
        """
    )
    suspend fun idsWithoutFeatureTags(limit: Int): List<Long>

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun count(): Int
}

data class TagCount(
    val name: String,
    val displayName: String,
    val cnt: Int,
)

data class IdUri(
    val id: Long,
    val uri: String,
)

data class IdText(
    val screenshotId: Long,
    val text: String,
)

data class CatCount(
    val catKey: String,
    val displayName: String,
    val cnt: Int,
)
