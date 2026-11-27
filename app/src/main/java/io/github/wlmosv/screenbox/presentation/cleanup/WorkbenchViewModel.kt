package io.github.wlmosv.screenbox.presentation.cleanup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.data.db.SuggestionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 工作台里的一个候选类型卡片。 */
data class CandidateCard(
    val type: SuggestionType,
    val count: Int,
    val bytes: Long,
    val groupCount: Int,
    /** 该类型的一句话解释，让用户知道"为什么"。 */
    val explanation: String,
    /** 风险等级，决定按钮颜色和默认勾选行为。 */
    val risk: Risk,
) {
    enum class Risk { LOW, MEDIUM, HIGH }
}

data class WorkbenchUiState(
    val cards: List<CandidateCard> = emptyList(),
    val loading: Boolean = true,
    /** 当前展开的类型，null 表示在总览页。 */
    val activeType: SuggestionType? = null,
    /** 当前类型下的候选图片（已应用类型内筛选）。 */
    val items: List<CandidateItem> = emptyList(),
    /** 未筛选的完整候选集，用于切换筛选时快速重算。 */
    val allItems: List<CandidateItem> = emptyList(),
    /** 本类型里实际出现过的来源应用，用作筛选 chip。 */
    val sources: List<SourceOption> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val selectedBytes: Long = 0,
    /** 类型内筛选：多少天以前（null=不限）。 */
    val olderThanDays: Int? = null,
    /** 类型内筛选：最小体积（null=不限）。 */
    val minBytes: Long? = null,
    val sourceKey: String? = null,
    val toast: String? = null,
) {
    /** 当前筛选下可被选中的数量（排除保护与收藏）。 */
    val selectable: List<CandidateItem>
        get() = items.filter {
            it.shot.protectionStatus != ProtectionStatus.PROTECTED && !it.shot.isFavorite
        }
}

data class SourceOption(val key: String, val name: String, val count: Int)

data class CandidateItem(
    val shot: ScreenshotEntity,
    val reason: String,
    val evidence: String,
    val confidence: Float,
    val isRecommendedKeep: Boolean = false,
    val sourceName: String? = null,
    val sourceKey: String? = null,
)

/**
 * 清理工作台 ViewModel（R10）。
 *
 * ══ 设计目标 ══
 *
 * 旧版：306 条建议平铺，247 条是"画面相似需人工确认"，用户无法处理。
 * v3：先看类型总览，再进入某一类批量处理。
 *
 * R10 补的是「进到类型里之后仍然只能全选」的问题：
 *   · 按时间、体积、来源继续收窄，再全选
 *   · 全选只作用于当前可见集合，不会偷偷选中被筛掉的图
 *   · 受保护与收藏永远不可选，删除前还有第二次校验
 */
class WorkbenchViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Graph.db
    private val suggestionRepo = Graph.suggestionRepo

    private val _uiState = MutableStateFlow(WorkbenchUiState())
    val uiState: StateFlow<WorkbenchUiState> = _uiState.asStateFlow()

    init {
        loadOverview()
    }

    /** 加载类型总览。 */
    fun loadOverview() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, activeType = null, items = emptyList())
            val summary = withContext(Dispatchers.IO) {
                runCatching { suggestionRepo.summary() }.getOrDefault(emptyList())
            }
            val cards = summary.map { s ->
                CandidateCard(
                    type = s.type,
                    count = s.count,
                    bytes = s.bytes,
                    groupCount = s.groupCount,
                    explanation = explain(s.type),
                    risk = riskOf(s.type),
                )
            }
            _uiState.value = _uiState.value.copy(cards = cards, loading = false)
        }
    }

    private fun explain(t: SuggestionType): String = when (t) {
        SuggestionType.EXACT_DUPLICATE -> "文件字节完全一致，每组保留一份即可"
        SuggestionType.VISUAL_DUPLICATE -> "画面接近但可能内容不同，需要你对比后决定"
        SuggestionType.POSSIBLY_EXPIRED -> "截图里出现了已过期的日期"
        SuggestionType.NEAR_BLANK -> "画面近乎空白，可能是误触截屏"
        SuggestionType.LARGE_FILE -> "单张超过 2 MB，可优先检查"
        SuggestionType.LOW_INFORMATION -> "没有文字且画面简单"
        SuggestionType.LONG_UNTOUCHED -> "很久没查看（仅供参考，不构成删除理由）"
        SuggestionType.USER_PICKED -> "你手动加入的"
        SuggestionType.ONE_TIME_CODE -> "验证码截图，超过 1 天后通常已失效"
        SuggestionType.STALE_NOTIFICATION -> "聊天/游戏界面上的新消息浮窗，已超过 90 天"
        SuggestionType.OLD_GAME -> "游戏截图，已超过 180 天（可能是战绩纪念，请确认）"
        SuggestionType.OLD_SOCIAL -> "社交动态截图，已超过 180 天（可能是留证，请确认）"
    }

    private fun riskOf(t: SuggestionType): CandidateCard.Risk = when (t) {
        SuggestionType.EXACT_DUPLICATE, SuggestionType.NEAR_BLANK -> CandidateCard.Risk.LOW
        SuggestionType.POSSIBLY_EXPIRED, SuggestionType.LARGE_FILE,
        SuggestionType.LOW_INFORMATION, SuggestionType.LONG_UNTOUCHED,
        -> CandidateCard.Risk.MEDIUM
        SuggestionType.VISUAL_DUPLICATE -> CandidateCard.Risk.HIGH
        SuggestionType.USER_PICKED -> CandidateCard.Risk.LOW
        SuggestionType.STALE_NOTIFICATION -> CandidateCard.Risk.HIGH
        SuggestionType.OLD_GAME -> CandidateCard.Risk.HIGH
        SuggestionType.OLD_SOCIAL -> CandidateCard.Risk.HIGH
        SuggestionType.ONE_TIME_CODE -> CandidateCard.Risk.MEDIUM
    }

    /** 进入某一类的批量处理。 */
    fun openType(type: SuggestionType) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loading = true,
                activeType = type,
                selected = emptySet(),
                selectedBytes = 0,
                olderThanDays = null,
                minBytes = null,
                sourceKey = null,
            )
            val (items, sources) = withContext(Dispatchers.IO) {
                val loaded = loadItems(type)
                attachSources(loaded)
            }
            _uiState.value = _uiState.value.copy(
                allItems = items,
                items = items,
                sources = sources,
                loading = false,
            )
        }
    }

    private suspend fun loadItems(type: SuggestionType): List<CandidateItem> {
        val dao = db.screenshotDao()
        val cleanupDao = db.cleanupDao()

        if (type == SuggestionType.LARGE_FILE) {
            // 真分页取完，不用固定 500 上限，避免"显示 500 实际更多"。
            val out = ArrayList<CandidateItem>()
            var offset = 0
            while (true) {
                val page = dao.pageLarge(2L * 1024 * 1024, 500, offset)
                if (page.isEmpty()) break
                page.forEach { shot ->
                    out += CandidateItem(
                        shot = shot,
                        reason = "单张 %.1f MB".format(shot.fileSize / 1024.0 / 1024.0),
                        evidence = "${shot.width}×${shot.height}",
                        confidence = 1f,
                    )
                }
                if (page.size < 500) break
                offset += page.size
            }
            return out
        }

        val suggestions = cleanupDao.allOpen().filter { it.suggestionType == type }
        if (suggestions.isEmpty()) return emptyList()
        val shots = dao.byIds(suggestions.map { it.screenshotId }).associateBy { it.id }
        return suggestions.mapNotNull { s ->
            shots[s.screenshotId]?.let { shot ->
                CandidateItem(
                    shot = shot,
                    reason = s.reason,
                    evidence = s.evidence,
                    confidence = s.confidence,
                )
            }
        }
    }

    /** 只查本类型里出现过的来源，避免给用户一堆选不到的 chip。 */
    private suspend fun attachSources(items: List<CandidateItem>): Pair<List<CandidateItem>, List<SourceOption>> {
        if (items.isEmpty()) return items to emptyList()
        val ids = items.map { it.shot.id }
        val rels = runCatching { db.sourceAppDao().sourcesForIds(ids) }.getOrDefault(emptyList())
        val apps = runCatching { db.sourceAppDao().allNow().associateBy { it.id } }
            .getOrDefault(emptyMap())
        val keyOfShot = rels.mapNotNull { rel ->
            apps[rel.sourceAppId]?.let { rel.screenshotId to (it.key to it.displayName) }
        }.toMap()
        val counts = HashMap<String, Pair<String, Int>>()
        keyOfShot.values.forEach { (k, n) ->
            val cur = counts[k]
            counts[k] = n to ((cur?.second ?: 0) + 1)
        }
        val withSources = items.map { item ->
            val pair = keyOfShot[item.shot.id]
            if (pair == null) item
            else item.copy(sourceKey = pair.first, sourceName = pair.second)
        }
        val options = counts.entries
            .sortedByDescending { it.value.second }
            .map { SourceOption(it.key, it.value.first, it.value.second) }
        return withSources to options
    }

    // ── 类型内筛选 ──

    fun setOlderThan(days: Int?) = applyFilters { it.copy(olderThanDays = days) }

    fun setMinBytes(bytes: Long?) = applyFilters { it.copy(minBytes = bytes) }

    fun setSource(key: String?) = applyFilters { it.copy(sourceKey = key) }

    fun clearFilters() = applyFilters {
        it.copy(olderThanDays = null, minBytes = null, sourceKey = null)
    }

    private fun applyFilters(block: (WorkbenchUiState) -> WorkbenchUiState) {
        val next = block(_uiState.value)
        val now = System.currentTimeMillis()
        val filtered = next.allItems.filter { item ->
            val byAge = next.olderThanDays?.let {
                item.shot.dateModified < now - it * 24L * 3600 * 1000
            } ?: true
            val bySize = next.minBytes?.let { item.shot.fileSize >= it } ?: true
            val bySource = next.sourceKey?.let { it == item.sourceKey } ?: true
            byAge && bySize && bySource
        }
        val stillVisible = filtered.map { it.shot.id }.toSet()
        val selected = next.selected.intersect(stillVisible)
        _uiState.value = next.copy(
            items = filtered,
            selected = selected,
            selectedBytes = filtered.filter { it.shot.id in selected }.sumOf { it.shot.fileSize },
        )
    }

    fun backToOverview() {
        _uiState.value = _uiState.value.copy(
            activeType = null,
            items = emptyList(),
            allItems = emptyList(),
            selected = emptySet(),
            sources = emptyList(),
        )
    }

    fun toggle(id: Long) {
        val item = _uiState.value.items.firstOrNull { it.shot.id == id } ?: return
        if (item.shot.protectionStatus == ProtectionStatus.PROTECTED ||
            item.shot.isFavorite
        ) return
        val next = _uiState.value.selected.let { if (it.contains(id)) it - id else it + id }
        _uiState.value = _uiState.value.copy(
            selected = next,
            selectedBytes = _uiState.value.items.filter { it.shot.id in next }.sumOf { it.shot.fileSize },
        )
    }

    /** 全选当前筛选下所有可选（排除保护/收藏）。 */
    fun selectAll() {
        val ids = _uiState.value.selectable.map { it.shot.id }.toSet()
        _uiState.value = _uiState.value.copy(
            selected = ids,
            selectedBytes = _uiState.value.items.filter { it.shot.id in ids }.sumOf { it.shot.fileSize },
        )
    }

    fun invertSelection() {
        val all = _uiState.value.selectable.map { it.shot.id }.toSet()
        val next = all - _uiState.value.selected
        _uiState.value = _uiState.value.copy(
            selected = next,
            selectedBytes = _uiState.value.items.filter { it.shot.id in next }.sumOf { it.shot.fileSize },
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selected = emptySet(), selectedBytes = 0)
    }

    fun protect(id: Long) {
        viewModelScope.launch {
            Graph.suggestionRepo.protectAndClear(id)
            _uiState.value = _uiState.value.copy(selected = _uiState.value.selected - id)
            _uiState.value.activeType?.let { openType(it) }
        }
    }

    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    /** 重建建议。 */
    fun rebuild() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            withContext(Dispatchers.IO) {
                runCatching { Graph.duplicateRepo.rebuild() }
                runCatching { suggestionRepo.rebuild() }
            }
            loadOverview()
        }
    }
}
