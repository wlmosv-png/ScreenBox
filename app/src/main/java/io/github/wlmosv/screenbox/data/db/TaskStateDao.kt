package io.github.wlmosv.screenbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 任务状态 DAO。
 *
 * 界面只读这张表，Worker 只写这张表。
 * 这是 OCR 进度唯一可信来源。
 */
@Dao
interface TaskStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: TaskStateEntity)

    @Query("SELECT * FROM task_state WHERE kind = :kind")
    suspend fun get(kind: String): TaskStateEntity?

    @Query("SELECT * FROM task_state WHERE kind = :kind")
    fun flow(kind: String): Flow<TaskStateEntity?>

    /**
     * 只更新计数，不碰 status。用于高频进度写入。
     *
     * ══ 不变量保护（R4）══
     *
     * done + failed + skipped 永远不能超过 totalCount。
     * 用 SQL 层的 MIN() 硬性夹紧，任何调用方算错都不会污染数据。
     * 这是防御性设计 —— 正常路径不会触发，但一旦触发就是 bug 信号。
     */
    @Query(
        """
        UPDATE task_state
        SET doneCount = MIN(MAX(:done, 0), MAX(totalCount - :failed - :skipped, 0)),
            failedCount = MIN(MAX(:failed, 0), MAX(totalCount - :done - :skipped, 0)),
            skippedCount = MIN(MAX(:skipped, 0), MAX(totalCount - :done - :failed, 0)),
            updatedAt = :now
        WHERE kind = :kind
        """
    )
    suspend fun updateCounts(kind: String, done: Int, failed: Int, skipped: Int, now: Long)

    @Query("UPDATE task_state SET status = :status, updatedAt = :now WHERE kind = :kind")
    suspend fun updateStatus(kind: String, status: String, now: Long)

    /** 任务中途扩容：新截图进来后分母变大，显式记录时间点。 */
    @Query(
        """
        UPDATE task_state
        SET totalCount = :total, setExpandedAt = :now, updatedAt = :now
        WHERE kind = :kind
        """
    )
    suspend fun expandTotal(kind: String, total: Int, now: Long)

    @Query("UPDATE task_state SET lastError = :error, updatedAt = :now WHERE kind = :kind")
    suspend fun setError(kind: String, error: String?, now: Long)

    /** 进程重启后，把残留的 RUNNING/STOPPING 状态校正为 PAUSED，等待用户继续。 */
    @Query(
        """
        UPDATE task_state
        SET status = 'PAUSED', updatedAt = :now
        WHERE status IN ('RUNNING','STOPPING')
        """
    )
    suspend fun resetStaleRunning(now: Long)

    /**
     * 校正"剩余为 0 却仍显示未完成"的任务。
     *
     * ══ 真实 bug（R5 实测）══
     *
     * 首页显示「分析图片（已暂停）8136 / 8137」，但 remaining = 0
     * （8137 - 8136 - 1 失败 = 0）。任务其实已经做完了，
     * 状态却停在 PAUSED，用户看到"继续"按钮点了也没反应。
     *
     * 根因：publishProgress 在最后一轮用的是上一轮算出的剩余，
     * 落库时机与真实状态错开一帧，之后再没人刷新。
     *
     * 修正：每次启动把"算术上已完成"的任务校正为 COMPLETED。
     * 条件是 remaining=0 且 status 不是终态。
     */
    @Query(
        """
        UPDATE task_state
        SET status = 'COMPLETED', updatedAt = :now
        WHERE totalCount > 0
          AND (totalCount - doneCount - failedCount - skippedCount) <= 0
          AND status NOT IN ('COMPLETED','IDLE')
        """
    )
    suspend fun reconcileCompleted(now: Long): Int
}

@Dao
interface SavedFilterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(filter: SavedFilterEntity): Long

    @Query("SELECT * FROM saved_filters ORDER BY createdAt DESC")
    fun all(): Flow<List<SavedFilterEntity>>

    @Query("SELECT * FROM saved_filters ORDER BY createdAt DESC")
    suspend fun allNow(): List<SavedFilterEntity>

    @Query("DELETE FROM saved_filters WHERE id = :id")
    suspend fun delete(id: Long)

    /** 同名覆盖：保存同一组条件两次不会在列表里出现两条。 */
    @Query("DELETE FROM saved_filters WHERE name = :name")
    suspend fun deleteByName(name: String)
}

@Dao
interface SearchHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY lastUsedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SearchHistoryEntity>

    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    suspend fun delete(keyword: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun count(): Int
}
