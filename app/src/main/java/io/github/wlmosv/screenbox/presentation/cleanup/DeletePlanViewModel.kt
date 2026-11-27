package io.github.wlmosv.screenbox.presentation.cleanup

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.CleanupOperationEntity
import io.github.wlmosv.screenbox.data.db.OperationStatus
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.data.repo.DeletePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 删除清单的分组统计。 */
data class PlanBreakdown(
    val label: String,
    val count: Int,
    val bytes: Long,
)

data class DeletePlanUiState(
    val loading: Boolean = true,
    val plan: DeletePlan? = null,
    val breakdown: List<PlanBreakdown> = emptyList(),
    val protectedCount: Int = 0,
    val favoriteCount: Int = 0,
    val missingCount: Int = 0,
    val pendingOperationId: Long? = null,
    val pendingSender: IntentSender? = null,
    val resultMessage: String? = null,
    val executing: Boolean = false,
    /**
     * 选择已失效（进程被杀、token 过期）。
     *
     * 必须显式区分这个状态：不能当成"没有可删除的截图"，
     * 否则用户会以为保护机制拦下了，实际是选择丢了。
     */
    val expired: Boolean = false,
)

/**
 * 删除清单页 ViewModel。
 *
 * 为什么必须独立成页（而不是弹窗）：
 * 任务书要求展示「按分类统计、按来源统计、保护项数量、风险项数量」，
 * 弹窗装不下这些信息，用户也无法逐张核对。
 *
 * 安全链路（与 v2 一致，不放松）：
 * 1. buildPlan 过滤保护/收藏/已删除
 * 2. 复查文件是否还存在
 * 3. 建操作单（PENDING）
 * 4. 拉起系统授权
 * 5. 按真实结果逐条落账
 * 6. 系统说成功但文件还在 → 记 FAILED，不谎报
 */
class DeletePlanViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Graph.db
    private val deleteRepo = Graph.deleteRepo

    private val _uiState = MutableStateFlow(DeletePlanUiState())
    val uiState: StateFlow<DeletePlanUiState> = _uiState.asStateFlow()

    /** 由导航参数传入的 id 集合。 */
    private var targetIds: List<Long> = emptyList()

    /** 由导航 token 取真实 id 集合。 */
    fun prepareFromToken(token: String) {
        val entry = io.github.wlmosv.screenbox.core.PendingDeleteStore.take(token)
        if (entry == null) {
            _uiState.value = DeletePlanUiState(
                loading = false,
                expired = true,
            )
            return
        }
        prepare(entry.ids)
    }

    fun prepare(ids: List<Long>) {
        targetIds = ids
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val plan = withContext(Dispatchers.IO) {
                deleteRepo.buildPlan(ids)
            }
            val breakdown = withContext(Dispatchers.IO) {
                buildBreakdown(plan)
            }
            val favorites = plan.blockedProtected.count { it.isFavorite }
            _uiState.value = _uiState.value.copy(
                loading = false,
                plan = plan,
                breakdown = breakdown,
                protectedCount = plan.blockedProtected.size - favorites,
                favoriteCount = favorites,
                missingCount = plan.missing.size,
            )
        }
    }

    private suspend fun buildBreakdown(plan: DeletePlan): List<PlanBreakdown> {
        val shots = plan.deletable
        if (shots.isEmpty()) return emptyList()
        val catDao = db.categoryDao()
        val srcDao = db.sourceAppDao()
        val cats = catDao.allNow().associateBy { it.id }
        val srcs = srcDao.allNow().associateBy { it.id }

        // 批量取关联，避免 8000 次逐条查询（R6 实测 P0：逐条会卡 40 秒+）
        val ids = shots.map { it.id }
        val catRelByShot = catDao.relationsForIds(ids).groupBy { it.screenshotId }
        val srcByShot = srcDao.sourcesForIds(ids).associateBy { it.screenshotId }

        val byCat = mutableMapOf<String, Pair<Int, Long>>()
        val bySrc = mutableMapOf<String, Pair<Int, Long>>()

        for (shot in shots) {
            val rel = catRelByShot[shot.id]?.firstOrNull()
            val catName = rel?.let { cats[it.categoryId]?.displayName } ?: "未分类"
            val prevCat = byCat[catName] ?: (0 to 0L)
            byCat[catName] = (prevCat.first + 1) to (prevCat.second + shot.fileSize)

            val src = srcByShot[shot.id]
            val srcName = src?.let { srcs[it.sourceAppId]?.displayName } ?: "来源不明"
            val prevSrc = bySrc[srcName] ?: (0 to 0L)
            bySrc[srcName] = (prevSrc.first + 1) to (prevSrc.second + shot.fileSize)
        }

        return buildList {
            byCat.entries.sortedByDescending { it.value.first }.take(6).forEach {
                add(PlanBreakdown(it.key, it.value.first, it.value.second))
            }
            bySrc.entries.sortedByDescending { it.value.first }.take(6).forEach {
                add(PlanBreakdown("来源 · ${it.key}", it.value.first, it.value.second))
            }
        }
    }

    /** 用户点「确认删除」→ 建操作单并返回系统授权 IntentSender。 */
    fun execute() {
        val plan = _uiState.value.plan ?: return
        if (!plan.canProceed) {
            _uiState.value = _uiState.value.copy(resultMessage = "没有可删除的截图（都被保护或已不存在）")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(executing = true)
            val (opId, sender) = withContext(Dispatchers.IO) {
                deleteRepo.createOperation(plan)
            }
            _uiState.value = _uiState.value.copy(
                pendingOperationId = opId,
                pendingSender = sender,
                executing = false,
            )
            if (sender == null) {
                finalize(opId, true)
            }
        }
    }

    fun finalize(opId: Long, confirmed: Boolean) {
        viewModelScope.launch {
            deleteRepo.finalizeOperation(opId, confirmed)
            val msg = if (confirmed) "已删除，索引已更新" else "已取消，没有任何文件被删除"
            _uiState.value = _uiState.value.copy(
                pendingOperationId = null,
                pendingSender = null,
                resultMessage = msg,
            )
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(resultMessage = null)
    }
}
