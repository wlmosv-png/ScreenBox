package io.github.wlmosv.screenbox.presentation.cleanup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.github.wlmosv.screenbox.core.ui.components.ZoomableImage
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing
import io.github.wlmosv.screenbox.presentation.home.formatBytes

/**
 * 相似组并排对比。
 *
 * 核心是"对比"而不是"网格浏览"：
 * - 顶部大图：当前基准图，可点开放大
 * - 下方列表：其他成员，标注与基准的相似度和具体差异
 * - 每组可切换基准、只留这张、勾选删除
 *
 * 默认不选中任何图 —— 相似不等于该删。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    groupId: Long,
    onBack: () -> Unit,
    onRequestDelete: (Set<Long>) -> Unit,
    vm: CompareViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var fullscreenUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(groupId) { vm.load(groupId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("并排对比", style = MaterialTheme.typography.titleLarge)
                        if (!state.loading) {
                            Text(
                                "${state.members.size} 张 · " +
                                    if (state.minSimilarity >= 0.99f) "画面完全一致"
                                    else "相似度 ${(state.minSimilarity * 100).toInt()}%–${(state.maxSimilarity * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        vm.dismissGroup()
                        onBack()
                    }) {
                        Icon(
                            Icons.Outlined.VisibilityOff,
                            contentDescription = "解散这组",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
            return@Scaffold
        }

        val base = state.members.firstOrNull { it.shot.id == state.baseId }
            ?: state.members.firstOrNull()

        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "点任意一张设为对比基准。相似不等于重复，请看清差异再决定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = vm::selectAllExceptBase) { Text("全选其余") }
                    TextButton(onClick = vm::clearSelection) { Text("清除") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${state.selected.size} / ${state.members.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                    // 基准大图
                    if (base != null) {
                        item {
                            Column(Modifier.padding(horizontal = Spacing.lg)) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(300.dp)
                                        .clip(RoundedCornerShape(Radius.md))
                                        .background(Color.Black)
                                        .clickable { fullscreenUrl = base.shot.contentUri },
                                ) {
                                    AsyncImage(
                                        model = base.shot.contentUri,
                                        contentDescription = "基准图",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(Radius.sm),
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(Spacing.xs),
                                    ) {
                                        Text(
                                            "基准",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(Spacing.xs))
                                Text(
                                    "${formatBytes(base.shot.fileSize)} · " +
                                        "${base.shot.width}×${base.shot.height} · " +
                                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(base.shot.dateModified)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    items(state.members, key = { it.shot.id }) { m ->
                        CompareRow(
                            member = m,
                            isBase = m.shot.id == state.baseId,
                            selected = state.selected.contains(m.shot.id),
                            onSetBase = { vm.setBase(m.shot.id) },
                            onToggle = { vm.toggle(m.shot.id) },
                            onKeepOnly = { vm.keepOnly(m.shot.id) },
                            onProtect = { vm.protect(m.shot.id) },
                            onZoom = { fullscreenUrl = m.shot.contentUri },
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
                                    "将删除 ${state.selected.size} 张",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "保留 ${state.members.size - state.selected.size} 张 · ${formatBytes(state.selectedBytes)}",
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

    // 全屏查看
    if (fullscreenUrl != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullscreenUrl = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ZoomableImage(
                    model = fullscreenUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { fullscreenUrl = null },
                )
            }
        }
    }
}

@Composable
private fun CompareRow(
    member: CompareMember,
    isBase: Boolean,
    selected: Boolean,
    onSetBase: () -> Unit,
    onToggle: () -> Unit,
    onKeepOnly: () -> Unit,
    onProtect: () -> Unit,
    onZoom: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clickable(enabled = !member.isProtected, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        when {
                            member.isProtected -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
                            selected -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    selected -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "将删除",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(14.dp),
                    )
                    member.isProtected -> Icon(
                        Icons.Outlined.Shield,
                        contentDescription = "受保护",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(Spacing.xs))

        Box(
            Modifier
                .size(width = 52.dp, height = 78.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (isBase) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.sm))
                    else Modifier
                )
                .clickable(onClick = onZoom),
        ) {
            AsyncImage(
                model = member.shot.contentUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(Spacing.md))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (member.isRecommendedKeep) {
                    Text(
                        "建议保留",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                Text(
                    if (isBase) "当前基准"
                    else if (member.similarityToBase >= 0.99f) "画面一致"
                    else "相似度 ${(member.similarityToBase * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isBase) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                member.diffNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                if (!isBase) {
                    TextButton(onClick = onSetBase, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("设为基准", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = onKeepOnly, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("只留这张", style = MaterialTheme.typography.labelSmall)
                }
                if (!member.isProtected) {
                    TextButton(onClick = onProtect, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("保护", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}