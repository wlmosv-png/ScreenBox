package io.github.wlmosv.screenbox.data.repo

import androidx.room.withTransaction
import io.github.wlmosv.screenbox.data.db.CategoryEntity
import io.github.wlmosv.screenbox.data.db.ScreenshotCategoryEntity
import io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase
import io.github.wlmosv.screenbox.data.db.ScreenshotSourceEntity
import io.github.wlmosv.screenbox.domain.rules.ScreenshotClassifier
import io.github.wlmosv.screenbox.domain.rules.ScreenshotClassifierV2
import io.github.wlmosv.screenbox.domain.rules.SourceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 分类引擎（v2）。
 *
 * 关键设计：**不重跑 OCR**。
 * 8130 张里已有 4600+ 条 OCR 结果，重跑要 30 分钟且结果不变。
 * 这里只读 ocr 表的文本，重新跑规则，写回分类和来源。
 *
 * 两种模式：
 * - FULL：清空所有自动分类，全部重算（换规则后第一次跑用）
 * - INCREMENTAL：只处理还没有分类/来源的图（日常增量用）
 *
 * 用户手动确认的分类和来源永不被覆盖。
 */
class ClassifyRepository(private val db: ScreenBoxDatabase) {

    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val classified: Int = 0,
        val sourceIdentified: Int = 0,
        val sourceUnknown: Int = 0,
    )

    /**
     * 全量重分类。
     *
     * @param onProgress 每批回调，供 UI 显示进度
     */
    suspend fun reclassifyAll(
        onProgress: (Progress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val shotDao = db.screenshotDao()
        val ocrDao = db.ocrDao()
        val catDao = db.categoryDao()
        val srcDao = db.sourceAppDao()

        // 分类和来源的 id 映射预先查一次
        val catIdByKey = catDao.allNow().associate { it.key to it.id }
        val srcIdByKey = srcDao.allNow().associate { it.key to it.id }
        val unknownSourceId = srcIdByKey["unknown"] ?: return@withContext

        // 只处理有 OCR 文本的图
        val targets = ocrDao.screenshotIdsWithText()
        val total = targets.size
        var done = 0
        var classified = 0
        var srcIdentified = 0
        var srcUnknown = 0

        onProgress(Progress(running = true, total = total))

        val now = System.currentTimeMillis()

        // 分批处理，每批 200 张，避免一次加载太多文本
        targets.chunked(200).forEach { batch ->
            coroutineContext.ensureActive()

            // 一次性取出这批的 OCR 文本
            val texts = ocrDao.textsOf(batch).associate { it.screenshotId to it.text }
            // 取出图片尺寸（分类规则需要宽高）
            val shots = shotDao.byIds(batch).associateBy { it.id }

            db.withTransaction {
                for (id in batch) {
                    val text = texts[id] ?: continue
                    val shot = shots[id]

                    // ── 内容分类 ──
                    // 清掉旧的自动分类，保留用户确认的
                    catDao.clearAutoRelationsKeepUser(id)

                    // R9：改用 V2 分类器（几何信号优先，无法判断时明确不分类）
                    val hits = ScreenshotClassifierV2.classify(
                        text,
                        shot?.width ?: 0,
                        shot?.height ?: 0,
                    )
                    for (hit in hits) {
                        val catId = catIdByKey[hit.categoryKey] ?: continue
                        catDao.upsertRelation(
                            ScreenshotCategoryEntity(
                                screenshotId = id,
                                categoryId = catId,
                                confidence = hit.confidence,
                                source = "auto",
                                isUserConfirmed = false,
                            )
                        )
                    }
                    if (hits.isNotEmpty()) classified++

                    // 同时回填结构化实体（历史数据也需要）
                    runCatching {
                        val ents = io.github.wlmosv.screenbox.domain.extract.EntityExtractor.extract(text)
                        db.entityDao().clearFor(id)
                        if (ents.isNotEmpty()) {
                            db.entityDao().insertAll(
                                ents.map { e ->
                                    io.github.wlmosv.screenbox.data.db.StructuredEntityEntity(
                                        screenshotId = id,
                                        kind = e.kind.name,
                                        raw = e.raw,
                                        normalized = e.normalized,
                                        confidence = e.confidence,
                                        startPos = e.start,
                                    )
                                }
                            )
                        }
                    }

                    // ── 来源识别（v3：标题栏加权 + 通用来源类型）──
                    val srcHit = SourceDetector.detect(text)
                    if (srcHit != null) {
                        val appId = srcIdByKey[srcHit.appKey]
                        if (appId != null) {
                            srcDao.upsertAuto(
                                screenshotId = id,
                                sourceAppId = appId,
                                confidence = srcHit.confidence,
                                evidence = srcHit.evidence.joinToString("；"),
                                now = now,
                            )
                            srcIdentified++
                        }
                    } else {
                        // 识别不出就明确标"来源不明"，不硬猜
                        srcDao.upsertAuto(
                            screenshotId = id,
                            sourceAppId = unknownSourceId,
                            confidence = 0f,
                            evidence = "未命中任何应用界面特征",
                            now = now,
                        )
                        srcUnknown++
                    }
                }
            }

            done += batch.size
            onProgress(
                Progress(
                    running = true,
                    done = done,
                    total = total,
                    classified = classified,
                    sourceIdentified = srcIdentified,
                    sourceUnknown = srcUnknown,
                )
            )
        }

        onProgress(
            Progress(
                running = false,
                done = done,
                total = total,
                classified = classified,
                sourceIdentified = srcIdentified,
                sourceUnknown = srcUnknown,
            )
        )

        // 分类/来源变化影响特征标签（OCR 状态、重复组归属不变，但保持一致）
        runCatching { TagRepository(db).rebuildIncremental(limit = 9000) }
    }

    /** 增量：只处理还没分类/来源的。 */
    suspend fun reclassifyIncremental(
        limit: Int = 2000,
        onProgress: (Progress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val ocrDao = db.ocrDao()
        val srcDao = db.sourceAppDao()
        val catDao = db.categoryDao()
        val shotDao = db.screenshotDao()

        val ids = srcDao.idsWithoutSource(limit)
        if (ids.isEmpty()) {
            onProgress(Progress(running = false))
            return@withContext
        }

        val catIdByKey = catDao.allNow().associate { it.key to it.id }
        val srcIdByKey = srcDao.allNow().associate { it.key to it.id }
        val unknownSourceId = srcIdByKey["unknown"] ?: return@withContext

        var done = 0
        onProgress(Progress(running = true, total = ids.size))

        val now = System.currentTimeMillis()
        ids.chunked(200).forEach { batch ->
            coroutineContext.ensureActive()
            val texts = ocrDao.textsOf(batch).associate { it.screenshotId to it.text }
            val shots = shotDao.byIds(batch).associateBy { it.id }

            db.withTransaction {
                for (id in batch) {
                    val text = texts[id] ?: continue
                    val shot = shots[id]

                    catDao.clearAutoRelationsKeepUser(id)
                    val hits = ScreenshotClassifierV2.classify(text, shot?.width ?: 0, shot?.height ?: 0)
                    for (hit in hits) {
                        val catId = catIdByKey[hit.categoryKey] ?: continue
                        catDao.upsertRelation(
                            ScreenshotCategoryEntity(
                                screenshotId = id,
                                categoryId = catId,
                                confidence = hit.confidence,
                                source = "auto",
                                isUserConfirmed = false,
                            )
                        )
                    }

                    val srcHit = SourceDetector.detect(text)
                    srcDao.upsertAuto(
                        screenshotId = id,
                        sourceAppId = srcHit?.let { srcIdByKey[it.appKey] } ?: unknownSourceId,
                        confidence = srcHit?.confidence ?: 0f,
                        evidence = srcHit?.evidence?.joinToString("；") ?: "未命中任何应用界面特征",
                        now = now,
                    )
                }
            }
            done += batch.size
            onProgress(Progress(running = true, done = done, total = ids.size))
        }
        onProgress(Progress(running = false, done = done, total = ids.size))
        runCatching { TagRepository(db).rebuildIncremental(limit = 9000) }
    }
}
