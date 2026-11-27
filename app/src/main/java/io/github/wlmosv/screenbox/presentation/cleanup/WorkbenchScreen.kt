package io.github.wlmosv.screenbox.presentation.cleanup

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing
import io.github.wlmosv.screenbox.data.db.SuggestionType
import io.github.wlmosv.screenbox.presentation.home.formatBytes

/**
 * 清理工作台。
 *
 * 两级结构：
 *   总览：每类候选有多少、能释放多少、风险多高
 *   详情：进入某一类，批量选择处理
 *
 * 视觉相似不在这里（走组级页面），避免 247 条噪音。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenSimilarGroups: () -> Unit,
    onRequestDelete: (Set<Long>) -> Unit,
    vm: WorkbenchViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        val m = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.consumeToast()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (state.activeType == null) "清理工作台" else state.activeType!!.label,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (state.activeType != null) {
                            Text(
                                "${state.items.size} 张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.activeType != null) vm.backToOverview() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.activeType == null) {
                        TextButton(onClick = vm::rebuild) { Text("重新分析") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }

            state.activeType == null -> OverviewContent(
                state = state,
                modifier = Modifier.padding(padding),
                onOpenType = vm::openType,
                onOpenSimilar = onOpenSimilarGroups,
            )

            else -> TypeDetailContent(
                state = state,
                modifier = Modifier.padding(padding),
                onToggle = vm::toggle,
                onSelectAll = vm::selectAll,
                onInvert = vm::invertSelection,
                onClear = vm::clearSelection,
                onOpenDetail = onOpenDetail,
                onProtect = vm::protect,
                onRequestDelete = onRequestDelete,
                onSetOlder = vm::setOlderThan,
                onSetSize = vm::setMinBytes,
                onSetSource = vm::setSource,
                onClearFilters = vm::clearFilters,
            )
        }
    }
}

@Composable
private fun OverviewContent(
    state: WorkbenchUiState,
    modifier: Modifier,
    onOpenType: (SuggestionType) -> Unit,
    onOpenSimilar: () -> Unit,
) {
    if (state.cards.isEmpty()) {
        Box(modifier.fillMaxSize().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(Spacing.md))
                Text("暂时没有清理候选", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "ScreenBox 不会凭空建议你删东西。\n只有找到确实重复、明确过期、或近乎空白的截图时，这里才会出现内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.huge),
    ) {
        item {
            Text(
                "按类型处理。每一类都有具体依据，你可以只看某一类。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
        }

        items(state.cards, key = { it.type.name }) { card ->
            CandidateTypeRow(
                card = card,
                onClick = {
                    if (card.type == SuggestionType.VISUAL_DUPLICATE) onOpenSimilar()
                    else onOpenType(card.type)
                },
            )
        }
    }
}

@Composable
private fun CandidateTypeRow(card: CandidateCard, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 3.dp),
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        when (card.risk) {
                            CandidateCard.Risk.LOW -> MaterialTheme.colorScheme.primaryContainer
                            CandidateCard.Risk.MEDIUM -> MaterialTheme.colorScheme.secondaryContainer
                            CandidateCard.Risk.HIGH -> MaterialTheme.colorScheme.errorContainer
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconOf(card.type),
                    contentDescription = null,
                    tint = when (card.risk) {
                        CandidateCard.Risk.LOW -> MaterialTheme.colorScheme.onPrimaryContainer
                        CandidateCard.Risk.MEDIUM -> MaterialTheme.colorScheme.onSecondaryContainer
                        CandidateCard.Risk.HIGH -> MaterialTheme.colorScheme.onErrorContainer
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.type.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        if (card.groupCount > 0) "${card.groupCount} 组 · ${card.count} 张"
                        else "${card.count} 张",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    card.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.bytes > 0) {
                    Text(
                        "可释放 ${formatBytes(card.bytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeDetailContent(
    state: WorkbenchUiState,
    modifier: Modifier,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onClear: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onProtect: (Long) -> Unit,
    onRequestDelete: (Set<Long>) -> Unit,
    onSetOlder: (Int?) -> Unit,
    onSetSize: (Long?) -> Unit,
    onSetSource: (String?) -> Unit,
    onClearFilters: () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSelectAll) { Text("全选") }
                TextButton(onClick = onInvert) { Text("反选") }
                TextButton(onClick = onClear) { Text("清除") }
                Spacer(Modifier.weight(1f))
                Text(
                    // 短格式：小屏 + 大字体时这一行不能折行，否则挤压上面的按钮。
                    "已选 ${state.selected.size}/${state.selectable.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = Spacing.md),
                )
            }

            // 类型内再收窄：时间 / 体积 / 来源。三者可叠加。
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item {
                    FilterChip(
                        selected = state.olderThanDays == null,
                        onClick = { onSetOlder(null) },
                        label = { Text("全部时间", style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(Radius.pill),
                    )
                }
                listOf(90 to "90 天前", 180 to "180 天前", 365 to "一年前").forEach { (days, label) ->
                    item {
                        FilterChip(
                            selected = state.olderThanDays == days,
                            onClick = { onSetOlder(if (state.olderThanDays == days) null else days) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(Radius.pill),
                        )
                    }
                }
                item {
                    FilterChip(
                        selected = state.minBytes == 2L * 1024 * 1024,
                        onClick = {
                            onSetSize(
                                if (state.minBytes == 2L * 1024 * 1024) null else 2L * 1024 * 1024
                            )
                        },
                        label = { Text("≥ 2 MB", style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(Radius.pill),
                    )
                }
                item {
                    FilterChip(
                        selected = state.minBytes == 5L * 1024 * 1024,
                        onClick = {
                            onSetSize(
                                if (state.minBytes == 5L * 1024 * 1024) null else 5L * 1024 * 1024
                            )
                        },
                        label = { Text("≥ 5 MB", style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(Radius.pill),
                    )
                }
                if (state.sources.isNotEmpty()) {
                    item {
                        FilterChip(
                            selected = state.sourceKey == null,
                            onClick = { onSetSource(null) },
                            label = { Text("全部来源", style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(Radius.pill),
                        )
                    }
                    state.sources.forEach { s ->
                        item {
                        FilterChip(
                            selected = state.sourceKey == s.key,
                            onClick = { onSetSource(if (state.sourceKey == s.key) null else s.key) },
                            label = {
                                Text(
                                    "${s.name} ${s.count}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            shape = RoundedCornerShape(Radius.pill),
                        )
                        }
                    }
                }
            }

            if (state.items.isEmpty() && state.allItems.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "当前筛选没有符合条件的图片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClearFilters) { Text("清除筛选") }
                }
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                items(state.items, key = { it.shot.id }) { item ->
                    CandidateRow(
                        item = item,
                        selected = state.selected.contains(item.shot.id),
                        onToggle = { onToggle(item.shot.id) },
                        onOpen = { onOpenDetail(item.shot.id) },
                        onProtect = { onProtect(item.shot.id) },
                    )
                }
            }
        }

        if (state.selected.isNotEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(bottom = Spacing.lg),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 4.dp,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                ) {
                    Row(
                        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "已选 ${state.selected.size} 张",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                formatBytes(state.selectedBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { onRequestDelete(state.selected) }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Spacing.xs))
                            Text("加入清单")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    item: CandidateItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onProtect: () -> Unit,
) {
    val isProtected = item.shot.protectionStatus == io.github.wlmosv.screenbox.data.db.ProtectionStatus.PROTECTED ||
        item.shot.isFavorite

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clickable(enabled = !isProtected, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        when {
                            isProtected -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
                            selected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                } else if (isProtected) {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = "受保护",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(Spacing.xs))

        AsyncImage(
            model = item.shot.contentUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 46.dp, height = 68.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onOpen),
        )
        Spacer(Modifier.width(Spacing.md))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isRecommendedKeep) {
                    Text(
                        "建议保留",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = Spacing.xs),
                    )
                }
                Text(
                    item.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                item.evidence,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatBytes(item.shot.fileSize)} · ${item.shot.width}×${item.shot.height}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        if (!isProtected) {
            TextButton(onClick = onProtect, contentPadding = PaddingValues(horizontal = Spacing.xs)) {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = "保护",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun iconOf(t: SuggestionType): ImageVector = when (t) {
    SuggestionType.EXACT_DUPLICATE -> Icons.Outlined.ContentCopy
    SuggestionType.VISUAL_DUPLICATE -> Icons.Outlined.Image
    SuggestionType.POSSIBLY_EXPIRED -> Icons.Outlined.Schedule
    SuggestionType.NEAR_BLANK -> Icons.Outlined.ErrorOutline
    SuggestionType.LARGE_FILE -> Icons.Outlined.Storage
    SuggestionType.LOW_INFORMATION -> Icons.Outlined.ErrorOutline
    SuggestionType.LONG_UNTOUCHED -> Icons.Outlined.Schedule
    SuggestionType.USER_PICKED -> Icons.Outlined.DeleteOutline
    SuggestionType.ONE_TIME_CODE -> Icons.Outlined.Info
    SuggestionType.STALE_NOTIFICATION -> Icons.Outlined.Schedule
    SuggestionType.OLD_GAME -> Icons.Outlined.Image
    SuggestionType.OLD_SOCIAL -> Icons.Outlined.Schedule
}