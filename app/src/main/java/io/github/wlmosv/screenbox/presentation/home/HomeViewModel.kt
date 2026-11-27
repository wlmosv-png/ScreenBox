package io.github.wlmosv.screenbox.presentation.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.core.permission.MediaPermissionState
import io.github.wlmosv.screenbox.core.permission.MediaPermissions
import io.github.wlmosv.screenbox.core.permission.PermissionStateHolder
import io.github.wlmosv.screenbox.data.db.DuplicateGroupType
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.repo.FingerprintProgress
import io.github.wlmosv.screenbox.data.repo.OcrProgress
import io.github.wlmosv.screenbox.data.repo.OcrQueuePolicy
import io.github.wlmosv.screenbox.data.repo.ScanPhase
import io.github.wlmosv.screenbox.data.repo.ScanProgress
import io.github.wlmosv.screenbox.worker.FingerprintWorker
import io.github.wlmosv.screenbox.worker.OcrWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 首页统计。
 *
 * 每个数字都有唯一定义，且互不叠加（任务书 7.1 要求）：
 *
 * totalScreenshots  当前有效索引中的截图数（不含 DELETED/MISSING）
 * recognizedCount   OCR 已完成（SUCCESS + PARTIAL）
 * pendingOcrCount   尚未完成 OCR（NOT_STARTED + QUEUED + FAILED）
 * exactGroupCount   完全重复的组数
 * exactExtraFiles   完全重复组里"多出来的"文件数（= 组内总数 - 组数）
 * similarGroupCount 视觉相似的组数
 * cleanupCandidates 当前 OPEN 状态的清理建议数（去重到图片）
 * reclaimableBytes  按"完全重复只留一份 + 相似组留一份"估算的可释放空间
 * largeFileCount    ≥2MB 的截图数
 *
 * 关键：exactExtraFiles 和 cleanupCandidates 可能重叠，界面上不能相加。
 */
data class HomeStats(
    val totalScreenshots: Int = 0,
    val recognizedCount: Int = 0,
    val pendingOcrCount: Int = 0,
    val totalBytes: Long = 0,
    val exactGroupCount: Int = 0,
    val exactExtraFiles: Int = 0,
    val similarGroupCount: Int = 0,
    val cleanupCandidates: Int = 0,
    val reclaimableBytes: Long = 0,
    val largeFileCount: Int = 0,
    val protectedCount: Int = 0,
    val favoriteCount: Int = 0,
)

/** 分析任务进度。 */
data class AnalysisProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** 失败数（解码失败等）。失败也算处理过，不再重试。 */
    val failed: Int = 0,
    /** 任务状态字符串（COMPLETED / PAUSED / RUNNING / IDLE）。 */
    val status: String = "IDLE",
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    /** 真的还有活要干：没跑完且算术上仍有剩余。 */
    val hasWork: Boolean
        get() = status != "COMPLETED" && (total - done - failed) > 0
}

data class HomeUiState(
    val permission: MediaPermissionState = MediaPermissionState.DENIED,
    val stats: HomeStats = HomeStats(),
    val scan: ScanProgress = ScanProgress(),
    val ocr: OcrProgress = OcrProgress(),
    val fingerprint: FingerprintProgress = FingerprintProgress(),
    /** 图片分析任务（SHA-256 + pHash + 熵）。 */
    val analysis: AnalysisProgress = AnalysisProgress(),
    val libraryReady: Boolean = false,
    val recentShots: List<io.github.wlmosv.screenbox.data.db.ScreenshotEntity> = emptyList(),
    /** 扫描/OCR 过程中最近处理的几张缩略图，用于可视化"系统真的在工作"。 */
    val processingShots: List<io.github.wlmosv.screenbox.data.db.ScreenshotEntity> = emptyList(),
    val loading: Boolean = true,
    val toast: String? = null,
)

