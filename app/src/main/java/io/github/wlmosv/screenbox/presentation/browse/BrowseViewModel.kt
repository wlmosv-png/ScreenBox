package io.github.wlmosv.screenbox.presentation.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.OcrStatus
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.data.repo.ScreenshotQueryRepository
import io.github.wlmosv.screenbox.domain.query.ShotFilter
import io.github.wlmosv.screenbox.domain.query.ShotSort
import io.github.wlmosv.screenbox.domain.query.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 时间快捷选项。 */
data class TimeOption(val label: String, val window: TimeWindow)

/** 大小快捷选项。 */
data class SizeOption(val label: String, val minBytes: Long?)

data class BrowseUiState(
    val filter: ShotFilter = ShotFilter(),
    val sort: ShotSort = ShotSort.NEWEST,
    val items: List<ScreenshotEntity> = emptyList(),
    /** 当前筛选的总数（不受分页限制）。 */
    val total: Int = 0,
    val selected: Set<Long> = emptySet(),
    val selectedBytes: Long = 0,
    val loading: Boolean = false,
    val categories: List<ScreenshotQueryRepository.FacetCount> = emptyList(),
    val sources: List<ScreenshotQueryRepository.FacetCount> = emptyList(),
    /** 图片特征标签（长截图/二维码/大文件…）。 */
    val tags: List<ScreenshotQueryRepository.FacetCount> = emptyList(),
    /** 筛选条件的人类可读描述。 */
    val description: List<String> = emptyList(),
    /** 用户保存过的筛选条件。 */
    val savedFilters: List<io.github.wlmosv.screenbox.data.db.SavedFilterEntity> = emptyList(),
    val toast: String? = null,
)

/**
 * 统一筛选浏览 ViewModel。
 *
 * ══ 设计目标 ══
 *
 * 一个页面承载所有"按条件找图 → 批量操作"的场景：
 *   按内容分类 / 按来源应用 / 按时间 / 按大小 / 按重复关系 / 关键词
 * 全部可组合，结果可全选、反选、批量保护/收藏/删除。
 *
 * ══ 与旧实现的关键差异 ══
 *
 * 1. 全选作用于**完整筛选结果**（走 allIds），不是当前页
 * 2. 筛选条件不可变对象 ShotFilter，任何维度可叠加
 * 3. 分类/来源的计数从 facet 查询来，不受当前已选条件干扰（避免自己被自己过滤没了）
 * 4. 收藏/保护的排除由 UI 层的开关控制，但删除前的最终检查在 DeleteRepository
 */
class BrowseViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val PAGE = 300
    }

    private val db = Graph.db
    private val queryRepo = Graph.queryRepo

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private val _catNames = mutableMapOf<String, String>()
    private val _srcNames = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.categoryDao().allNow().forEach { _catNames[it.key] = it.displayName }
                db.sourceAppDao().allNow().forEach { _srcNames[it.key] = it.displayName }
            }
            refreshFacets()
            loadSaved()
            reload()
        }
    }

    /** 从外部预设初始筛选（首页入口传进来）。 */
    fun applyPreset(filter: ShotFilter) {
        _uiState.value = _uiState.value.copy(filter = filter)
        reload()
    }

    fun setFilter(filter: ShotFilter) {
        _uiState.value = _uiState.value.copy(filter = filter, selected = emptySet(), selectedBytes = 0)
        reload()
    }

    fun toggleCategory(key: String) {
        setFilter(_uiState.value.filter.toggleCategory(key))
    }

    fun toggleSource(key: String) {
        setFilter(_uiState.value.filter.toggleSource(key))
    }

    fun toggleFeatureTag(key: String) {
        setFilter(_uiState.value.filter.toggleFeatureTag(key))
    }

    fun setTime(w: TimeWindow) {
        setFilter(_uiState.value.filter.withTime(w))
    }

    fun setMinBytes(b: Long?) {
        setFilter(_uiState.value.filter.withMinBytes(b))
    }

    fun setFavorite(v: Boolean?) {
        setFilter(_uiState.value.filter.withFavorite(v))
    }

    fun setProtected(v: Boolean?) {
        setFilter(_uiState.value.filter.withProtected(v))
    }

    fun toggleOcrRecognized() {
        val cur = _uiState.value.filter.ocrStatuses
        val next = if (cur.isEmpty()) {
            setOf(OcrStatus.SUCCESS, OcrStatus.PARTIAL)
        } else {
            emptySet()
        }
        setFilter(_uiState.value.filter.withOcr(next))
    }

    fun toggleOcrUnrecognized() {
        val cur = _uiState.value.filter.ocrStatuses
        val target = setOf(OcrStatus.NOT_STARTED, OcrStatus.QUEUED, OcrStatus.FAILED)
        val next = if (cur == target) emptySet() else target
        setFilter(_uiState.value.filter.withOcr(next))
    }

    fun toggleExactDuplicate() {
        setFilter(_uiState.value.filter.copy(exactDuplicateOnly = !_uiState.value.filter.exactDuplicateOnly))
    }

    fun toggleSimilar() {
        setFilter(_uiState.value.filter.copy(similarOnly = !_uiState.value.filter.similarOnly))
    }

    fun toggleRedundant() {
        setFilter(_uiState.value.filter.copy(redundantOnly = !_uiState.value.filter.redundantOnly))
    }

    fun clearAll() {
        setFilter(ShotFilter())
    }

    fun setSort(sort: ShotSort) {
        _uiState.value = _uiState.value.copy(sort = sort)
        reload()
    }

    // ── 保存的筛选条件 ──

    private fun loadSaved() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { db.savedFilterDao().allNow() }.getOrDefault(emptyList())
            }
            _uiState.value = _uiState.value.copy(savedFilters = list)
        }
    }

    /** 保存当前条件；同名覆盖。 */
    fun saveCurrentFilter(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val f = _uiState.value.filter
        if (f.copy(textQuery = null, parsedQuery = null).isEmpty && f.textQuery.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(toast = "当前没有可保存的条件")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.savedFilterDao().deleteByName(trimmed)
                db.savedFilterDao().put(
                    io.github.wlmosv.screenbox.data.db.SavedFilterEntity(
                        name = trimmed,
                        payload = io.github.wlmosv.screenbox.domain.query.SavedFilterCodec.encode(f),
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }
            loadSaved()
            _uiState.value = _uiState.value.copy(toast = "已保存「$trimmed」")
        }
    }

    fun applySaved(entity: io.github.wlmosv.screenbox.data.db.SavedFilterEntity) {
        val f = io.github.wlmosv.screenbox.domain.query.SavedFilterCodec.decode(entity.payload)
        if (f == null) {
            _uiState.value = _uiState.value.copy(toast = "这条筛选读不出来，可能来自旧版本")
            return
        }
        setFilter(f)
        _uiState.value = _uiState.value.copy(toast = "已应用「${entity.name}」")
    }

    fun deleteSaved(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.savedFilterDao().delete(id) }
            loadSaved()
        }
    }

    /** 重新执行查询。 */
    fun reload() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val f = _uiState.value.filter
            val s = _uiState.value.sort
            val (items, total) = withContext(Dispatchers.IO) {
                val list = queryRepo.query(f, s, limit = PAGE, offset = 0)
                val cnt = queryRepo.count(f)
                list to cnt
            }
            _uiState.value = _uiState.value.copy(
                items = items,
                total = total,
                loading = false,
                description = f.describe(_catNames, _srcNames),
            )
        }
    }

    private fun refreshFacets() {
        viewModelScope.launch {
            val (cats, srcs, tgs) = withContext(Dispatchers.IO) {
                Triple(
                    queryRepo.categoryFacets(ShotFilter()),
                    queryRepo.sourceFacets(),
                    queryRepo.tagFacets(),
                )
            }
            _uiState.value = _uiState.value.copy(categories = cats, sources = srcs, tags = tgs)
        }
    }

    // ── 选择 ──

    fun toggle(id: Long) {
        val item = _uiState.value.items.firstOrNull { it.id == id } ?: return
        if (item.protectionStatus == io.github.wlmosv.screenbox.data.db.ProtectionStatus.PROTECTED ||
            item.isFavorite
        ) {
            _uiState.value = _uiState.value.copy(toast = "这张图受保护，不能选择")
            return
        }
        val next = _uiState.value.selected.let { if (it.contains(id)) it - id else it + id }
        _uiState.value = _uiState.value.copy(
            selected = next,
            selectedBytes = _uiState.value.items.filter { it.id in next }.sumOf { it.fileSize },
        )
    }

    /**
     * 全选当前筛选结果。
     *
     * 关键：走 allIds() 查出**完整结果集**，不是当前页。
     * 500 张筛选结果就选 500 张，不管现在只加载了 300 张。
     */
    fun selectAllFiltered() {
        viewModelScope.launch {
            val f = _uiState.value.filter
            val ids = withContext(Dispatchers.IO) {
                queryRepo.allIds(f.copy(favorite = null, protected = null))
            }
            // 排除受保护和收藏
            val safeIds = withContext(Dispatchers.IO) {
                db.screenshotDao().filterSelectable(ids)
            }
            val bytes = withContext(Dispatchers.IO) {
                db.screenshotDao().sumBytes(safeIds)
            }
            _uiState.value = _uiState.value.copy(
                selected = safeIds.toSet(),
                selectedBytes = bytes,
                toast = if (safeIds.size < ids.size) {
                    "已选 ${safeIds.size} 张（排除 ${ids.size - safeIds.size} 张受保护/收藏）"
                } else "已选 ${safeIds.size} 张",
            )
        }
    }

    fun invertSelection() {
        viewModelScope.launch {
            val f = _uiState.value.filter
            val all = withContext(Dispatchers.IO) { queryRepo.allIds(f) }
            val selectable = withContext(Dispatchers.IO) { db.screenshotDao().filterSelectable(all) }
            val next = selectable.toSet() - _uiState.value.selected
            val bytes = withContext(Dispatchers.IO) { db.screenshotDao().sumBytes(next.toList()) }
            _uiState.value = _uiState.value.copy(selected = next, selectedBytes = bytes)
        }
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selected = emptySet(), selectedBytes = 0)
    }

    // ── 批量操作 ──

    fun protectSelected() {
        val ids = _uiState.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ids.forEach { Graph.suggestionRepo.protectAndClear(it) }
            }
            _uiState.value = _uiState.value.copy(
                selected = emptySet(),
                selectedBytes = 0,
                toast = "已保护 ${ids.size} 张",
            )
            reload()
            refreshFacets()
        }
    }

    fun favoriteSelected() {
        val ids = _uiState.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ids.forEach { Graph.suggestionRepo.favoriteAndClear(it) }
            }
            _uiState.value = _uiState.value.copy(
                selected = emptySet(),
                selectedBytes = 0,
                toast = "已收藏 ${ids.size} 张",
            )
            reload()
        }
    }

    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }
}