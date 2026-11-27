package io.github.wlmosv.screenbox.data.repo

import android.content.Context
import io.github.wlmosv.screenbox.data.db.ClassifyStatus
import io.github.wlmosv.screenbox.data.db.OcrStatus
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.ScanStateEntity
import io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase
import io.github.wlmosv.screenbox.data.db.ScreenshotConfidence
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.data.media.Hashing
import io.github.wlmosv.screenbox.data.media.MediaShot
import io.github.wlmosv.screenbox.data.media.MediaStoreSource
import io.github.wlmosv.screenbox.data.media.ScreenshotDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 扫描阶段。UI 直接展示这些阶段名，用户随时知道在干什么。
 */
enum class ScanPhase(val label: String) {
    IDLE("空闲"),
    ENUMERATING("正在读取截图列表"),
    INDEXING("正在建立索引"),
    HASHING("正在计算指纹"),
    FINISHING("正在收尾"),
    DONE("已完成"),
    FAILED("扫描失败"),
}

data class ScanProgress(
    val phase: ScanPhase = ScanPhase.IDLE,
    val current: Int = 0,
    val total: Int = 0,
    val message: String? = null,
    val startedAt: Long = 0L,
    val newItems: Int = 0,
    val updatedItems: Int = 0,
    val removedItems: Int = 0,
) {
    val fraction: Float get() = if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
    val isRunning: Boolean get() = phase in setOf(ScanPhase.ENUMERATING, ScanPhase.INDEXING, ScanPhase.HASHING, ScanPhase.FINISHING)
}

/** 指纹任务独立进度。与扫描解耦，因为它是"后台补数据"，不该阻塞浏览。 */
data class FingerprintProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 扫描引擎。
 *
 * 三条硬规矩：
 * 1. 不阻塞主线程 —— 全部在 IO 调度器，进度通过 StateFlow 推。
 * 2. 支持中断恢复 —— 每处理一批就落库，被杀掉重来不会丢已完成的部分。
 * 3. 增量 —— 靠 mediaStoreId + dateModified + fileSize 三元组判断是否需要重处理，
 *    不靠文件名（文件名会重名）。
 */
