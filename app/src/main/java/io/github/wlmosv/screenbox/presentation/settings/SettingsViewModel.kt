package io.github.wlmosv.screenbox.presentation.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wlmosv.screenbox.BuildConfig
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.repo.DuplicateRepository
import io.github.wlmosv.screenbox.data.repo.TagRepository
import io.github.wlmosv.screenbox.worker.LineBackfillRunner
import io.github.wlmosv.screenbox.worker.LineBackfillWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页状态。
 *
 * ══ R5 改动 ══
 *
 * 旧版设置页是纯装饰：三个开关只改本地 remember，重启即失效；
 * 版本号硬编码 "0.1.0"，与实际 APK 不符。
 *
 * 用户根本没有任何入口去重建分类、重建标签、重建重复组 ——
 * 这些只能靠 debug 广播触发，等于普通用户用不到。
 *
 * 现在这一页承担**维护入口**职责：
 * 每个按钮都真的调用仓库、真的落库、完成后给出可核对的结果。
 *
 * ══ R10 改动 ══
 *
 * 行级坐标回填从「点一次 40 张」改成后台任务：有进度、可暂停、可继续，
 * 进程被杀后从数据库剩余量恢复。状态读 task_state，不靠内存。
 */
data class SettingsUiState(
    val busy: String? = null,
    val lastResult: String? = null,
    val tagCount: Int = 0,
    /** 有内容分类的图片数。 */
    val categoryCovered: Int = 0,
    /** 还没有内容分类的图片数（识别不出来会留在这里）。 */
    val categoryMissing: Int = 0,
    val totalActive: Int = 0,
    /** 已回填行级坐标的 OCR 条数。 */
    val lineBoundsDone: Int = 0,
    /** 还缺行级坐标的 OCR 条数。 */
    val lineBoundsTodo: Int = 0,
    /** 尝试过但读取失败的条数（文件已删/损坏/权限不足）。 */
    val lineBoundsFailed: Int = 0,
    /** 后台回填任务状态：IDLE / RUNNING / PAUSED / COMPLETED / FAILED。 */
    val lineTaskStatus: String = "IDLE",
    val lineTaskTotal: Int = 0,
    val lineTaskDone: Int = 0,
    val lineTaskRemaining: Int = 0,
    val lineTaskError: String? = null,
)

