package io.github.wlmosv.screenbox.presentation.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.OcrStatus
import io.github.wlmosv.screenbox.data.db.SavedFilterEntity
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.data.db.SearchHistoryEntity
import io.github.wlmosv.screenbox.data.repo.IoDispatcher
import io.github.wlmosv.screenbox.data.repo.ScreenshotQueryRepository
import io.github.wlmosv.screenbox.domain.query.SavedFilterCodec
import io.github.wlmosv.screenbox.domain.query.SearchQuery
import io.github.wlmosv.screenbox.domain.query.SearchQueryParser
import io.github.wlmosv.screenbox.domain.query.ShotFilter
import io.github.wlmosv.screenbox.domain.query.ShotSort
import io.github.wlmosv.screenbox.domain.query.TimeWindow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 搜索结果排序。 */
enum class SearchSort(val label: String) {
    RELEVANCE("相关度"),
    NEWEST("最新"),
    OLDEST("最旧"),
    LARGEST("最大"),
}

/** 搜索结果里展示的结构化实体。 */
data class EntityChip(
    val kindLabel: String,
    val value: String,
)

data class SearchResultItem(
    val shot: ScreenshotEntity,
    val snippet: String,
    /** 命中位置（在 snippet 内的字符区间），用于高亮。 */
    val hitRanges: List<IntRange>,
    val categoryName: String?,
    val sourceName: String?,
    /** 这张图里识别出的结构化实体（日期/金额/订单号等），最多 3 个。 */
    val entities: List<EntityChip> = emptyList(),
    /** 为什么这张图会出现在结果里。必须能说清楚，不能只给一张缩略图。 */
    val hitReason: String = "",
    /** 实际命中的词。 */
    val matchedTerm: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResultItem> = emptyList(),
    val searching: Boolean = false,
    /** 满足条件的总数（不受展示上限影响）。 */
    val totalMatches: Int = 0,
    /** 本次实际展示了多少条。 */
    val shownCount: Int = 0,
    val hasSearched: Boolean = false,
    val history: List<SearchHistoryEntity> = emptyList(),
    val sort: SearchSort = SearchSort.RELEVANCE,
    /** 二次筛选条件，与关键词分开保存，清除关键词不影响筛选。 */
    val filter: ShotFilter = ShotFilter(),
    val categories: List<ScreenshotQueryRepository.FacetCount> = emptyList(),
    val sources: List<ScreenshotQueryRepository.FacetCount> = emptyList(),
    val savedFilters: List<SavedFilterEntity> = emptyList(),
    /** OCR 未完成的图片数，用于提示「结果可能不全」。 */
    val pendingOcr: Int = 0,
    val toast: String? = null,
) {
    val hasFilter: Boolean
        get() = !filter.copy(textQuery = null, parsedQuery = null).isEmpty
}

