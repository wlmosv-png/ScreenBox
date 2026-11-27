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

/** 组内成员，带差异信息。 */
data class CompareMember(
    val shot: ScreenshotEntity,
    val similarityToBase: Float,
    val isRecommendedKeep: Boolean,
    val isProtected: Boolean,
    /** 相对基准图的差异描述。 */
    val diffNote: String,
)

data class CompareUiState(
    val groupId: Long = -1,
    val members: List<CompareMember> = emptyList(),
    val loading: Boolean = true,
    /** 当前作为对比基准的图片 id。 */
    val baseId: Long? = null,
    /** 用户选中要删除的。 */
    val selected: Set<Long> = emptySet(),
    val selectedBytes: Long = 0,
    val totalBytes: Long = 0,
    val maxSimilarity: Float = 0f,
    val minSimilarity: Float = 0f,
)

/**
 * 相似组并排对比 ViewModel。
 *
 * ══ 为什么重做 ══
 *
 * R3 只是复用旧的 GroupDetailScreen（2 列网格），没有"对比"能力。
 * 用户真正要判断的是"这两张到底哪里不一样" ——
 * 需要：并排看、放大看、知道相似度和差异在哪。
 *
 * ══ 设计 ══
 *
 * 1. 一张基准图 + 其他成员并排/切换对比
 * 2. 每张标注与基准的相似度
 * 3. 差异信息：尺寸、大小、时间差
 * 4. 默认不选中任何图 —— 相似不等于该删
 */
class CompareViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Graph.db
    private val _uiState = MutableStateFlow(CompareUiState())
    val uiState: StateFlow<CompareUiState> = _uiState.asStateFlow()

    fun load(groupId: Long) {
        _uiState.value = _uiState.value.copy(groupId = groupId, loading = true)
        viewModelScope.launch {
            val (members, baseId) = withContext(Dispatchers.IO) { buildMembers(groupId) }
            _uiState.value = CompareUiState(
                groupId = groupId,
                members = members,
                loading = false,
                baseId = baseId,
                // 默认不选中：相似不等于该删
                selected = emptySet(),
                totalBytes = members.sumOf { it.shot.fileSize },
                maxSimilarity = members.maxOfOrNull { it.similarityToBase } ?: 0f,
                minSimilarity = members.minOfOrNull { it.similarityToBase } ?: 0f,
            )
        }
    }

    private suspend fun buildMembers(groupId: Long): Pair<List<CompareMember>, Long?> {
        val dupDao = db.duplicateDao()
        val rels = dupDao.membersOf(groupId)
        val shots = db.screenshotDao().byIds(rels.map { it.screenshotId })
        if (shots.isEmpty()) return emptyList<CompareMember>() to null

        val keeper = DuplicateRepository(db).pickKeeper(shots)
        val base = shots.firstOrNull { it.id == keeper.id } ?: shots.first()

        val out = shots.map { shot ->
            val rel = rels.firstOrNull { it.screenshotId == shot.id }
            val sim = if (shot.id == base.id) 1f else (rel?.similarityScore ?: 0f)

            // 差异描述
            val diffs = buildList {
                if (shot.width != base.width || shot.height != base.height) {
                    add("尺寸 ${shot.width}×${shot.height} vs ${base.width}×${base.height}")
                }
                val sizeDiff = shot.fileSize - base.fileSize
                if (kotlin.math.abs(sizeDiff) > base.fileSize * 0.1) {
                    add("体积 ${if (sizeDiff > 0) "+" else ""}${sizeDiff / 1024} KB")
                }
                val timeDiff = (shot.dateModified - base.dateModified) / (1000 * 60)
                if (kotlin.math.abs(timeDiff) > 1) {
                    add("时间差 ${if (timeDiff > 0) "+" else ""}${timeDiff} 分钟")
                }
            }

            CompareMember(
                shot = shot,
                similarityToBase = sim,
                isRecommendedKeep = shot.id == keeper.id,
                isProtected = shot.protectionStatus == ProtectionStatus.PROTECTED ||
                    shot.protectionStatus == ProtectionStatus.FAVORITE ||
                    shot.isFavorite,
                diffNote = if (diffs.isEmpty()) "与基准几乎无差异" else diffs.joinToString("；"),
            )
        }.sortedByDescending { it.shot.dateModified }

        return out to base.id
    }

    /** 切换对比基准。 */
    fun setBase(id: Long) {
        val members = _uiState.value.members
        val base = members.firstOrNull { it.shot.id == id } ?: return
        val recomputed = members.map { m ->
            if (m.shot.id == id) {
                m.copy(similarityToBase = 1f, diffNote = "当前基准")
            } else {
                val diffs = buildList {
                    if (m.shot.width != base.shot.width || m.shot.height != base.shot.height) {
                        add("尺寸 ${m.shot.width}×${m.shot.height} vs ${base.shot.width}×${base.shot.height}")
                    }
                    val sizeDiff = m.shot.fileSize - base.shot.fileSize
                    if (kotlin.math.abs(sizeDiff) > base.shot.fileSize * 0.1) {
                        add("体积 ${if (sizeDiff > 0) "+" else ""}${sizeDiff / 1024} KB")
                    }
                    val timeDiff = (m.shot.dateModified - base.shot.dateModified) / (1000 * 60)
                    if (kotlin.math.abs(timeDiff) > 1) {
                        add("时间差 ${if (timeDiff > 0) "+" else ""}${timeDiff} 分钟")
                    }
                }
                m.copy(
                    diffNote = if (diffs.isEmpty()) "与基准几乎无差异" else diffs.joinToString("；"),
                )
            }
        }
        _uiState.value = _uiState.value.copy(members = recomputed, baseId = id)
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

    /** 只保留这张：选中其余所有未保护的。 */
    fun keepOnly(keepId: Long) {
        val next = _uiState.value.members
            .filter { it.shot.id != keepId && !it.isProtected }
            .map { it.shot.id }.toSet()
        _uiState.value = _uiState.value.copy(
            selected = next,
            selectedBytes = _uiState.value.members.filter { it.shot.id in next }.sumOf { it.shot.fileSize },
        )
    }

    fun selectAllExceptBase() {
        val baseId = _uiState.value.baseId
        val next = _uiState.value.members
            .filter { it.shot.id != baseId && !it.isProtected }
            .map { it.shot.id }.toSet()
        _uiState.value = _uiState.value.copy(
            selected = next,
            selectedBytes = _uiState.value.members.filter { it.shot.id in next }.sumOf { it.shot.fileSize },
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selected = emptySet(), selectedBytes = 0)
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
        }
    }
}