class ScanEngine(
    private val context: Context,
    private val db: ScreenBoxDatabase,
    private val media: MediaStoreSource = MediaStoreSource(context),
) {

    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    private val _fingerprint = MutableStateFlow(FingerprintProgress())
    val fingerprint: StateFlow<FingerprintProgress> = _fingerprint.asStateFlow()

    @Volatile
    private var cancelRequested = false

    fun requestCancel() {
        cancelRequested = true
    }

    /**
     * 执行一次扫描。
     * @param full true = 忽略时间戳，全量重扫；false = 增量。
     */
    suspend fun scan(full: Boolean) = withContext(Dispatchers.IO) {
        cancelRequested = false
        val startedAt = System.currentTimeMillis()
        val dao = db.screenshotDao()

        try {
            _progress.value = ScanProgress(
                phase = ScanPhase.ENUMERATING,
                message = if (full) "全量扫描" else "增量扫描",
                startedAt = startedAt,
            )

            // 读取已有指纹表，用于增量判定
            val existing = dao.allIdentityRows().associateBy { it.contentUri }
            val knownUris = existing.keys.toMutableSet()

            var newCount = 0
            var updatedCount = 0
            var processed = 0
            val pendingHash = mutableListOf<Long>()

            // 增量基线：优先用上次增量扫描时间，其次全量时间。
            // 两个都为空说明从没扫过 → 必须走全量，否则 0 条命中、什么也不做。
            val sinceSeconds = if (full) 0L else {
                val st = db.scanStateDao().get()
                val base = st?.lastIncrementalScanAt ?: st?.lastFullScanAt
                base?.div(1000) ?: 0L
            }
            // 基线为 0 等价于全量，避免"增量扫了等于没扫"
            val effectiveFull = full || sinceSeconds == 0L

            val consumer: suspend (List<MediaShot>) -> Unit = { page ->
                if (cancelRequested) throw CancellationException("用户取消扫描")
                coroutineContext.ensureActive()

                val accepted = page.filter { shot ->
                    val conf = ScreenshotDetector.classify(shot)
                    ScreenshotDetector.shouldInclude(conf, emptySet(), shot.relativePath)
                }

                processed += accepted.size
                _progress.value = _progress.value.copy(
                    phase = ScanPhase.INDEXING,
                    current = processed,
                    total = maxOf(processed, _progress.value.total),
                    message = "已处理 $processed 张",
                    newItems = newCount,
                    updatedItems = updatedCount,
                )

                val now = System.currentTimeMillis()
                for (shot in accepted) {
                    val prior = existing[shot.contentUri]
                    val conf = ScreenshotDetector.classify(shot)

                    if (prior == null) {
                        val id = dao.insertIgnore(
                            ScreenshotEntity(
                                mediaStoreId = shot.mediaStoreId,
                                contentUri = shot.contentUri,
                                displayName = shot.displayName,
                                relativePath = shot.relativePath,
                                mimeType = shot.mimeType,
                                fileSize = shot.fileSize,
                                width = shot.width,
                                height = shot.height,
                                dateAdded = shot.dateAdded,
                                dateModified = shot.dateModified,
                                screenshotConfidence = conf,
                                createdAt = now,
                                updatedAt = now,
                            )
                        )
                        if (id > 0) {
                            newCount++
                            pendingHash += id
                        }
                    } else {
                        // 内容可能变了（同 URI 被覆盖），或只是元数据变了
                        val changed = prior.dateModified != shot.dateModified || prior.fileSize != shot.fileSize
                        if (changed) {
                            val fresh = dao.byId(prior.id) ?: continue
                            dao.update(
                                fresh.copy(
                                    mediaStoreId = shot.mediaStoreId,
                                    displayName = shot.displayName,
                                    relativePath = shot.relativePath,
                                    fileSize = shot.fileSize,
                                    width = shot.width,
                                    height = shot.height,
                                    dateModified = shot.dateModified,
                                    // 内容变了，旧指纹和 OCR 全部作废
                                    sha256 = null,
                                    perceptualHash = null,
                                    ocrStatus = OcrStatus.NOT_STARTED,
                                    classifyStatus = ClassifyStatus.NOT_STARTED,
                                    updatedAt = now,
                                )
                            )
                            db.ocrDao().delete(prior.id)
                            db.ocrDao().deleteFts(prior.id)
                            updatedCount++
                            pendingHash += prior.id
                        }
                    }
                    knownUris.remove(shot.contentUri)
                }
            }

            if (effectiveFull) {
                media.queryAllImages(onPage = consumer)
            } else {
                media.queryChangedSince(sinceSeconds, onPage = consumer)
            }

            // 剩下没被这次枚举到的，说明已经从 MediaStore 消失
            var removed = 0
            if (effectiveFull && knownUris.isNotEmpty()) {
                val gone = dao.allIdentityRows().filter { it.contentUri in knownUris }.map { it.id }
                if (gone.isNotEmpty()) {
                    dao.markMissing(gone, System.currentTimeMillis())
                    removed = gone.size
                }
            }

            // ── 分层：指纹不再阻塞"图库可用" ──
            // v1 把指纹放在 scan() 里同步跑，8115 张要算 8 分钟，
            // 用户在这 8 分钟里既看不到图库也没法操作。
            // 现在 scan() 只负责"入库"，指纹由独立的 runFingerprint() 处理。
            _progress.value = _progress.value.copy(
                phase = ScanPhase.FINISHING,
                message = "写入扫描记录",
                removedItems = removed,
            )

            val now = System.currentTimeMillis()
            val prev = db.scanStateDao().get()
            db.scanStateDao().put(
                ScanStateEntity(
                    id = 1,
                    lastFullScanAt = if (effectiveFull) now else prev?.lastFullScanAt,
                    lastIncrementalScanAt = if (!effectiveFull) now else prev?.lastIncrementalScanAt,
                    lastScanDurationMs = now - startedAt,
                    lastScannedCount = processed,
                    isScanning = false,
                    scanPhase = ScanPhase.DONE.name,
                )
            )

            _progress.value = ScanProgress(
                phase = ScanPhase.DONE,
                current = processed,
                total = processed,
                startedAt = startedAt,
                newItems = newCount,
                updatedItems = updatedCount,
                removedItems = removed,
                message = "新增 $newCount，更新 $updatedCount" + if (removed > 0) "，移除 $removed" else "",
            )
        } catch (e: CancellationException) {
            _progress.value = _progress.value.copy(phase = ScanPhase.IDLE, message = "已取消")
            throw e
        } catch (t: Throwable) {
            _progress.value = _progress.value.copy(phase = ScanPhase.FAILED, message = t.message ?: "未知错误")
        }
    }

    /**
     * 第二层：指纹任务。独立于扫描，可单独跑、可中断、可恢复。
     *
     * 只处理 sha256 IS NULL 的，天然幂等。
     * 每 20 张提交一次，进程被杀最多重算 20 张。
     */
    suspend fun runFingerprint(batchSize: Int = 200) = withContext(Dispatchers.IO) {
        cancelRequested = false
        val dao = db.screenshotDao()

        val pending = dao.countPendingHash()
        if (pending == 0) {
            _fingerprint.value = FingerprintProgress()
            return@withContext
        }

        _fingerprint.value = FingerprintProgress(running = true, total = pending)
        var done = 0

        try {
            while (true) {
                if (cancelRequested) throw CancellationException("取消指纹计算")
                val batch = dao.nextHashBatch(batchSize)
                if (batch.isEmpty()) break

                for (shot in batch) {
                    if (cancelRequested) throw CancellationException("取消指纹计算")
                    val uri = android.net.Uri.parse(shot.contentUri)
                    runCatching {
                        val sha = Hashing.sha256(context, uri)
                        if (sha != null) {
                            dao.setSha256(shot.id, sha, System.currentTimeMillis())
                            val phash = Hashing.perceptualHash(context, uri)
                            if (phash != null) dao.setPhash(shot.id, phash, System.currentTimeMillis())
                        }
                    }
                    done++
                    if (done % 10 == 0) {
                        _fingerprint.value = _fingerprint.value.copy(done = done)
                    }
                }
                _fingerprint.value = _fingerprint.value.copy(done = done)
            }
            _fingerprint.value = _fingerprint.value.copy(running = false, done = done)
        } catch (e: CancellationException) {
            _fingerprint.value = _fingerprint.value.copy(running = false)
            throw e
        }
    }
}
