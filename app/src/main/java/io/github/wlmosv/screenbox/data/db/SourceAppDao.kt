package io.github.wlmosv.screenbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 来源应用 DAO。
 *
 * 一张图只能有一个来源，screenshotId 唯一约束保证这点。
 * 用户手动确认的（isUserConfirmed=true）永不被自动任务覆盖。
 */
@Dao
interface SourceAppDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertApps(apps: List<SourceAppEntity>)

    @Query("SELECT * FROM source_apps ORDER BY sortOrder, id")
    suspend fun allNow(): List<SourceAppEntity>

    @Query("SELECT * FROM source_apps ORDER BY sortOrder, id")
    fun allFlow(): Flow<List<SourceAppEntity>>

    @Query("SELECT * FROM source_apps WHERE `key` = :key")
    suspend fun byKey(key: String): SourceAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: ScreenshotSourceEntity)

    @Query("SELECT * FROM screenshot_sources WHERE screenshotId = :id")
    suspend fun sourceOf(id: Long): ScreenshotSourceEntity?

    /** 批量取来源关联（删除清单分组统计用，避免 N+1）。 */
    @Query("SELECT * FROM screenshot_sources WHERE screenshotId IN (:ids)")
    suspend fun sourcesForIdsRaw(ids: List<Long>): List<ScreenshotSourceEntity>

    /** 分批版 sourcesForIds。 */
    suspend fun sourcesForIds(ids: List<Long>): List<ScreenshotSourceEntity> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<ScreenshotSourceEntity>(ids.size)
        ids.chunked(900).forEach { chunk -> out += sourcesForIdsRaw(chunk) }
        return out
    }

    /** 只覆盖自动结果，用户确认过的不动。 */
    @Query(
        """
        INSERT OR REPLACE INTO screenshot_sources
          (screenshotId, sourceAppId, confidence, evidence, isUserConfirmed, updatedAt)
        SELECT :screenshotId, :sourceAppId, :confidence, :evidence,
               COALESCE((SELECT isUserConfirmed FROM screenshot_sources WHERE screenshotId = :screenshotId), 0),
               :now
        WHERE COALESCE((SELECT isUserConfirmed FROM screenshot_sources WHERE screenshotId = :screenshotId), 0) = 0
        """
    )
    suspend fun upsertAuto(
        screenshotId: Long,
        sourceAppId: Long,
        confidence: Float,
        evidence: String,
        now: Long,
    )

    @Query("UPDATE screenshot_sources SET sourceAppId = :appId, isUserConfirmed = 1, updatedAt = :now WHERE screenshotId = :screenshotId")
    suspend fun setUserSource(screenshotId: Long, appId: Long, now: Long)

    /** 某来源下的图片 id 列表。 */
    @Query("SELECT screenshotId FROM screenshot_sources WHERE sourceAppId = :appId")
    suspend fun screenshotIdsOf(appId: Long): List<Long>

    /** 各来源的图片数量统计。 */
    @Query(
        """
        SELECT sa.`key` AS appKey, sa.displayName AS displayName, COUNT(ss.screenshotId) AS cnt
        FROM source_apps sa
        LEFT JOIN screenshot_sources ss ON ss.sourceAppId = sa.id
        GROUP BY sa.id
        HAVING cnt > 0
        ORDER BY cnt DESC
        """
    )
    suspend fun countByApp(): List<AppCount>

    @Query("SELECT COUNT(*) FROM screenshot_sources")
    suspend fun classifiedCount(): Int

    @Query("DELETE FROM screenshot_sources")
    suspend fun clearAllSources()

    /** 待识别来源的图片 id（还没被任何来源关联的）。 */
    @Query(
        """
        SELECT s.id FROM screenshots s
        LEFT JOIN screenshot_sources ss ON ss.screenshotId = s.id
        WHERE ss.screenshotId IS NULL
          AND s.ocrStatus IN ('SUCCESS','PARTIAL')
        LIMIT :limit
        """
    )
    suspend fun idsWithoutSource(limit: Int): List<Long>
}

data class AppCount(
    val appKey: String,
    val displayName: String,
    val cnt: Int,
)
