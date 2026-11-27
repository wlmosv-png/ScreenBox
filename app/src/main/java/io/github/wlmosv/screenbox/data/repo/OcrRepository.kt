package io.github.wlmosv.screenbox.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.wlmosv.screenbox.core.DeviceGuard
import io.github.wlmosv.screenbox.data.db.ClassifyStatus
import io.github.wlmosv.screenbox.data.db.OcrEntity
import io.github.wlmosv.screenbox.data.db.OcrFtsEntity
import io.github.wlmosv.screenbox.data.db.OcrStatus
import io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase
import io.github.wlmosv.screenbox.data.db.ScreenshotCategoryEntity
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.data.db.TaskStateEntity
import io.github.wlmosv.screenbox.data.ocr.MlKitOcrSource
import io.github.wlmosv.screenbox.domain.rules.ScreenshotClassifier
import io.github.wlmosv.screenbox.domain.rules.ScreenshotClassifierV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** OCR 队列策略。用户可切换。 */
enum class OcrQueuePolicy(val label: String) {
    RECENT_FIRST("优先近期"),
    ALL_HISTORY("全部历史"),
    NEW_ONLY("仅新增"),
}

/** 任务状态机。与数据库里的字符串一一对应。 */
enum class TaskStatus(val label: String) {
    IDLE("空闲"),
    QUEUED("排队中"),
    RUNNING("识别中"),
    PAUSED("已暂停"),
    STOPPING("正在停止"),
    COMPLETED("已完成"),
    FAILED("失败"),
}

/**
 * 从数据库读出的 OCR 进度快照。
 *
 * 这个类的所有字段都直接来自 task_state 表，不掺任何内存状态。
 * UI 只消费这个对象，保证前台后台、进程重启后数字一致。
 */
data class OcrProgress(
    val status: TaskStatus = TaskStatus.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val throughput: Float = 0f,
    val lastError: String? = null,
    val policy: OcrQueuePolicy = OcrQueuePolicy.RECENT_FIRST,
    val thermalPaused: Boolean = false,
    val healthNote: String? = null,
    /** 任务集在运行中被扩容过（新截图进来）。UI 应提示分母变了。 */
    val setExpanded: Boolean = false,
) {
    val running: Boolean get() = status == TaskStatus.RUNNING
    val paused: Boolean get() = status == TaskStatus.PAUSED
    val remaining: Int get() = (total - done - failed - skipped).coerceAtLeast(0)
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    /** 有足够样本才给 ETA，避免假精度。 */
    val etaSeconds: Int?
        get() = if (throughput > 0.2f && remaining > 0) (remaining / throughput).toInt() else null
}

/**
 * OCR 流水线（v3）。
 *
 * 相对 v2 的关键改动：进度不再存内存，而是写 task_state 表。
 *
 * 为什么必须这样改（v2 的真实 bug）：
 *   OcrWorker 每轮传 maxCount=600 → total 恒为 600
 *   done 每轮 AtomicInteger(0) 新建 → 永远从 0 开始
 *   结果：界面永久显示 "0 / 600"，而首页已识别数在涨，两个数字自相矛盾。
 *
 * v3 的做法：
 *   - 任务开始时把"当前待处理总数"写进 totalCount
 *   - 每批完成累加 doneCount（跨 Worker 轮次连续累计）
 *   - Worker 续跑时读同一行，不重置
 *   - 中途有新截图进来时调 expandTotal()，显式记录分母变化
 *
 * 不变量：totalCount >= doneCount + failedCount + skippedCount
 */
