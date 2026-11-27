package io.github.wlmosv.screenbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 结构化实体 DAO。
 *
 * 实体由 OCR 完成后写入，一张图可以有多个同类实体。
 * 删除图片时由外键 CASCADE 自动清理。
 */
@Dao
interface StructuredEntityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<StructuredEntityEntity>)

    @Query("SELECT * FROM structured_entities WHERE screenshotId = :id ORDER BY startPos")
    suspend fun ofScreenshot(id: Long): List<StructuredEntityEntity>

    @Query("SELECT * FROM structured_entities WHERE screenshotId IN (:ids) ORDER BY screenshotId, startPos")
    suspend fun forIdsRaw(ids: List<Long>): List<StructuredEntityEntity>

    /** 批量版 forIds，避免搜索结果页 N+1。 */
    suspend fun forIds(ids: List<Long>): List<StructuredEntityEntity> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<StructuredEntityEntity>(ids.size)
        ids.chunked(900).forEach { chunk -> out += forIdsRaw(chunk) }
        return out
    }

    @Query("DELETE FROM structured_entities WHERE screenshotId = :id")
    suspend fun clearFor(id: Long)

    /**
     * 按实体类型和归一化值查图片 id。
     * 用于"搜 2025-03-15"或"搜订单号"这类结构化检索。
     */
    @Query(
        """
        SELECT DISTINCT screenshotId FROM structured_entities
        WHERE kind = :kind AND instr(lower(normalized), lower(:value)) > 0
        LIMIT :limit
        """
    )
    suspend fun idsByEntity(kind: String, value: String, limit: Int = 500): List<Long>

    /** 跨类型模糊查（用户不知道是日期还是订单号时）。 */
    @Query(
        """
        SELECT DISTINCT screenshotId FROM structured_entities
        WHERE instr(lower(normalized), lower(:value)) > 0
        LIMIT :limit
        """
    )
    suspend fun idsByAnyEntity(value: String, limit: Int = 500): List<Long>

    /** 各类型的实体数量统计，用于首页/审计。 */
    @Query("SELECT kind, COUNT(*) AS cnt FROM structured_entities GROUP BY kind ORDER BY cnt DESC")
    suspend fun countByKind(): List<KindCount>

    @Query("SELECT COUNT(DISTINCT screenshotId) FROM structured_entities")
    suspend fun screenshotsWithEntities(): Int

    /** 还没做过实体提取的图（有 OCR 文本但没实体记录）。 */
    @Query(
        """
        SELECT o.screenshotId FROM ocr o
        LEFT JOIN structured_entities se ON se.screenshotId = o.screenshotId
        WHERE o.recognizedText != '' AND se.id IS NULL
        LIMIT :limit
        """
    )
    suspend fun idsWithoutEntities(limit: Int): List<Long>
}

data class KindCount(
    val kind: String,
    val cnt: Int,
)
