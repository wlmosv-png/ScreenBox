package io.github.wlmosv.screenbox.presentation.cleanup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.DuplicateGroupType
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.data.repo.DuplicateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 相似组摘要。 */
data class SimilarGroupCard(
    val groupId: Long,
    val members: List<ScreenshotEntity>,
    /** 组内最低相似度（0..1）。 */
    val minSimilarity: Float,
    val maxSimilarity: Float,
    /** 可释放空间：组内除最大一张外的总大小。 */
    val reclaimableBytes: Long,
    /** 时间跨度（最早到最新，天）。 */
    val timeSpanDays: Int,
    /** 是否有成员被保护/收藏。 */
    val hasProtected: Boolean,
)

data class SimilarGroupsUiState(
    val groups: List<SimilarGroupCard> = emptyList(),
    val loading: Boolean = true,
    /** 按相似度排序还是按时间排序。 */
    val sortBySimilarity: Boolean = true,
    val totalGroups: Int = 0,
    val totalImages: Int = 0,
)

/**
 * 相似组列表 ViewModel。
 *
 * ══ 解决什么问题 ══
 *
 * 旧版把 243 个相似组拆成 247 条独立建议，用户看到的是
 * 一堆"画面相似，需人工确认"的卡片，完全无法处理。
 *
 * 新版按组展示：每组一张代表图 + 组内数量 + 相似度范围 + 可释放空间。
 * 用户可以先扫一眼，只点开值得看的组。
 */
class SimilarGroupsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Graph.db
    private val _uiState = MutableStateFlow(SimilarGroupsUiState())
    val uiState: StateFlow<SimilarGroupsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val cards = withContext(Dispatchers.IO) {
                val dupDao = db.duplicateDao()
                val shotDao = db.screenshotDao()
                val repo = DuplicateRepository(db)

                dupDao.activeGroups()
                    .filter { it.groupType == DuplicateGroupType.VISUAL }
                    .mapNotNull { g ->
                        val rels = dupDao.membersOf(g.id)
                        val shots = shotDao.byIds(rels.map { it.screenshotId })
                        if (shots.size < 2) return@mapNotNull null

                        val sims = rels.map { it.similarityScore }
                        val keeper = repo.pickKeeper(shots)
                        // 可释放空间：除最大一张外的总和（保守估算）
                        val reclaimable = shots.sumOf { it.fileSize } - shots.maxOf { it.fileSize }
                        val times = shots.map { it.dateModified }
                        val spanDays = ((times.max() - times.min()) / (24L * 3600 * 1000)).toInt()

                        SimilarGroupCard(
                            groupId = g.id,
                            members = shots.sortedByDescending { it.dateModified },
                            minSimilarity = sims.minOrNull() ?: 0f,
                            maxSimilarity = sims.maxOrNull() ?: 0f,
                            reclaimableBytes = reclaimable,
                            timeSpanDays = spanDays,
                            hasProtected = shots.any {
                                it.protectionStatus == ProtectionStatus.PROTECTED ||
                                    it.protectionStatus == ProtectionStatus.FAVORITE ||
                                    it.isFavorite
                            },
                        )
                    }
            }
            val sorted = if (_uiState.value.sortBySimilarity) {
                cards.sortedByDescending { it.maxSimilarity }
            } else {
                cards.sortedByDescending { it.members.firstOrNull()?.dateModified ?: 0L }
            }
            _uiState.value = SimilarGroupsUiState(
                groups = sorted,
                loading = false,
                sortBySimilarity = _uiState.value.sortBySimilarity,
                totalGroups = sorted.size,
                totalImages = sorted.sumOf { it.members.size },
            )
        }
    }

    fun setSort(bySimilarity: Boolean) {
        _uiState.value = _uiState.value.copy(sortBySimilarity = bySimilarity)
        load()
    }

    /** 解散误分组。 */
    fun dismissGroup(groupId: Long) {
        viewModelScope.launch {
            Graph.duplicateRepo.dismissGroup(groupId)
            load()
        }
    }
}