class OcrRepository(
    private val context: Context,
    private val db: ScreenBoxDatabase,
) {

    companion object {
        /** 并发度。由基准测试决定（并发3=2.68张/秒，并发4 温度过高）。 */
        const val CONCURRENCY = 3

        private const val BATCH = 60
        private const val TX_CHUNK = 20
        private const val MAX_ATTEMPTS = 3

        const val KIND = "ocr"
    }

    private val taskDao = db.taskStateDao()

    /** 供 UI 观察的进度流。唯一数据源是数据库。 */
    val progressFlow: Flow<OcrProgress> = taskDao.flow(KIND).map { it.toProgress() }

    private fun TaskStateEntity?.toProgress(): OcrProgress {
        if (this == null) return OcrProgress()
        return OcrProgress(
            status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.IDLE),
            done = doneCount,
            total = totalCount,
            failed = failedCount,
            skipped = skippedCount,
            policy = runCatching { OcrQueuePolicy.valueOf(policy) }
                .getOrDefault(OcrQueuePolicy.RECENT_FIRST),
            lastError = lastError,
            setExpanded = setExpandedAt != null,
        )
    }

    // 温度/内存守卫导致的临时暂停，只影响本次运行，不写库
    @Volatile private var thermalPaused = false
    @Volatile private var healthNote: String? = null
    @Volatile private var cancelled = false
    @Volatile private var paused = false

    fun cancel() { cancelled = true }
    fun pause() { paused = true }
    fun resume() { paused = false }

    /** 供 UI 读取当前温控提示。 */
    fun thermalState(): Pair<Boolean, String?> = thermalPaused to healthNote

    /**
     * 跑队列。
     *
     * @param maxCount 本次最多处理多少张，0 = 直到队列空
     * @param reset 是否重置累计（用户主动"重新开始"时用）
     */
    suspend fun run(
        maxCount: Int = 0,
        policy: OcrQueuePolicy = OcrQueuePolicy.RECENT_FIRST,
        reset: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        cancelled = false
        paused = false

        val dao = db.screenshotDao()
        dao.resetStaleProcessing()

        val now = System.currentTimeMillis()
        val existing = taskDao.get(KIND)

        // 决定是否新建任务集：
        // - reset=true 强制新建
        // - 库里没有记录
        // - 上次已 COMPLETED / FAILED（新一轮）
        // - 用户换了策略
        val needNewSet = reset
            || existing == null
            || existing.status in setOf("COMPLETED", "FAILED", "IDLE")
            || existing.policy != policy.name

        val pendingNow = dao.pendingOcrCount()

        if (needNewSet) {
            taskDao.put(
                TaskStateEntity(
                    kind = KIND,
                    status = TaskStatus.QUEUED.name,
                    totalCount = pendingNow,
                    doneCount = 0,
                    failedCount = 0,
                    skippedCount = 0,
                    startedAt = now,
                    updatedAt = now,
                    policy = policy.name,
                )
            )
        } else {
            // 续跑：分母可能因为新截图变大，显式记录
            if (pendingNow + existing.doneCount + existing.failedCount > existing.totalCount) {
                taskDao.expandTotal(
                    KIND,
                    pendingNow + existing.doneCount + existing.failedCount + existing.skippedCount,
                    now,
                )
            }
        }

        applyPolicy(policy)

        val state = taskDao.get(KIND) ?: return@withContext

        // ══ 关键修复 ══
        // 不能用 totalCount - done - failed - skipped 判断是否完成。
        // 那个算术值会因为重试、状态回退、任务集扩容等原因失真，
        // 导致 remaining > 0 但队列里实际没有可处理的图 —— 永久卡住。
        //
        // 必须查数据库真实待处理数。
        if (pendingNow == 0) {
            taskDao.updateStatus(
                KIND,
                if (state.totalCount > 0) TaskStatus.COMPLETED.name else TaskStatus.IDLE.name,
                System.currentTimeMillis(),
            )
            return@withContext
        }
        if (state.totalCount == 0) {
            taskDao.updateStatus(KIND, TaskStatus.IDLE.name, System.currentTimeMillis())
            return@withContext
        }

        taskDao.updateStatus(KIND, TaskStatus.RUNNING.name, System.currentTimeMillis())

        val ocr = MlKitOcrSource(context)
        val runStart = System.currentTimeMillis()
        // 本次运行的计数（相对本次），与库里的累计分开
        val runDone = AtomicInteger(0)
        val runFailed = AtomicInteger(0)

        try {
            var processedThisRun = 0

            while (!cancelled && (maxCount == 0 || processedThisRun < maxCount)) {
                // 用户暂停
                while (paused && !cancelled) {
                    taskDao.updateStatus(KIND, TaskStatus.PAUSED.name, System.currentTimeMillis())
                    delay(300)
                }
                if (cancelled) break
                taskDao.updateStatus(KIND, TaskStatus.RUNNING.name, System.currentTimeMillis())

                // 设备健康守卫
                val health = DeviceGuard.read()
                if (health.shouldPause) {
                    thermalPaused = true
                    healthNote = health.reason
                    DeviceGuard.reclaimMemory()
                    var waited = 0
                    while (!cancelled && !DeviceGuard.canResume() && waited < 60_000) {
                        delay(2000)
                        waited += 2000
                    }
                    thermalPaused = false
                    healthNote = null
                    if (cancelled) break
                } else if (health.rssMb >= 420) {
                    DeviceGuard.reclaimMemory()
                }

                val batch = dao.nextOcrBatchByPriority(BATCH)
                if (batch.isEmpty()) break

                val take = if (maxCount > 0) minOf(batch.size, maxCount - processedThisRun) else batch.size
                val slice = batch.take(take)
                processedThisRun += slice.size

                processBatch(ocr, slice, runDone, runFailed, runStart)
            }

            // 结算：用真实待处理数判定，不用算术值
            val remainingAfter = dao.pendingOcrCount()
            taskDao.updateStatus(
                KIND,
                when {
                    cancelled -> TaskStatus.PAUSED.name
                    remainingAfter == 0 -> TaskStatus.COMPLETED.name
                    else -> TaskStatus.PAUSED.name
                },
                System.currentTimeMillis(),
            )
        } catch (e: CancellationException) {
            taskDao.updateStatus(KIND, TaskStatus.PAUSED.name, System.currentTimeMillis())
            throw e
        } catch (t: Throwable) {
            taskDao.setError(KIND, t.message?.take(200), System.currentTimeMillis())
            taskDao.updateStatus(KIND, TaskStatus.FAILED.name, System.currentTimeMillis())
        } finally {
            ocr.close()
        }
    }

    /**
     * 处理一批：并发识别 → 批量落库 → 累加库里的计数。
     */
    private suspend fun processBatch(
        ocr: MlKitOcrSource,
        batch: List<ScreenshotEntity>,
        runDone: AtomicInteger,
        runFailed: AtomicInteger,
        runStart: Long,
    ) = coroutineScope {
        val gate = Semaphore(CONCURRENCY)
        val results = Channel<Pair<ScreenshotEntity, Result<io.github.wlmosv.screenbox.data.ocr.OcrResult>>>(
            capacity = Channel.UNLIMITED
        )

        val jobs = batch.map { shot ->
            async(Dispatchers.Default) {
                if (cancelled) return@async
                gate.withPermit {
                    if (cancelled) return@withPermit
                    val r = ocr.recognize(Uri.parse(shot.contentUri))
                    results.send(shot to r)
                }
            }
        }

        val consumer = async(Dispatchers.IO) {
            val buffer = mutableListOf<Pair<ScreenshotEntity, Result<io.github.wlmosv.screenbox.data.ocr.OcrResult>>>()
            var closed = false
            while (!closed) {
                val item = results.tryReceive().getOrNull()
                if (item != null) {
                    buffer += item
                    if (buffer.size >= TX_CHUNK) {
                        persistBatch(buffer, runDone, runFailed)
                        buffer.clear()
                    }
                } else {
                    if (jobs.all { it.isCompleted }) {
                        while (true) {
                            val rest = results.tryReceive().getOrNull() ?: break
                            buffer += rest
                        }
                        closed = true
                    } else {
                        delay(15)
                    }
                }
            }
            if (buffer.isNotEmpty()) persistBatch(buffer, runDone, runFailed)
        }

        jobs.awaitAll()
        consumer.await()
        results.close()

        // 吞吐只用于 UI 展示，不影响正确性，所以放内存
        val elapsed = ((System.currentTimeMillis() - runStart) / 1000f).coerceAtLeast(0.001f)
        lastThroughput = runDone.get() / elapsed
    }

    @Volatile
    private var lastThroughput: Float = 0f

    fun currentThroughput(): Float = lastThroughput

    /**
     * 一批结果落库。
     * 关键：done/failed 直接增量写进 task_state，跨 Worker 轮次连续累计。
     */
    private suspend fun persistBatch(
        items: List<Pair<ScreenshotEntity, Result<io.github.wlmosv.screenbox.data.ocr.OcrResult>>>,
        runDone: AtomicInteger,
        runFailed: AtomicInteger,
    ) {
        val now = System.currentTimeMillis()
        val ocrDao = db.ocrDao()
        val catDao = db.categoryDao()
        val shotDao = db.screenshotDao()
        val catIdByKey = catDao.allNow().associate { it.key to it.id }

        var okDelta = 0
        var failDelta = 0

        db.withTransaction {
            for ((shot, result) in items) {
                result.fold(
                    onSuccess = { r ->
                        val hasText = r.text.isNotBlank()
                        ocrDao.upsert(
                            OcrEntity(
                                screenshotId = shot.id,
                                recognizedText = r.text,
                                language = "zh+en",
                                recognitionStatus = if (hasText) OcrStatus.SUCCESS else OcrStatus.PARTIAL,
                                recognizedAt = now,
                                engineVersion = MlKitOcrSource.ENGINE_VERSION,
                                blockBounds = r.blockBoundsJson,
                                lineBounds = r.lineBoundsJson,
                            )
                        )
                        if (hasText) ocrDao.insertFts(OcrFtsEntity(shot.id, r.text))
                        shotDao.setOcrStatus(
                            shot.id,
                            if (hasText) OcrStatus.SUCCESS else OcrStatus.PARTIAL,
                            now,
                        )
                        if (hasText) {
                            // 结构化实体提取（日期/金额/订单号/手机号/URL/邮箱）
                            runCatching {
                                val ents = io.github.wlmosv.screenbox.domain.extract.EntityExtractor
                                    .extract(r.text)
                                if (ents.isNotEmpty()) {
                                    db.entityDao().clearFor(shot.id)
                                    db.entityDao().insertAll(
                                        ents.map { e ->
                                            io.github.wlmosv.screenbox.data.db.StructuredEntityEntity(
                                                screenshotId = shot.id,
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

                            val hits = ScreenshotClassifierV2.classify(r.text, shot.width, shot.height)
                            catDao.clearAutoRelations(shot.id)
                            for (hit in hits) {
                                val catId = catIdByKey[hit.categoryKey] ?: continue
                                catDao.upsertRelation(
                                    ScreenshotCategoryEntity(
                                        screenshotId = shot.id,
                                        categoryId = catId,
                                        confidence = hit.confidence,
                                        source = "auto",
                                        isUserConfirmed = false,
                                    )
                                )
                            }
                        }
                        shotDao.setClassifyStatus(shot.id, ClassifyStatus.DONE, now)
                        okDelta++
                        runDone.incrementAndGet()
                    },
                    onFailure = { e ->
                        ocrDao.upsert(
                            OcrEntity(
                                screenshotId = shot.id,
                                recognizedText = "",
                                language = "zh+en",
                                recognitionStatus = OcrStatus.FAILED,
                                recognizedAt = now,
                                engineVersion = MlKitOcrSource.ENGINE_VERSION,
                                errorMessage = e.message?.take(200),
                            )
                        )
                        shotDao.setOcrStatus(shot.id, OcrStatus.FAILED, now)
                        shotDao.bumpOcrAttempts(shot.id)
                        failDelta++
                        runFailed.incrementAndGet()
                    }
                )
            }
        }

        // ══ R5：计数从数据库实时推导，不累加 ══
        // 与 AnalysisRepository 同一原则：累加模型会因重复调用/进程被杀永久失真。
        publishProgress(TaskStatus.RUNNING)
    }

    /**
     * 把数据库真实状态写成任务进度。
     *
     * doneCount   = OCR 已完成（SUCCESS + PARTIAL）
     * failedCount = OCR 失败
     * totalCount  = 已完成 + 失败 + 待处理
     *
     * 这样显示的数字永远等于数据库真实状态，无论任务怎么中断重启。
     */
    private suspend fun publishProgress(status: TaskStatus) {
        val dao = db.screenshotDao()
        val done = dao.countOcrDone()
        val failed = dao.countOcrFailed()
        val pending = dao.pendingOcrCount()

        taskDao.put(
            TaskStateEntity(
                kind = KIND,
                status = status.name,
                totalCount = done + failed + pending,
                doneCount = done,
                failedCount = failed,
                skippedCount = 0,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun applyPolicy(policy: OcrQueuePolicy) {
        val dao = db.screenshotDao()
        val now = System.currentTimeMillis()
        val day = 24L * 3600 * 1000
        when (policy) {
            OcrQueuePolicy.RECENT_FIRST -> {
                dao.setOcrPriorityBulk(now - 7 * day, 1)
                dao.setOcrPriorityBulk(now - 30 * day, 4)
            }
            OcrQueuePolicy.ALL_HISTORY -> dao.setOcrPriorityBulk(0, 9)
            OcrQueuePolicy.NEW_ONLY -> {
                dao.setOcrPriorityBulk(now - 7 * day, 1)
                dao.setOcrPriorityBulk(now, 99)
            }
        }
    }

    suspend fun reprocess(screenshotId: Long) = withContext(Dispatchers.IO) {
        db.screenshotDao().setOcrStatus(screenshotId, OcrStatus.QUEUED, System.currentTimeMillis())
        db.ocrDao().deleteFts(screenshotId)
    }

    suspend fun retryFailed() = withContext(Dispatchers.IO) {
        db.screenshotDao().resetFailedOcr()
        // 重试后分母要重新算
        val pending = db.screenshotDao().pendingOcrCount()
        val cur = taskDao.get(KIND)
        if (cur != null) {
            taskDao.expandTotal(KIND, cur.doneCount + pending, System.currentTimeMillis())
        }
    }

    /** 进程重启后校正残留的 RUNNING 状态。 */
    suspend fun recoverAfterRestart() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        taskDao.resetStaleRunning(now)
        // 剩余为 0 但状态还停在暂停/运行的任务，校正为已完成。
        // 否则用户看到"继续"按钮却无事可做（R5 实测 bug）。
        taskDao.reconcileCompleted(now)
    }
}

/**
 * 搜索仓储。
 *
 * 中文搜索实测结论：FTS4 内置分词器对中文无效（整段一个 token），
 * 所以用 instr() 子串匹配。实测 avg 10.9ms @ 万级数据量。
 */
class SearchRepository(private val db: ScreenBoxDatabase) {

    suspend fun searchIds(keyword: String, limit: Int = 100, offset: Int = 0): List<Long> =
        withContext(Dispatchers.IO) {
            val trimmed = keyword.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()
            db.ocrDao().searchSubstring(trimmed, limit, offset)
        }

    suspend fun count(keyword: String): Int = withContext(Dispatchers.IO) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return@withContext 0
        db.ocrDao().countSubstring(trimmed)
    }

    suspend fun snippet(screenshotId: Long, keyword: String, radius: Int = 30): String =
        withContext(Dispatchers.IO) {
            val ocr = db.ocrDao().byScreenshot(screenshotId) ?: return@withContext ""
            val text = ocr.recognizedText.replace("\n", " ")
            val idx = text.indexOf(keyword, ignoreCase = true)
            if (idx < 0) return@withContext text.take(80)
            val start = (idx - radius).coerceAtLeast(0)
            val end = (idx + keyword.length + radius).coerceAtMost(text.length)
            buildString {
                if (start > 0) append("…")
                append(text.substring(start, end).trim())
                if (end < text.length) append("…")
            }
        }

    /** 记录搜索历史，用于"最近搜索"和常用词。 */
    suspend fun recordHistory(keyword: String, hitCount: Int) = withContext(Dispatchers.IO) {
        val k = keyword.trim()
        if (k.isEmpty()) return@withContext
        db.searchHistoryDao().put(
            io.github.wlmosv.screenbox.data.db.SearchHistoryEntity(
                keyword = k,
                hitCount = hitCount,
                lastUsedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun recentSearches(limit: Int = 10) = withContext(Dispatchers.IO) {
        db.searchHistoryDao().recent(limit)
    }
}
