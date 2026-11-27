package io.github.wlmosv.screenbox.presentation.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.wlmosv.screenbox.core.ui.components.EmptyState
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onOpenDetail: (Long) -> Unit,
    onDelete: (Set<Long>) -> Unit,
    vm: GalleryViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    // 滚到底部自动加载下一页
    LaunchedEffect(gridState) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last to gridState.layoutInfo.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 6) vm.loadMore()
        }
    }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                SelectionBar(
                    count = state.selectedIds.size,
                    totalInFilter = state.totalCount,
                    onClear = vm::clearSelection,
                    onSelectAll = vm::selectAllFiltered,
                    onInvert = vm::invertSelection,
                    onFavorite = { vm.setFavorite(state.selectedIds, true) },
                    onProtect = { vm.setProtected(state.selectedIds, true) },
                    onDelete = { onDelete(state.selectedIds) },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("截图", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${state.totalCount} 张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 筛选条：横向可滚动，不再挤压换行
            FilterRow(current = state.filter, onSelect = vm::setFilter)

            // 排序条
            SortRow(current = state.sort, onSelect = vm::setSort)

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
                state.items.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.PhotoLibrary,
                    title = when (state.filter) {
                        GalleryFilter.ALL -> "还没有截图"
                        GalleryFilter.FAVORITE -> "还没有收藏的截图"
                        GalleryFilter.PROTECTED -> "还没有保护的截图"
                        GalleryFilter.UNRECOGNIZED -> "全部截图都已识别"
                        GalleryFilter.LARGE -> "没有超过 2 MB 的截图"
                    },
                    description = when (state.filter) {
                        GalleryFilter.ALL -> "回到首页开始扫描，ScreenBox 会读取你授权范围内的截图。"
                        GalleryFilter.FAVORITE -> "在截图详情里点收藏，重要的图就不会被建议清理。"
                        GalleryFilter.PROTECTED -> "保护状态的截图永远不会进入清理建议。"
                        GalleryFilter.UNRECOGNIZED -> "所有截图都已完成文字识别。"
                        GalleryFilter.LARGE -> "大文件筛选条件是单张 ≥ 2 MB。"
                    },
                )
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(
                        start = Spacing.md,
                        end = Spacing.md,
                        bottom = Spacing.huge,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(state.items, key = { it.id }) { shot ->
                        GalleryCell(
                            shot = shot,
                            selected = state.selectedIds.contains(shot.id),
                            selectionMode = state.selectionMode,
                            onClick = {
                                if (state.selectionMode) vm.toggleSelect(shot.id)
                                else onOpenDetail(shot.id)
                            },
                            onLongClick = { vm.toggleSelect(shot.id) },
                        )
                    }
                    if (state.loadingMore) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(Spacing.lg),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 筛选条：LazyRow 横向滚动，解决 v2 的挤压竖排问题。 */
@Composable
private fun FilterRow(current: GalleryFilter, onSelect: (GalleryFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(GalleryFilter.entries.size) { idx ->
            val f = GalleryFilter.entries[idx]
            FilterChip(
                selected = f == current,
                onClick = { onSelect(f) },
                label = { Text(f.label, style = MaterialTheme.typography.labelLarge) },
                shape = RoundedCornerShape(Radius.pill),
            )
        }
    }
}

@Composable
private fun SortRow(current: GallerySort, onSelect: (GallerySort) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(GallerySort.entries.size) { idx ->
            val s = GallerySort.entries[idx]
            FilterChip(
                selected = s == current,
                onClick = { onSelect(s) },
                label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                shape = RoundedCornerShape(Radius.pill),
            )
        }
    }
}

@Composable
private fun GalleryCell(
    shot: ScreenshotEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.sm)
    Box(
        Modifier
            .aspectRatio(0.62f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        AsyncImage(
            model = shot.contentUri,
            contentDescription = shot.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (shot.isFavorite || shot.protectionStatus == ProtectionStatus.PROTECTED) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.BookmarkBorder,
                    contentDescription = "已保护",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(10.dp),
                )
            }
        }

        if (selectionMode) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.35f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    totalInFilter: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onFavorite: () -> Unit,
    onProtect: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClear) { Text("取消") }
            Text(
                if (totalInFilter > count) "已选 $count / $totalInFilter" else "已选 $count 张",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onFavorite) {
                Icon(Icons.Outlined.BookmarkBorder, contentDescription = "收藏")
            }
            IconButton(onClick = onProtect) {
                Icon(Icons.Outlined.Shield, contentDescription = "保护")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "清理",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        // 全选/反选独立一行：全选覆盖整个筛选结果，不只是已加载页
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSelectAll) { Text("全选全部") }
            TextButton(onClick = onInvert) { Text("反选") }
            Spacer(Modifier.weight(1f))
            Text(
                "共 $totalInFilter 张",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        }
    }
}