/**
 * 首页 ViewModel（v3）。
 *
 * 相对 v2 的改动：
 * 1. OCR 进度改读数据库（唯一可信来源），不再用内存 StateFlow
 * 2. 统计口径补全 exactExtraFiles / reclaimableBytes / largeFileCount
 * 3. 每个统计入口有独立回调，不再共用 onOpenCleanup
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Graph.db
    private val scanEngine = Graph.scanEngine
    private val ocrRepo = Graph.ocrRepo

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var ocrJob: Job? = null
    private var fpJob: Job? = null
    private var analysisJob: Job? = null

    init {
        observePermission()
        observe()
    }

    /**
     * 订阅全局权限状态。
     *
     * 旧版只在 init 时查一次，用户授权回来后 UI 不刷新 —— "点了没反应"。
     * 现在所有页面共享 PermissionStateHolder，授权回调 / onResume 都会更新。
     */
    private fun observePermission() {
        viewModelScope.launch {
            PermissionStateHolder.state.collect { state ->
                _uiState.value = _uiState.value.copy(permission = state)
                if (MediaPermissions.canReadAnything(state)) ensureSeed()
            }
        }
    }

    fun refreshPermission() {
        PermissionStateHolder.refresh()
    }

    private fun ensureSeed() {
        viewModelScope.launch {
            runCatching { io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase.seedCategories(db) }
            runCatching { io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase.seedSources(db) }
            runCatching { io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase.seedTags(db) }
            // 特征标签是派生数据：库里已有截图但没有任何特征标签时，说明从未建过。
            // 在后台补一次，不阻塞 UI、不重跑 OCR。
            runCatching {
                val shots = db.screenshotDao().countActive()
                val tagRows = db.tagDao().count()
                if (shots > 0 && tagRows > 0) {
                    val none = db.tagDao().idsWithoutFeatureTags(1)
                    if (none.isNotEmpty() && db.screenshotDao().activeFeatureTagCount() == 0) {
                        Graph.tagRepo.rebuildAll()
                    } else {
                        Graph.tagRepo.rebuildIncremental()
                    }
                }
            }
        }
    }

    private fun observe() {
        viewModelScope.launch {
            combine(
                db.screenshotDao().countFlow(),
                db.screenshotDao().recognizedCountFlow(),
                db.screenshotDao().pendingOcrCountFlow(),
                db.screenshotDao().totalBytesFlow(),
            ) { total, recognized, pending, bytes ->
                listOf(total, recognized, pending, bytes)
            }.collect { arr ->
                _uiState.value = _uiState.value.copy(
                    stats = _uiState.value.stats.copy(
                        totalScreenshots = arr[0] as Int,
                        recognizedCount = arr[1] as Int,
                        pendingOcrCount = arr[2] as Int,
                        totalBytes = arr[3] as Long,
                    ),
                    libraryReady = (arr[0] as Int) > 0,
                    loading = false,
                )
            }
        }

        viewModelScope.launch {
            db.duplicateDao().countByTypeFlow(DuplicateGroupType.EXACT).collect { n ->
                _uiState.value = _uiState.value.copy(
                    stats = _uiState.value.stats.copy(exactGroupCount = n)
                )
            }
        }
        viewModelScope.launch {
            db.duplicateDao().countByTypeFlow(DuplicateGroupType.VISUAL).collect { n ->
                _uiState.value = _uiState.value.copy(
                    stats = _uiState.value.stats.copy(similarGroupCount = n)
                )
            }
        }
        viewModelScope.launch {
            db.cleanupDao().openCountFlow().collect { n ->
                _uiState.value = _uiState.value.copy(
                    stats = _uiState.value.stats.copy(cleanupCandidates = n)
                )
            }
        }
        viewModelScope.launch {
            db.screenshotDao().countByProtectionFlow(ProtectionStatus.PROTECTED).collect { n ->
                _uiState.value = _uiState.value.copy(
                    stats = _uiState.value.stats.copy(protectedCount = n)
                )
            }
        }
        viewModelScope.launch {
            db.screenshotDao().countByProtectionFlow(ProtectionStatus.FAVORITE).collect { n ->
                _uiState.value = _uiState.value.copy(
                    stats = _uiState.value.stats.copy(favoriteCount = n)
                )
            }
        }

        // 派生统计：完全重复多余文件数、可释放空间、大文件数
        viewModelScope.launch {
            runCatching {
                val extras = db.duplicateDao().exactExtraFileCount()
                val reclaim = db.duplicateDao().exactReclaimableBytes()
                val large = db.screenshotDao().countLargeFiles(2L * 1024 * 1024)
                _uiState.value = _uiState.value.copy(
                    stats = _uiState.value.stats.copy(
                        exactExtraFiles = extras,
                        reclaimableBytes = reclaim,
                        largeFileCount = large,
                    )
                )
            }
        }

        viewModelScope.launch {
            scanEngine.progress.collect { p ->
                _uiState.value = _uiState.value.copy(scan = p)
            }
        }
        viewModelScope.launch {
            scanEngine.fingerprint.collect { p ->
                _uiState.value = _uiState.value.copy(fingerprint = p)
            }
        }

        // 扫描/OCR 运行中，定期取最近处理的几张缩略图做可视化
        viewModelScope.launch {
            while (true) {
                val active = _uiState.value.scan.isRunning || _uiState.value.ocr.running
                if (active) {
                    runCatching {
                        val shots = db.screenshotDao().recentlyProcessed(6)
                        _uiState.value = _uiState.value.copy(processingShots = shots)
                    }
                }
                kotlinx.coroutines.delay(if (active) 1500 else 4000)
            }
        }

        // 分析任务进度（读 task_state，与 OCR 同一套机制）
        viewModelScope.launch {
            db.taskStateDao().flow("analysis").collect { st ->
                if (st == null) {
                    _uiState.value = _uiState.value.copy(analysis = AnalysisProgress())
                } else {
                    _uiState.value = _uiState.value.copy(
                        analysis = AnalysisProgress(
                            running = st.status == "RUNNING",
                            done = st.doneCount,
                            total = st.totalCount,
                            failed = st.failedCount,
                            status = st.status,
                        )
                    )
                }
            }
        }

        // OCR 进度：读数据库，唯一可信来源
        viewModelScope.launch {
            ocrRepo.progressFlow.collect { p ->
                val thermal = ocrRepo.thermalState()
                _uiState.value = _uiState.value.copy(
                    ocr = p.copy(
                        throughput = ocrRepo.currentThroughput(),
                        thermalPaused = thermal.first,
                        healthNote = thermal.second,
                    )
                )
            }
        }

        // 启动自愈：清掉保护项的历史遗留建议
        // 场景：用户在旧版本里保护了一张图，但当时建议没被清掉。
        // 现在启动时统一修正，保证统计口径和清理清单一致。
        viewModelScope.launch {
            runCatching {
                val healed = db.cleanupDao().resolveOpenForProtected()
                if (healed > 0) {
                    android.util.Log.i("SCREENBOX", "启动自愈：清理了 $healed 条保护项建议")
                }
            }
        }

        // 进程重启后校正残留 RUNNING
        viewModelScope.launch {
            runCatching {
                ocrRepo.recoverAfterRestart()
                val st = db.scanStateDao().get()
                if (st?.isScanning == true) {
                    db.scanStateDao().put(st.copy(isScanning = false, scanPhase = ScanPhase.IDLE.name))
                }
            }
        }

        viewModelScope.launch {
            db.screenshotDao().paged(12, 0).collect { list ->
                _uiState.value = _uiState.value.copy(recentShots = list)
            }
        }
    }

    /**
     * 扫描按钮。
     *
     * ══ R4 修复 ══
     *
     * 旧版问题：
     * 1. 只在 scanJob.isActive 时提示"进行中"，但 OCR/分析任务在跑时
     *    scanJob 已经结束，按钮看起来可点却什么也不做。
     * 2. 扫描完成后只 enqueue Worker，不启动前台任务，
     *    用户看不到后续进度，以为卡住了。
     *
     * 新版：
     * - 任何任务在跑 → 明确提示当前在做什么
     * - 扫描后立即接上分析 + OCR，用户能立刻看到任务卡
     * - 全部完成后给出明确总结
     */
    fun startScan(full: Boolean) {
        // 检查是否已有任务在跑
        val ocrRunning = _uiState.value.ocr.running
        val analysisRunning = _uiState.value.analysis.running
        if (scanJob?.isActive == true) {
            _uiState.value = _uiState.value.copy(toast = "正在扫描截图，请稍候")
            return
        }
        if (ocrRunning) {
            _uiState.value = _uiState.value.copy(toast = "文字识别进行中，可先暂停再扫描")
            return
        }

        scanJob = viewModelScope.launch {
            scanEngine.scan(full)

            val p = scanEngine.progress.value
            val msg = when {
                p.phase == ScanPhase.FAILED -> "扫描失败：${p.message ?: "未知错误"}"
                p.newItems == 0 && p.updatedItems == 0 && p.removedItems == 0 ->
                    "没有发现新截图（已是最新）"
                else -> buildString {
                    append("扫描完成")
                    if (p.newItems > 0) append("，新增 ${p.newItems}")
                    if (p.updatedItems > 0) append("，更新 ${p.updatedItems}")
                    if (p.removedItems > 0) append("，移除 ${p.removedItems}")
                }
            }
            _uiState.value = _uiState.value.copy(toast = msg)

            // 扫描完立刻启动后续任务（前台跑，用户能看到进度）
            // 不用 Worker 是因为 Worker 有调度延迟，用户会以为没反应
            launch { runCatching { Graph.analysisRepo.run() } }
            launch { runCatching { ocrRepo.run() } }
        }
    }

    /**
     * 任务卡上的「继续」按钮。
     *
     * 根据当前卡住的任务类型启动对应任务，不是笼统地"扫描"。
     */
    fun resumeCurrentTask() {
        val s = _uiState.value
        when {
            s.analysis.running -> return // 已在跑
            s.ocr.paused -> {
                ocrRepo.resume()
                startOcr()
            }
            s.analysis.total > 0 && s.analysis.done < s.analysis.total -> {
                startAnalysis()
            }
            s.stats.pendingOcrCount > 0 -> {
                startOcr()
            }
            else -> {
                _uiState.value = _uiState.value.copy(toast = "没有待处理的任务")
            }
        }
    }

    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    fun cancelScan() = scanEngine.requestCancel()

    fun startOcr(policy: OcrQueuePolicy = OcrQueuePolicy.RECENT_FIRST) {
        if (ocrJob?.isActive == true) return
        ocrJob = viewModelScope.launch {
            runCatching { ocrRepo.run(policy = policy) }
        }
    }

    fun pauseOcr() = ocrRepo.pause()
    fun resumeOcr() = ocrRepo.resume()
    fun cancelOcr() = ocrRepo.cancel()

    fun retryFailedOcr() {
        viewModelScope.launch { runCatching { ocrRepo.retryFailed() } }
    }

    /** 启动图片分析（SHA-256 + pHash + 熵）。 */
    fun startAnalysis() {
        if (analysisJob?.isActive == true) return
        analysisJob = viewModelScope.launch {
            runCatching { Graph.analysisRepo.run() }
            // 分析完自动重建重复组和建议
            runCatching { Graph.duplicateRepo.rebuild() }
            runCatching { Graph.suggestionRepo.rebuild() }
        }
    }

    fun startFingerprint() {
        if (fpJob?.isActive == true) return
        fpJob = viewModelScope.launch {
            runCatching { scanEngine.runFingerprint() }
            runCatching { Graph.duplicateRepo.rebuild() }
            runCatching { Graph.suggestionRepo.rebuild() }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        ocrJob?.cancel()
        fpJob?.cancel()
        analysisJob?.cancel()
    }
}
