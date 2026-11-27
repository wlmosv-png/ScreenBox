package io.github.wlmosv.screenbox.presentation.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 时间分组粒度。 */
enum class TimeGranularity(val label: String) {
    DAY("按天"),
    MONTH("按月"),
    YEAR("按年"),
}

/** 一个时间分组。 */
data class TimeBucket(
    /** 分组键，如 "2025-03" / "2025-03-15" / "2025"。 */
    val key: String,
    /** 展示名，如 "2025 年 3 月" / "3 月 15 日" / "2025 年"。 */
    val displayName: String,
    val count: Int,
    val bytes: Long,
    /** 该组的时间范围（用于查询）。 */
    val fromMs: Long,
    val toMs: Long,
) {
    /** 是否全选。 */
    var allSelected: Boolean = false
}

data class TimelineUiState(
    val granularity: TimeGranularity = TimeGranularity.MONTH,
    val buckets: List<TimeBucket> = emptyList(),
    val loading: Boolean = true,
    val totalCount: Int = 0,
    val totalBytes: Long = 0,
    /** 已勾选的分组 key。 */
    val selectedBuckets: Set<String> = emptySet(),
    val selectedCount: Int = 0,
    val selectedBytes: Long = 0,
    val toast: String? = null,
)

/**
 * 时间轴浏览 ViewModel。
 *
 * ══ 解决什么问题 ══
 *
 * 用户要"删掉三个月以前的截图"，原来只能：
 *   筛选 → 时间 → 90天以前 → 全选 → 清单（5 步）
 * 而且看不到每个月有多少张。
 *
 * 现在按年月分组，每组显示数量和占用，可以直接勾选整月。
 *
 * ══ 关键约束 ══
 *
 * 时间用**文件修改时间**（dateModified），不是 OCR 内容里的日期。
 * 两者语义不同，不能混用。内容日期在详情页展示，用于"过期判断"。
 */
class TimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Graph.db

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun setGranularity(g: TimeGranularity) {
        _uiState.value = _uiState.value.copy(granularity = g, selectedBuckets = emptySet())
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val g = _uiState.value.granularity
            val buckets = withContext(Dispatchers.IO) { buildBuckets(g) }
            _uiState.value = _uiState.value.copy(
                buckets = buckets,
                loading = false,
                totalCount = buckets.sumOf { it.count },
                totalBytes = buckets.sumOf { it.bytes },
            )
        }
    }

    private suspend fun buildBuckets(g: TimeGranularity): List<TimeBucket> {
        // 取全部有效截图的时间和大小，内存里聚合
        // 8136 张的数据量在内存里聚合是几十毫秒，比 SQL 分组好写
        val rows = db.query(
            """
            SELECT dateModified, fileSize FROM screenshots
            WHERE protectionStatus NOT IN ('DELETED','MISSING')
            ORDER BY dateModified DESC
            """.trimIndent(),
            emptyArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(c.getLong(0) to c.getLong(1))
                }
            }
        }

        val cal = Calendar.getInstance()
        val keyFmt = when (g) {
            TimeGranularity.DAY -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            TimeGranularity.MONTH -> SimpleDateFormat("yyyy-MM", Locale.getDefault())
            TimeGranularity.YEAR -> SimpleDateFormat("yyyy", Locale.getDefault())
        }

        val grouped = rows.groupBy { (ts, _) -> keyFmt.format(java.util.Date(ts)) }

        return grouped.map { (key, list) ->
            val (from, to) = rangeOf(key, g)
            TimeBucket(
                key = key,
                displayName = displayOf(key, g),
                count = list.size,
                bytes = list.sumOf { it.second },
                fromMs = from,
                toMs = to,
            )
        }.sortedByDescending { it.fromMs }
    }

    private fun rangeOf(key: String, g: TimeGranularity): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.HOUR_OF_DAY, 0)

        return when (g) {
            TimeGranularity.DAY -> {
                val p = key.split("-")
                cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
                val from = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 1)
                from to cal.timeInMillis
            }
            TimeGranularity.MONTH -> {
                val p = key.split("-")
                cal.set(p[0].toInt(), p[1].toInt() - 1, 1)
                val from = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                from to cal.timeInMillis
            }
            TimeGranularity.YEAR -> {
                cal.set(key.toInt(), 0, 1)
                val from = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                from to cal.timeInMillis
            }
        }
    }

    private fun displayOf(key: String, g: TimeGranularity): String = when (g) {
        TimeGranularity.DAY -> {
            val p = key.split("-")
            "${p[0]} 年 ${p[1].toInt()} 月 ${p[2].toInt()} 日"
        }
        TimeGranularity.MONTH -> {
            val p = key.split("-")
            "${p[0]} 年 ${p[1].toInt()} 月"
        }
        TimeGranularity.YEAR -> "$key 年"
    }

    fun toggleBucket(key: String) {
        val next = _uiState.value.selectedBuckets.let {
            if (it.contains(key)) it - key else it + key
        }
        recomputeSelection(next)
    }

    fun selectAllBuckets() {
        recomputeSelection(_uiState.value.buckets.map { it.key }.toSet())
    }

    fun invertBuckets() {
        val all = _uiState.value.buckets.map { it.key }.toSet()
        recomputeSelection(all - _uiState.value.selectedBuckets)
    }

    fun clearSelection() {
        recomputeSelection(emptySet())
    }

    private fun recomputeSelection(keys: Set<String>) {
        val selected = _uiState.value.buckets.filter { it.key in keys }
        _uiState.value = _uiState.value.copy(
            selectedBuckets = keys,
            selectedCount = selected.sumOf { it.count },
            selectedBytes = selected.sumOf { it.bytes },
        )
    }

    /**
     * 把已选时间分组转成图片 id 列表。
     *
     * 这一步会真正查库拿 id，且排除保护/收藏。
     */
    suspend fun resolveSelectedIds(): List<Long> = withContext(Dispatchers.IO) {
        val buckets = _uiState.value.buckets.filter { it.key in _uiState.value.selectedBuckets }
        if (buckets.isEmpty()) return@withContext emptyList()

        val out = mutableListOf<Long>()
        for (b in buckets) {
            val ids = db.query(
                """
                SELECT id FROM screenshots
                WHERE dateModified >= ? AND dateModified < ?
                  AND protectionStatus NOT IN ('PROTECTED','FAVORITE','DELETED','MISSING')
                  AND isFavorite = 0
                """.trimIndent(),
                arrayOf(b.fromMs, b.toMs),
            ).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
            out += ids
        }
        out
    }

    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }
}