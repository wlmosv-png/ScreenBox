@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.wlmosv.screenbox.presentation.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.wlmosv.screenbox.core.ui.components.EmptyState
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing
import io.github.wlmosv.screenbox.data.db.OcrStatus
import io.github.wlmosv.screenbox.domain.query.TimeWindow

@Composable
fun SearchScreen(
    onOpenDetail: (Long) -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.toast) {
        val msg = state.toast ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        vm.consumeToast()
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Text(
                "搜索截图内容",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "文字、日期、金额、订单号",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(Radius.md),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )

            // 筛选入口只保留一个，避免出现多个功能重复的按钮。
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { showFilterSheet = true },
                    label = {
                        Text(
                            if (state.hasFilter) "筛选 · 已启用" else "筛选",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                    },
                    shape = RoundedCornerShape(Radius.pill),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (state.hasFilter) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    ),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    filterSummary(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.hasFilter) {
                    TextButton(onClick = vm::clearFilters) {
                        Text("清除", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        when {
            state.searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }

            !state.hasSearched -> SearchIdleState(
                history = state.history,
                pendingOcr = state.pendingOcr,
                onUseHistory = vm::useHistory,
                onDeleteHistory = vm::deleteHistory,
                onClearHistory = vm::clearHistory,
            )

            state.results.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Search,
                title = "没有找到「${state.query}」",
                description = buildString {
                    append("试试更短的关键词，或者用别的说法。")
                    if (state.pendingOcr > 0) {
                        append("\n\n还有 ${state.pendingOcr} 张截图尚未完成文字识别，")
                        append("它们暂时搜不到。")
                    }
                    if (state.hasFilter) {
                        append("\n\n当前有筛选条件，可能过滤掉了部分结果。")
                    }
                },
                actionLabel = if (state.hasFilter) "清除筛选" else null,
                onAction = if (state.hasFilter) vm::clearFilters else null,
            )

            else -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        buildString {
                            append("找到 ${state.totalMatches} 张")
                            if (state.shownCount < state.totalMatches) {
                                append("，当前显示前 ${state.shownCount} 张")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showSaveDialog = true }) {
                        Icon(
                            Icons.Outlined.Save,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text("保存条件", style = MaterialTheme.typography.labelSmall)
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(SearchSort.entries.size) { idx ->
                        val s = SearchSort.entries[idx]
                        FilterChip(
                            selected = s == state.sort,
                            onClick = { vm.setSort(s) },
                            label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(Radius.pill),
                        )
                    }
                }

                if (state.pendingOcr > 0) {
                    Text(
                        "还有 ${state.pendingOcr} 张未识别，结果可能不全",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 2.dp),
                    )
                }

                LazyColumn(
                    contentPadding = PaddingValues(bottom = Spacing.huge),
                ) {
                    items(state.results, key = { it.shot.id }) { item ->
                        SearchResultRow(
                            item = item,
                            onClick = { onOpenDetail(item.shot.id) },
                        )
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            FilterSheet(
                state = state,
                vm = vm,
                onSave = {
                    showFilterSheet = false
                    showSaveDialog = true
                },
            )
        }
    }

    if (showSaveDialog) {
        SaveFilterDialog(
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                vm.saveCurrentFilter(name)
                showSaveDialog = false
            },
        )
    }
}

/** 当前条件的一句话摘要，让用户知道结果被什么收窄了。 */
private fun filterSummary(state: SearchUiState): String {
    val f = state.filter.copy(textQuery = null, parsedQuery = null)
    if (f.isEmpty) return "可按分类、来源、时间、大小、状态组合筛选"
    val parts = f.describe(
        categoryNames = state.categories.associate { it.key to it.displayName },
        appNames = state.sources.associate { it.key to it.displayName },
    )
    return parts.joinToString(" · ")
}

@Composable
private fun FilterSheet(
    state: SearchUiState,
    vm: SearchViewModel,
    onSave: () -> Unit,
) {
    val f = state.filter
    val scroll = rememberScrollState()
    val now = System.currentTimeMillis()

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 1200.dp)
            .verticalScroll(scroll)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xxl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("筛选条件", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSave) { Text("保存") }
            TextButton(onClick = vm::clearFilters) { Text("全部清除") }
        }

        // 已保存的条件：点一下恢复，右侧可删除
        if (state.savedFilters.isNotEmpty()) {
            FilterLabel("已保存")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(state.savedFilters, key = { it.id }) { saved ->
                    AssistChip(
                        onClick = { vm.applySaved(saved) },
                        label = { Text(saved.name, style = MaterialTheme.typography.labelMedium) },
                        shape = RoundedCornerShape(Radius.pill),
                        trailingIcon = {
                            IconButton(
                                onClick = { vm.deleteSaved(saved.id) },
                                modifier = Modifier.size(22.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "删除${saved.name}",
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        },
                    )
                }
            }
        }

        FilterLabel("内容分类")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(state.categories.size) { i ->
                val c = state.categories[i]
                FilterChip(
                    selected = c.key in f.contentCategories,
                    onClick = { vm.toggleCategory(c.key) },
                    label = { Text("${c.displayName} ${c.count}", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
        }

        FilterLabel("来源应用")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(state.sources.size) { i ->
                val s = state.sources[i]
                FilterChip(
                    selected = s.key in f.sourceApps,
                    onClick = { vm.toggleSource(s.key) },
                    label = { Text("${s.displayName} ${s.count}", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
        }

        FilterLabel("时间")
        val timeOptions = listOf(
            TimeWindow.all(),
            TimeWindow.today(now),
            TimeWindow.lastDays(7, now),
            TimeWindow.lastDays(30, now),
            TimeWindow.lastDays(90, now),
            TimeWindow.olderThan(90, now),
            TimeWindow.olderThan(365, now),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(timeOptions.size) { i ->
                val t = timeOptions[i]
                val selected = f.timeWindow.label == t.label
                FilterChip(
                    selected = selected,
                    onClick = { vm.setTime(if (selected) TimeWindow.all() else t) },
                    label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
        }

        FilterLabel("文件大小")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val sizes = listOf(
                "不限" to null,
                "≥ 1 MB" to 1L * 1024 * 1024,
                "≥ 2 MB" to 2L * 1024 * 1024,
                "≥ 5 MB" to 5L * 1024 * 1024,
            )
            items(sizes.size) { i ->
                val (label, bytes) = sizes[i]
                val selected = f.minBytes == bytes
                FilterChip(
                    selected = selected,
                    onClick = { vm.setMinBytes(if (selected) null else bytes) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
        }

        FilterLabel("状态")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item {
                FilterChip(
                    selected = f.favorite == true,
                    onClick = { vm.setFavorite(if (f.favorite == true) null else true) },
                    label = { Text("仅收藏", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
            item {
                FilterChip(
                    selected = f.protected == true,
                    onClick = { vm.setProtected(if (f.protected == true) null else true) },
                    label = { Text("仅保护", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
            item {
                val target = setOf(OcrStatus.SUCCESS, OcrStatus.PARTIAL)
                FilterChip(
                    selected = f.ocrStatuses == target,
                    onClick = vm::toggleOcrRecognized,
                    label = { Text("已识别", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
            item {
                val target = setOf(OcrStatus.NOT_STARTED, OcrStatus.QUEUED, OcrStatus.FAILED)
                FilterChip(
                    selected = f.ocrStatuses == target,
                    onClick = vm::toggleOcrUnrecognized,
                    label = { Text("未识别", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        Text(
            "条件立即生效，可随时清除。保存后可在本页再次调用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
    )
}

@Composable
private fun SaveFilterDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存当前条件") },
        text = {
            Column {
                Text(
                    "关键词与筛选项会一起保存，下次点一下就能恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("例如：微信里的订单") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 未搜索时的空态：显示历史 + 提示。 */
@Composable
private fun SearchIdleState(
    history: List<io.github.wlmosv.screenbox.data.db.SearchHistoryEntity>,
    pendingOcr: Int,
    onUseHistory: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.lg)) {
        if (history.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    "最近搜索",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClearHistory) {
                    Text("清除", style = MaterialTheme.typography.labelSmall)
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(history.size) { idx ->
                    val h = history[idx]
                    AssistChip(
                        onClick = { onUseHistory(h.keyword) },
                        label = {
                            Text(
                                "${h.keyword} · ${h.hitCount}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        shape = RoundedCornerShape(Radius.pill),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            EmptyState(
                icon = Icons.Outlined.Search,
                title = "找到任何一张截图",
                description = buildString {
                    append("搜订单号、聊天里的一句话、文章里的关键词 —— 只要截图里有，就能找到。")
                    append("\n\n所有识别都在本地完成，不会上传。")
                    if (pendingOcr > 0) {
                        append("\n\n当前有 $pendingOcr 张截图尚未识别，完成前搜不到它们。")
                    }
                },
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    item: SearchResultItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        AsyncImage(
            model = item.shot.contentUri,
            contentDescription = item.shot.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 54.dp, height = 84.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.shot.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))

            Text(
                text = highlight(
                    item.snippet.ifBlank { "（无上下文）" },
                    item.hitRanges,
                    MaterialTheme.colorScheme.primary,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            // 命中依据：让用户知道为什么这张图在结果里
            if (item.hitReason.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Surface(
                    shape = RoundedCornerShape(Radius.sm),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        item.hitReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        maxLines = 1,
                    )
                }
            }

            if (item.entities.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.entities.forEach { ent ->
                        Surface(
                            shape = RoundedCornerShape(Radius.sm),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                "${ent.kindLabel} ${ent.value}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            val meta = buildList {
                item.categoryName?.let { add(it) }
                item.sourceName?.let { add(it) }
                add(
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(java.util.Date(item.shot.dateModified))
                )
            }.joinToString(" · ")
            Spacer(Modifier.height(2.dp))
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 把命中区间套上高亮样式。
 *
 * 颜色必须来自主题：旧版写死 #2F6F6B，在深色主题下是深青压深底，
 * 对比度不足，等于没高亮。现在由调用方传入 primary。
 */
private fun highlight(text: String, ranges: List<IntRange>, accent: Color) = buildAnnotatedString {
    if (ranges.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var cursor = 0
    for (r in ranges.sortedBy { it.first }) {
        if (r.first < cursor) continue
        if (r.first > cursor) append(text.substring(cursor, r.first.coerceAtMost(text.length)))
        withStyle(
            SpanStyle(
                color = accent,
                fontWeight = FontWeight.SemiBold,
                background = accent.copy(alpha = 0.18f),
            )
        ) {
            append(text.substring(r.first.coerceAtMost(text.length), (r.last + 1).coerceAtMost(text.length)))
        }
        cursor = (r.last + 1).coerceAtMost(text.length)
    }
    if (cursor < text.length) append(text.substring(cursor))
}