private data class Stats(
    val total: Int,
    val covered: Int,
    val missing: Int,
    val tags: Int,
    val lineDone: Int,
    val lineTodo: Int,
    val lineFailed: Int,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Graph.db

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    val versionName: String get() = BuildConfig.VERSION_NAME
    val versionCode: Int get() = BuildConfig.VERSION_CODE

    init {
        observeLineTask()
        // 进程重启后，残留的 RUNNING 状态不可信，校正为 PAUSED 等用户继续。
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    db.taskStateDao().resetStaleRunning(System.currentTimeMillis())
                }
            }
        }
    }

    private fun observeLineTask() {
        viewModelScope.launch {
            db.taskStateDao().flow(LineBackfillWorker.TASK_KIND).collectLatest { st ->
                _ui.value = _ui.value.copy(
                    // 前台通道在跑时，状态以内存为准，避免后台 worker 的
                    // PAUSED 写入把界面拉回「已暂停」。
                    lineTaskStatus = if (LineBackfillRunner.isRunning) "RUNNING"
                    else st?.status ?: "IDLE",
                    lineTaskTotal = st?.totalCount ?: 0,
                    lineTaskDone = st?.doneCount ?: 0,
                    lineTaskRemaining = st?.remaining ?: 0,
                    lineTaskError = st?.lastError,
                )
            }
        }
    }

    override fun onCleared() {
        // 离开设置页：停前台，交给后台通道，不丢进度。
        LineBackfillRunner.stop()
        super.onCleared()
    }

    fun refreshStats() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                Stats(
                    total = db.screenshotDao().countActive(),
                    covered = db.screenshotDao().categoryCoveredCount(),
                    missing = db.screenshotDao().categoryMissingCount(),
                    tags = db.tagDao().count(),
                    lineDone = db.ocrDao().countWithLineBounds(),
                    lineTodo = db.ocrDao().countMissingLineBounds(),
                    lineFailed = db.ocrDao().countFailedLineBounds(),
                )
            }
            _ui.value = _ui.value.copy(
                totalActive = stats.total,
                categoryCovered = stats.covered,
                categoryMissing = stats.missing,
                tagCount = stats.tags,
                lineBoundsDone = stats.lineDone,
                lineBoundsTodo = stats.lineTodo,
                lineBoundsFailed = stats.lineFailed,
            )
        }
    }

    /** 重建分类与来源（不重跑 OCR）。 */
    fun rebuildClassification() = runTask("正在重建分类与来源") {
        var last: io.github.wlmosv.screenbox.data.repo.ClassifyRepository.Progress? = null
        Graph.classifyRepo.reclassifyAll { p -> last = p }
        val p = last
        "分类完成：${p?.classified ?: 0} 张有分类，" +
            "来源识别 ${p?.sourceIdentified ?: 0} 张，" +
            "来源不明 ${p?.sourceUnknown ?: 0} 张"
    }

    /** 重建特征标签。 */
    fun rebuildTags() = runTask("正在重建特征标签") {
        TagRepository(db).rebuildAll()
        val counts = TagRepository(db).counts()
        val top = counts.take(5).joinToString("、") { "${it.displayName} ${it.cnt}" }
        "标签完成，共 ${counts.size} 类：$top"
    }

    /** 重建重复/相似分组。 */
    fun rebuildDuplicates() = runTask("正在重建重复分组") {
        DuplicateRepository(db).rebuild()
        val groups = db.duplicateDao().activeGroups()
        val exact = groups.count { it.groupType.name == "EXACT" }
        val visual = groups.count { it.groupType.name == "VISUAL" }
        "重复分组完成：完全重复 $exact 组，视觉相似 $visual 组"
    }

    /**
     * 启动行级坐标回填。
     *
     * 之前的「已完成」任务，target 集已经变了（剩余量不同），
     * 这里显式把 total 重置为当前剩余，否则进度条会倒着走。
     */
    fun startLineBackfill() {
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) { db.ocrDao().countMissingLineBounds() }
            if (remaining <= 0) {
                _ui.value = _ui.value.copy(lastResult = "所有截图都已有行级坐标，无需回填")
                return@launch
            }
            withContext(Dispatchers.IO) {
                val prev = db.taskStateDao().get(LineBackfillWorker.TASK_KIND)
                val needsFreshBaseline = prev == null ||
                    prev.status == "COMPLETED" ||
                    prev.status == "FAILED" ||
                    prev.totalCount < remaining
                if (needsFreshBaseline) {
                    db.taskStateDao().put(
                        io.github.wlmosv.screenbox.data.db.TaskStateEntity(
                            kind = LineBackfillWorker.TASK_KIND,
                            status = "PAUSED",
                            totalCount = remaining,
                            doneCount = 0,
                            startedAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
            // 前台通道：停在设置页时全速跑（后台通道实测被系统限速到
            // 150 秒 4 张，7930 张要 80 小时，等于没有）。
            LineBackfillRunner.start(getApplication()) { done, remaining ->
                _ui.value = _ui.value.copy(
                    lineTaskDone = done,
                    lineTaskRemaining = remaining,
                )
                if (remaining <= 0) refreshStats()
            }
            _ui.value = _ui.value.copy(
                lastResult = "开始回填。停留在本页会全速处理；离开后自动转为后台接力。",
            )
        }
    }

    /**
     * 重试失败项。
     *
     * 失败通常是「文件当时读不到」——删掉了、MediaStore 还没索引、
     * 或者权限刚被撤销。用户补完权限后需要能重新试一次，
     * 否则这批图永远停在失败状态。
     */
    fun retryFailedLineBounds() {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                val n = db.ocrDao().countFailedLineBounds()
                if (n > 0) db.ocrDao().resetFailedLineBounds()
                n
            }
            if (n == 0) {
                _ui.value = _ui.value.copy(lastResult = "没有需要重试的失败项")
                return@launch
            }
            refreshStats()
            _ui.value = _ui.value.copy(lastResult = "已重置 $n 张失败项，可再次开始回填")
        }
    }

    fun pauseLineBackfill() {
        LineBackfillRunner.stop()
        LineBackfillWorker.pause(getApplication())
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.taskStateDao().updateStatus(
                    LineBackfillWorker.TASK_KIND,
                    "PAUSED",
                    System.currentTimeMillis(),
                )
            }
            _ui.value = _ui.value.copy(lastResult = "已暂停，随后可继续")
        }
    }

    /** 重建清理建议。 */
    fun rebuildSuggestions() = runTask("正在重建清理建议") {
        Graph.suggestionRepo.rebuild()
        val open = db.cleanupDao().allOpen()
        "清理建议完成：${open.size} 条待确认"
    }

    private fun runTask(label: String, block: suspend () -> String) {
        if (_ui.value.busy != null) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = label, lastResult = null)
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            _ui.value = _ui.value.copy(
                busy = null,
                lastResult = result.getOrElse { "失败：${it.message ?: it.javaClass.simpleName}" },
            )
            refreshStats()
        }
    }
}
