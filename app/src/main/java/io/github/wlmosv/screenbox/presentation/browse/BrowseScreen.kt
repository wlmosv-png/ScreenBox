package io.github.wlmosv.screenbox.presentation.browse

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.domain.query.ShotFilter
import io.github.wlmosv.screenbox.domain.query.ShotSort
import io.github.wlmosv.screenbox.domain.query.TimeWindow
import io.github.wlmosv.screenbox.presentation.home.formatBytes

/**
 * 统一筛选浏览页。
 *
 * 一个页面替代原来的：分类浏览 / 按应用浏览 / 按时间浏览 / 部分清理入口。
 *
 * 用户可以做：
 * - 任意组合条件筛选（内容/来源/时间/大小/收藏/保护/OCR/重复）
 * - 看真实总数（不是当前页）
 * - 全选筛选结果（走全量 id 查询，不是可见项）
 * - 反选 / 清除
 * - 批量保护 / 收藏 / 加入删除清单
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    initialFilter: ShotFilter,
    title: String,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onRequestDelete: (Set<Long>) -> Unit,
    vm: BrowseViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showFilterSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { vm.applyPreset(initialFilter) }

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
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (state.loading) "统计中…" else "${state.total} 张",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(Icons.Outlined.FilterList, contentDescription = "筛选")
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
        Column(Modifier.padding(padding).fillMaxSize()) {

            // 当前筛选条件（可点开面板修改）
            if (state.description.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(state.description.size) { i ->
                        Surface(
                            shape = RoundedCornerShape(Radius.pill),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.clickable { showFilterSheet = true },
                        ) {
                            Text(
                                state.description[i],
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                            )
                        }
                    }
                    item {
                        TextButton(onClick = vm::clearAll) {
                            Text("清除", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // 选择 + 排序条
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = vm::selectAllFiltered) { Text("全选") }
                TextButton(onClick = vm::invertSelection) { Text("反选") }
                TextButton(onClick = vm::clearSelection) { Text("清除") }
                Spacer(Modifier.weight(1f))
                Text(
                    "${state.selected.size} / ${state.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(ShotSort.entries.size) { i ->
                    val s = ShotSort.entries[i]
                    FilterChip(
                        selected = s == state.sort,
                        onClick = { vm.setSort(s) },
                        label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(Radius.pill),
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xs))

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
                state.items.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(Spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("没有符合条件的截图", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "试试放宽筛选条件，或者点右上角调整。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.lg))
                        TextButton(onClick = vm::clearAll) { Text("清除全部条件") }
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(
                        start = Spacing.md,
                        end = Spacing.md,
                        bottom = 120.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(state.items, key = { it.id }) { shot ->
                        val isProtected = shot.protectionStatus == ProtectionStatus.PROTECTED ||
                            shot.isFavorite
                        BrowseCell(
                            shot = shot,
                            selected = state.selected.contains(shot.id),
                            protected = isProtected,
                            anySelected = state.selected.isNotEmpty(),
                            onClick = {
                                if (state.selected.isNotEmpty()) vm.toggle(shot.id)
                                else onOpenDetail(shot.id)
                            },
                        )
                    }
                    if (state.total > state.items.size) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(Spacing.lg),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "已显示 ${state.items.size} / ${state.total} 张，用筛选缩小范围",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 底部批量操作条
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
                        Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.padding(start = Spacing.sm).weight(1f)) {
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
                        IconButton(onClick = vm::favoriteSelected) {
                            Icon(Icons.Outlined.FavoriteBorder, contentDescription = "收藏", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = vm::protectSelected) {
                            Icon(Icons.Outlined.Shield, contentDescription = "保护", modifier = Modifier.size(20.dp))
                        }
                        Button(onClick = { onRequestDelete(state.selected) }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Spacing.xs))
                            Text("清理")
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState,
        ) {
            FilterPanel(
                state = state,
                vm = vm,
                onDone = { showFilterSheet = false },
            )
        }
    }
}

@Composable
private fun BrowseCell(
    shot: io.github.wlmosv.screenbox.data.db.ScreenshotEntity,
    selected: Boolean,
    protected: Boolean,
    anySelected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.sm)
    Box(
        Modifier
            .aspectRatio(0.62f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.error, shape)
                else Modifier
            )
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = shot.contentUri,
            contentDescription = shot.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (protected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = "受保护",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(10.dp),
                )
            }
        }

        if (anySelected) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            protected -> Color.Black.copy(alpha = 0.45f)
                            selected -> MaterialTheme.colorScheme.error
                            else -> Color.Black.copy(alpha = 0.35f)
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}