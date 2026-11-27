package io.github.wlmosv.screenbox.presentation.cleanup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.data.repo.DuplicateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GroupMember(
    val shot: ScreenshotEntity,
    val similarity: Float,
    val isRecommendedKeep: Boolean,
    val isProtected: Boolean,
)

data class GroupUiState(
    val groupId: Long = -1,
    val members: List<GroupMember> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val loading: Boolean = true,
    val totalBytes: Long = 0,
    val selectedBytes: Long = 0,
)

/**
 * 重复/相似组对比页 ViewModel。
 *
 * 为什么需要独立页：
 * v2 把组内成员平铺在长列表里，用户无法对比。
 * 实际场景是"这两张到底是不是同一张图"——需要并排看。
 */
class GroupDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Graph.db
    private val _uiState = MutableStateFlow(GroupUiState())
    val uiState: StateFlow<GroupUiState> = _uiState.asStateFlow()

    fun load(groupId: Long) {
        _uiState.value = _uiState.value.copy(groupId = groupId, loading = true)
        viewModelScope.launch {
            val members = withContext(Dispatchers.IO) {
                val dupDao = db.duplicateDao()
                val rels = dupDao.membersOf(groupId)
                val shots = db.screenshotDao().byIds(rels.map { it.screenshotId })
                val keeper = DuplicateRepository(db).pickKeeper(shots)
                rels.mapNotNull { rel ->
                    shots.firstOrNull { it.id == rel.screenshotId }?.let { shot ->
                        GroupMember(
                            shot = shot,
                            similarity = rel.similarityScore,
                            isRecommendedKeep = shot.id == keeper.id,
                            isProtected = shot.protectionStatus == ProtectionStatus.PROTECTED ||
                                shot.protectionStatus == ProtectionStatus.FAVORITE ||
                                shot.isFavorite,
                        )
                    }
                }.sortedByDescending { it.shot.dateModified }
            }
            _uiState.value = _uiState.value.copy(
                members = members,
                loading = false,
                totalBytes = members.sumOf { it.shot.fileSize },
                // 默认选中"非推荐保留且未保护"的
                selected = members.filter { !it.isRecommendedKeep && !it.isProtected }
                    .map { it.shot.id }.toSet(),
            )
        }
    }

    fun toggle(id: Long) {
        val m = _uiState.value.members.firstOrNull { it.shot.id == id } ?: return
        if (m.isProtected) return
        val next = _uiState.value.selected.let { if (it.contains(id)) it - id else it + id }
        _uiState.value = _uiState.value.copy(
            selected = next,
            selectedBytes = _uiState.value.members.filter { it.shot.id in next }.sumOf { it.shot.fileSize },
        )
    }

    /** 只保留一张：选中除指定 id 外所有未保护的。 */
    fun keepOnly(keepId: Long) {
        val next = _uiState.value.members
            .filter { it.shot.id != keepId && !it.isProtected }
            .map { it.shot.id }.toSet()
        _uiState.value = _uiState.value.copy(
            selected = next,
            selectedBytes = _uiState.value.members.filter { it.shot.id in next }.sumOf { it.shot.fileSize },
        )
    }

    fun protect(id: Long) {
        viewModelScope.launch {
            Graph.suggestionRepo.protectAndClear(id)
            load(_uiState.value.groupId)
        }
    }

    fun dismissGroup() {
        viewModelScope.launch {
            Graph.duplicateRepo.dismissGroup(_uiState.value.groupId)
            load(_uiState.value.groupId)
        }
    }
}