/**
 * 搜索 ViewModel（R10）。
 *
 * ══ 与 R9 的关键差异 ══
 *
 * R9 的二次筛选是「先取 500 条，再在内存里过滤」：
 *   · 总数显示的是过滤前的数字，和列表对不上
 *   · 分类/来源只能单选，且每次过滤都逐条查数据库（N+1）
 *   · 条件无法保存，也无法在下次打开时恢复
 *
 * 现在筛选条件直接进入统一查询引擎（ShotFilter → SQL），
 * 总数与列表来自同一条 WHERE，不存在「说 300 实际 12」。
 *
 * 同时把每条结果的查询批量化：OCR 文本、分类、来源、实体各一次，
 * 300 条结果从 1200+ 次查询降到 5 次。
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** 结果展示上限。超出的用「共 N 张」明确告知，而不是假装只有这些。 */
        private const val SHOW_LIMIT = 300
    }

    private val db = Graph.db
    private val searchRepo = Graph.searchRepo
    private val queryRepo = Graph.queryRepo

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            queryFlow
                .debounce(280)
                .distinctUntilChanged()
                .collect { q -> performSearch(q) }
        }
        loadHistory()
        loadPendingOcr()
        loadFacets()
        loadSavedFilters()
    }

    // ── 输入与筛选 ──

    fun onQueryChange(q: String) {
        _uiState.value = _uiState.value.copy(query = q)
        queryFlow.value = q
        if (q.isBlank()) {
            _uiState.value = _uiState.value.copy(
                results = emptyList(),
                totalMatches = 0,
                shownCount = 0,
                hasSearched = false,
                searching = false,
            )
        }
    }

    fun setSort(sort: SearchSort) {
        _uiState.value = _uiState.value.copy(sort = sort)
        rerun()
    }

    fun toggleCategory(key: String) = updateFilter { it.toggleCategory(key) }
    fun toggleSource(key: String) = updateFilter { it.toggleSource(key) }
    fun setTime(w: TimeWindow) = updateFilter { it.withTime(w) }
    fun setMinBytes(b: Long?) = updateFilter { it.withMinBytes(b) }
    fun setFavorite(v: Boolean?) = updateFilter { it.withFavorite(v) }
    fun setProtected(v: Boolean?) = updateFilter { it.withProtected(v) }

    fun toggleOcrRecognized() = updateFilter {
        val target = setOf(OcrStatus.SUCCESS, OcrStatus.PARTIAL)
        if (it.ocrStatuses == target) it.withOcr(emptySet()) else it.withOcr(target)
    }

    fun toggleOcrUnrecognized() = updateFilter {
        val target = setOf(OcrStatus.NOT_STARTED, OcrStatus.QUEUED, OcrStatus.FAILED)
        if (it.ocrStatuses == target) it.withOcr(emptySet()) else it.withOcr(target)
    }

    fun clearFilters() = updateFilter { ShotFilter(textQuery = it.textQuery, parsedQuery = it.parsedQuery) }

    private fun updateFilter(block: (ShotFilter) -> ShotFilter) {
        val cleaned = _uiState.value.filter.copy(textQuery = null, parsedQuery = null)
        _uiState.value = _uiState.value.copy(filter = block(cleaned))
        rerun()
    }

    private fun rerun() {
        if (_uiState.value.query.isNotBlank()) {
            viewModelScope.launch { performSearch(_uiState.value.query) }
        }
    }

    // ── 历史与保存的条件 ──

    fun deleteHistory(keyword: String) {
        viewModelScope.launch {
            withContext(IoDispatcher) { db.searchHistoryDao().delete(keyword) }
            loadHistory()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(IoDispatcher) { db.searchHistoryDao().clear() }
            loadHistory()
        }
    }

    fun useHistory(keyword: String) {
        _uiState.value = _uiState.value.copy(query = keyword)
        queryFlow.value = keyword
    }

    /** 保存当前条件（含关键词）。重名覆盖，避免列表里出现两条一样的。 */
    fun saveCurrentFilter(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val f = _uiState.value.filter.copy(
            textQuery = _uiState.value.query.trim().ifBlank { null },
        ).let {
            it.copy(parsedQuery = it.textQuery?.let { q -> SearchQueryParser.parse(q) })
        }
        if (f.copy(textQuery = null, parsedQuery = null).isEmpty && f.textQuery == null) {
            _uiState.value = _uiState.value.copy(toast = "当前没有可保存的条件")
            return
        }
        viewModelScope.launch {
            withContext(IoDispatcher) {
                db.savedFilterDao().deleteByName(trimmed)
                db.savedFilterDao().put(
                    SavedFilterEntity(
                        name = trimmed,
                        payload = SavedFilterCodec.encode(f),
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }
            loadSavedFilters()
            _uiState.value = _uiState.value.copy(toast = "已保存「$trimmed」")
        }
    }

    fun applySaved(entity: SavedFilterEntity) {
        val f = SavedFilterCodec.decode(entity.payload) ?: run {
            _uiState.value = _uiState.value.copy(toast = "这条筛选读不出来，可能来自旧版本")
            return
        }
        _uiState.value = _uiState.value.copy(
            filter = f.copy(textQuery = null, parsedQuery = null),
            query = f.textQuery.orEmpty(),
            toast = "已应用「${entity.name}」",
        )
        queryFlow.value = f.textQuery.orEmpty()
        viewModelScope.launch { performSearch(f.textQuery.orEmpty()) }
    }

    fun deleteSaved(id: Long) {
        viewModelScope.launch {
            withContext(IoDispatcher) { db.savedFilterDao().delete(id) }
            loadSavedFilters()
        }
    }

    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun loadSavedFilters() {
        viewModelScope.launch {
            val list = withContext(IoDispatcher) {
                runCatching { db.savedFilterDao().allNow() }.getOrDefault(emptyList())
            }
            _uiState.value = _uiState.value.copy(savedFilters = list)
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val h = withContext(IoDispatcher) {
                runCatching { searchRepo.recentSearches(10) }.getOrDefault(emptyList())
            }
            _uiState.value = _uiState.value.copy(history = h)
        }
    }

    private fun loadPendingOcr() {
        viewModelScope.launch {
            val n = withContext(IoDispatcher) {
                runCatching { db.screenshotDao().pendingOcrCount() }.getOrDefault(0)
            }
            _uiState.value = _uiState.value.copy(pendingOcr = n)
        }
    }

    private fun loadFacets() {
        viewModelScope.launch {
            val (cats, srcs) = withContext(IoDispatcher) {
                runCatching {
                    queryRepo.categoryFacets(ShotFilter()) to queryRepo.sourceFacets()
                }.getOrDefault(emptyList<ScreenshotQueryRepository.FacetCount>() to emptyList())
            }
            _uiState.value = _uiState.value.copy(categories = cats, sources = srcs)
        }
    }

    // ── 搜索主流程 ──

    private suspend fun performSearch(q: String) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return

        _uiState.value = _uiState.value.copy(searching = true, hasSearched = true)

        val effective = _uiState.value.filter.copy(
            textQuery = trimmed,
            parsedQuery = SearchQueryParser.parse(trimmed),
        )
        val sort = _uiState.value.sort

        val (ordered, total) = withContext(IoDispatcher) {
            val items = runCatching {
                queryRepo.query(effective, sort.toShotSort(), limit = SHOW_LIMIT)
            }.getOrDefault(emptyList())
            val count = runCatching { queryRepo.count(effective) }.getOrDefault(items.size)
            items to count
        }

        val ids = ordered.map { it.id }
        val parsed = effective.parsedQuery
        val terms = parsed?.let { positiveTerms(it) }.orEmpty()

        // 批量取上下文：5 次查询覆盖整页，而不是每张图 4 次。
        val (texts, catMap, srcMap, entMap) = withContext(IoDispatcher) {
            val texts = runCatching {
                db.ocrDao().textsOf(ids).associate { it.screenshotId to it.text }
            }.getOrDefault(emptyMap())
            val catDao = db.categoryDao()
            val allCats = runCatching { catDao.allNow().associateBy { it.id } }.getOrDefault(emptyMap())
            val rels = runCatching { catDao.relationsForIds(ids) }.getOrDefault(emptyList())
                .groupBy { it.screenshotId }
            val catNames = rels.mapValues { (_, list) ->
                list.mapNotNull { allCats[it.categoryId]?.displayName }.firstOrNull()
            }
            val srcDao = db.sourceAppDao()
            val allSrcs = runCatching { srcDao.allNow().associateBy { it.id } }.getOrDefault(emptyMap())
            val srcRels = runCatching { srcDao.sourcesForIds(ids) }.getOrDefault(emptyList())
                .associateBy { it.screenshotId }
            val srcNames = srcRels.mapValues { (_, rel) -> allSrcs[rel.sourceAppId]?.displayName }
            val ents = runCatching { db.entityDao().forIds(ids) }.getOrDefault(emptyList())
                .groupBy { it.screenshotId }
            Quad(texts, catNames, srcNames, ents)
        }

        val items = ordered.map { shot ->
            val text = texts[shot.id].orEmpty()
            val term = terms.firstOrNull { text.contains(it, ignoreCase = true) }
            val snippet = snippetOf(text, term ?: terms.firstOrNull(), radius = 40)
            val ranges = if (term != null) hitRanges(snippet, term) else emptyList()
            val ents = entMap[shot.id].orEmpty()
            val entHit = parsed != null && parsed.looksStructured &&
                parsed.structuredCandidates().any { cand ->
                    ents.any { it.normalized.contains(cand, ignoreCase = true) }
                }
            val reason = when {
                term != null -> "文字命中「$term」"
                entHit -> "实体命中"
                else -> "条件命中"
            }
            SearchResultItem(
                shot = shot,
                snippet = snippet,
                hitRanges = ranges,
                categoryName = catMap[shot.id],
                sourceName = srcMap[shot.id],
                entities = ents.filter { it.confidence >= 0.6f }
                    .take(3)
                    .map {
                        EntityChip(
                            kindLabel = kindLabelOf(it.kind),
                            value = displayValueOf(it.kind, it.normalized),
                        )
                    },
                hitReason = reason,
                matchedTerm = term,
            )
        }

        val sorted = when (sort) {
            SearchSort.RELEVANCE -> items.sortedByDescending { it.hitRanges.size }
            SearchSort.NEWEST -> items.sortedByDescending { it.shot.dateModified }
            SearchSort.OLDEST -> items.sortedBy { it.shot.dateModified }
            SearchSort.LARGEST -> items.sortedByDescending { it.shot.fileSize }
        }

        if (total > 0) {
            withContext(IoDispatcher) {
                runCatching { searchRepo.recordHistory(trimmed, total) }
            }
            loadHistory()
        }

        _uiState.value = _uiState.value.copy(
            results = sorted,
            totalMatches = total,
            shownCount = sorted.size,
            searching = false,
        )
    }

    private data class Quad(
        val texts: Map<Long, String>,
        val cats: Map<Long, String?>,
        val srcs: Map<Long, String?>,
        val ents: Map<Long, List<io.github.wlmosv.screenbox.data.db.StructuredEntityEntity>>,
    )

    /** 只取正向条件；排除词不作为高亮依据。 */
    private fun positiveTerms(q: SearchQuery): List<String> =
        (q.andGroups.flatten() + q.phrases).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun snippetOf(text: String, term: String?, radius: Int): String {
        if (text.isBlank()) return ""
        val flat = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (term == null) return flat.take(90)
        val idx = flat.indexOf(term, ignoreCase = true)
        if (idx < 0) return flat.take(90)
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + term.length + radius).coerceAtMost(flat.length)
        return buildString {
            if (start > 0) append("…")
            append(flat.substring(start, end).trim())
            if (end < flat.length) append("…")
        }
    }

    private fun hitRanges(text: String, keyword: String): List<IntRange> {
        if (keyword.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var idx = text.indexOf(keyword, ignoreCase = true)
        while (idx >= 0 && out.size < 20) {
            out += idx until (idx + keyword.length)
            idx = text.indexOf(keyword, idx + keyword.length, ignoreCase = true)
        }
        return out
    }

    private fun kindLabelOf(kind: String): String = when (kind) {
        "DATE" -> "日期"
        "AMOUNT" -> "金额"
        "ORDER_NO" -> "订单号"
        "PHONE" -> "手机号"
        "URL" -> "网址"
        "EMAIL" -> "邮箱"
        "TRACKING" -> "物流号"
        "TIME" -> "时间"
        else -> kind
    }

    private fun displayValueOf(kind: String, value: String): String = when (kind) {
        "URL" -> if (value.length > 32) value.take(32) + "…" else value
        "PHONE" -> if (value.length >= 7) value.take(3) + "****" + value.takeLast(2) else value
        "AMOUNT" -> "¥$value"
        else -> value
    }
}

private fun SearchSort.toShotSort(): ShotSort = when (this) {
    SearchSort.RELEVANCE, SearchSort.NEWEST -> ShotSort.NEWEST
    SearchSort.OLDEST -> ShotSort.OLDEST
    SearchSort.LARGEST -> ShotSort.LARGEST
}
