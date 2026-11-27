package io.github.wlmosv.screenbox.presentation.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.OcrStatus
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GalleryFilter(val label: String) {
    ALL("全部"),
    FAVORITE("收藏"),
    PROTECTED("已保护"),
    UNRECOGNIZED("未识别"),
    LARGE("大文件"),
}

enum class GallerySort(val label: String) {
    NEWEST("最新"),
    OLDEST("最旧"),
    LARGEST("最大"),
    SMALLEST("最小"),
}

data class GalleryUiState(
    val items: List<ScreenshotEntity> = emptyList(),
    val filter: GalleryFilter = GalleryFilter.ALL,
    val sort: GallerySort = GallerySort.NEWEST,
    val selectedIds: Set<Long> = emptySet(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val totalCount: Int = 0,
    val hasMore: Boolean = true,
    /** 一次性提示（全选结果、反选结果）。 */
    val toast: String? = null,
) {
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
}

/**
 * 截图页 ViewModel（v3）。
 *
 * 修 v2 的三个问题：
 * 1. 硬编码 paged(2000, 0) → 改成真分页，滚到底自动加载
 * 2. 筛选只有 5 个状态且互斥 → 保留状态筛选，但去掉布局溢出的"待清理"
 * 3. 排序只有一个 → 支持 4 种
 */
class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val PAGE = 200
    }

    private val db = Graph.db
    private val dao = db.screenshotDao()

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun setFilter(f: GalleryFilter) {
        _uiState.value = _uiState.value.copy(filter = f, selectedIds = emptySet())
        reload()
    }

    fun setSort(s: GallerySort) {
        _uiState.value = _uiState.value.copy(sort = s)
        reload()
    }

    /**
     * 当前筛选与排序下的完整结果。
     *
     * ══ 为什么要整表排序 ══
     *
     * 旧实现每页 200 条各自排序后再拼接。用户选「最大」时，
     * 第 1 页是第 1 页里最大的 200 张，第 2 页又是另一批 ——
     * 全局顺序是错的，用户会以为更大的图不见了。
     *
     * 8000 条约 1-2 MB，一次性排序的代价远小于给用户一个错误的顺序。
     */
    private var cached: List<ScreenshotEntity>? = null

    private suspend fun sortedAll(): List<ScreenshotEntity> {
        cached?.let { return it }
        val out = ArrayList<ScreenshotEntity>()
        var offset = 0
        while (true) {
            val page = when (_uiState.value.filter) {
                GalleryFilter.ALL -> dao.page(1000, offset)
                GalleryFilter.FAVORITE -> dao.pageByProtection(ProtectionStatus.FAVORITE, 1000, offset)
                GalleryFilter.PROTECTED -> dao.pageByProtection(ProtectionStatus.PROTECTED, 1000, offset)
                GalleryFilter.UNRECOGNIZED -> dao.pageUnrecognized(1000, offset)
                GalleryFilter.LARGE -> dao.pageLarge(2L * 1024 * 1024, 1000, offset)
            }
            out += page
            if (page.size < 1000) break
            offset += page.size
            if (offset > 50000) break
        }
        val sorted = when (_uiState.value.sort) {
            GallerySort.NEWEST -> out.sortedByDescending { it.dateModified }
            GallerySort.OLDEST -> out.sortedBy { it.dateModified }
            GallerySort.LARGEST -> out.sortedByDescending { it.fileSize }
            GallerySort.SMALLEST -> out.sortedBy { it.fileSize }
        }
        cached = sorted
        return sorted
    }

    /** 首次加载 / 换筛选条件后重载。 */
    fun reload() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, hasMore = true)
            cached = null
            val all = withContext(Dispatchers.IO) { sortedAll() }
            val page = all.take(PAGE)
            _uiState.value = _uiState.value.copy(
                items = page,
                loading = false,
                totalCount = all.size,
                hasMore = all.size > page.size,
            )
        }
    }

    /** 滚动到底部时加载下一页（从同一份已排序结果切片，顺序不会跳）。 */
    fun loadMore() {
        val s = _uiState.value
        if (s.loadingMore || !s.hasMore || s.loading) return
        viewModelScope.launch {
            _uiState.value = s.copy(loadingMore = true)
            val all = withContext(Dispatchers.IO) { sortedAll() }
            val next = all.take(s.items.size + PAGE)
            _uiState.value = _uiState.value.copy(
                items = next,
                loadingMore = false,
                hasMore = all.size > next.size,
                totalCount = all.size,
            )
        }
    }

    private suspend fun countForFilter(): Int = when (_uiState.value.filter) {
        GalleryFilter.ALL -> dao.countActive()
        GalleryFilter.FAVORITE -> dao.countByProtection(ProtectionStatus.FAVORITE)
        GalleryFilter.PROTECTED -> dao.countByProtection(ProtectionStatus.PROTECTED)
        GalleryFilter.UNRECOGNIZED -> dao.countUnrecognized()
        GalleryFilter.LARGE -> dao.countLargeFiles(2L * 1024 * 1024)
    }

    fun toggleSelect(id: Long) {
        _uiState.value = _uiState.value.let {
            it.copy(selectedIds = if (it.selectedIds.contains(id)) it.selectedIds - id else it.selectedIds + id)
        }
    }

    fun selectAllLoaded() {
        _uiState.value = _uiState.value.copy(selectedIds = _uiState.value.items.map { it.id }.toSet())
    }

    /**
     * 全选当前筛选条件下的**全部**截图，不只是已加载的那一页。
     *
     * ══ 为什么必须区分 ══
     *
     * 旧版只有 selectAllLoaded()，用户筛出 8000 张却只选中了加载的 300 张，
     * 界面上没有任何提示 —— 这是"静默的删除范围错误"。
     *
     * 这里按筛选条件重新查全部 id，并排除受保护/收藏项。
     */
    fun selectAllFiltered() {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { allIdsForFilter() }
            val selectable = withContext(Dispatchers.IO) { dao.filterSelectable(ids) }
            _uiState.value = _uiState.value.copy(
                selectedIds = selectable.toSet(),
                toast = if (selectable.size < ids.size) {
                    "已选 ${selectable.size} 张（排除 ${ids.size - selectable.size} 张受保护/收藏）"
                } else {
                    "已选全部 ${selectable.size} 张"
                },
            )
        }
    }

    /** 反选：当前筛选结果里没被选中的，全部选中。 */
    fun invertSelection() {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { allIdsForFilter() }
            val selectable = withContext(Dispatchers.IO) { dao.filterSelectable(ids) }.toSet()
            val next = selectable - _uiState.value.selectedIds
            _uiState.value = _uiState.value.copy(
                selectedIds = next,
                toast = "反选后选中 ${next.size} 张",
            )
        }
    }

    /** 当前筛选条件下的全部 id（不分页）。 */
    private suspend fun allIdsForFilter(): List<Long> = when (_uiState.value.filter) {
        GalleryFilter.ALL -> dao.allActiveIds()
        GalleryFilter.FAVORITE -> dao.idsByProtection(ProtectionStatus.FAVORITE)
        GalleryFilter.PROTECTED -> dao.idsByProtection(ProtectionStatus.PROTECTED)
        GalleryFilter.UNRECOGNIZED -> dao.idsUnrecognized()
        GalleryFilter.LARGE -> dao.idsLarge(2L * 1024 * 1024)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedIds = emptySet())
    }

    fun setFavorite(ids: Set<Long>, favorite: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) { ids.forEach { dao.setFavorite(it, favorite, now) } }
            reload()
        }
    }

    fun setProtected(ids: Set<Long>, protect: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                ids.forEach {
                    dao.setProtection(it, if (protect) ProtectionStatus.PROTECTED else ProtectionStatus.NORMAL, now)
                }
            }
            reload()
        }
    }
}
