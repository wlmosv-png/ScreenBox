package io.github.wlmosv.screenbox.data.repo

import androidx.room.withTransaction
import io.github.wlmosv.screenbox.data.db.FeatureTag
import io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase
import io.github.wlmosv.screenbox.data.db.ScreenshotTagEntity
import io.github.wlmosv.screenbox.domain.rules.FeatureTagEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 特征标签仓库。
 *
 * ══ 职责 ══
 *
 * 1. 把 FeatureTagEngine 的计算结果落库（screenshot_tags）
 * 2. 批量重建全部标签（换规则后跑一次）
 * 3. 增量补齐还没有标签的图
 * 4. 给 UI 提供统计
 *
 * ══ 为什么不重算 OCR ══
 *
 * 标签只读数据库已有字段（熵、尺寸、体积、OCR 文本、重复组），
 * 不需要重新解码图片、更不需要重跑 OCR。
 * 8100 张全量重建实测在秒级完成。
 *
 * ══ 为什么先清后写 ══
 *
 * 特征标签是**派生数据**，不是用户输入。
 * 规则变了就必须整体替换，否则新旧标签混杂，筛选结果不可信。
 * 用户标签（group=CUSTOM）不受影响。
 */
class TagRepository(private val db: ScreenBoxDatabase) {

    private val tagDao = db.tagDao()
    private val shotDao = db.screenshotDao()
    private val ocrDao = db.ocrDao()
    private val dupDao = db.duplicateDao()

    /** 特征标签 key → tagId 的缓存。 */
    private suspend fun tagIdMap(): Map<FeatureTag, Long> {
        val all = tagDao.allNow().associateBy { it.name }
        return FeatureTag.entries.mapNotNull { tag ->
            all[tag.key]?.let { tag to it.id }
        }.toMap()
    }

    /**
     * 全量重建特征标签。
     *
     * @param onProgress (done, total)
     */
    suspend fun rebuildAll(
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val ids = shotDao.allActiveIds()
        val total = ids.size
        if (total == 0) return@withContext

        val idMap = tagIdMap()
        if (idMap.isEmpty()) return@withContext

        var done = 0
        ids.chunked(BATCH).forEach { chunk ->
            coroutineContext.ensureActive()
            applyBatch(chunk, idMap)
            done += chunk.size
            onProgress?.invoke(done, total)
        }
    }

    /** 增量：只处理还没有特征标签的图。 */
    suspend fun rebuildIncremental(limit: Int = 5000) = withContext(Dispatchers.IO) {
        val idMap = tagIdMap()
        if (idMap.isEmpty()) return@withContext
        val ids = tagDao.idsWithoutFeatureTags(limit)
        ids.chunked(BATCH).forEach { chunk ->
            coroutineContext.ensureActive()
            applyBatch(chunk, idMap)
        }
    }

    /** 单张图重算（详情页/分析完成后调用）。 */
    suspend fun refreshOne(screenshotId: Long) = withContext(Dispatchers.IO) {
        val idMap = tagIdMap()
        if (idMap.isEmpty()) return@withContext
        applyBatch(listOf(screenshotId), idMap)
    }

    /**
     * 对一批图执行计算并落库。
     *
     * 一次批量取 OCR 文本、重复组成员，避免 N+1。
     */
    private suspend fun applyBatch(ids: List<Long>, idMap: Map<FeatureTag, Long>) {
        val shots = shotDao.byIds(ids)
        if (shots.isEmpty()) return

        val texts = ocrDao.textsOf(ids).associate { it.screenshotId to it.text }

        // 每张图所属的重复组类型。一张图可能同时在 EXACT 和 VISUAL 组里，取更强的 EXACT。
        val dupType = mutableMapOf<Long, String>()
        for (id in ids) {
            val groups = dupDao.groupsOfScreenshot(id)
            var best: String? = null
            for (m in groups) {
                val g = dupDao.groupById(m.groupId) ?: continue
                if (g.dismissed) continue
                when (g.groupType.name) {
                    "EXACT" -> { best = "EXACT" }
                    "VISUAL" -> if (best == null) best = "VISUAL"
                }
            }
            if (best != null) dupType[id] = best
        }

        db.withTransaction {
            for (shot in shots) {
                val input = FeatureTagEngine.Input(
                    width = shot.width,
                    height = shot.height,
                    fileSize = shot.fileSize,
                    imageEntropy = shot.imageEntropy,
                    colorVariance = shot.colorVariance,
                    ocrStatus = shot.ocrStatus.name,
                    ocrAttempts = shot.ocrAttempts,
                    recognizedText = texts[shot.id] ?: "",
                    duplicateGroupType = dupType[shot.id],
                )
                val hits = FeatureTagEngine.compute(input)

                tagDao.clearFeatureTags(shot.id)
                if (hits.isNotEmpty()) {
                    tagDao.upsertRelations(
                        hits.mapNotNull { hit ->
                            idMap[hit.tag]?.let { ScreenshotTagEntity(shot.id, it) }
                        }
                    )
                }
            }
        }
    }

    /** 各特征标签的图片数（供 UI 统计）。 */
    suspend fun counts(): List<io.github.wlmosv.screenbox.data.db.TagCount> =
        withContext(Dispatchers.IO) { tagDao.countByTag() }

    companion object {
        /** 每批处理多少张。标签计算是纯 CPU + 少量 IO，批次大一点减少事务开销。 */
        private const val BATCH = 200
    }
}