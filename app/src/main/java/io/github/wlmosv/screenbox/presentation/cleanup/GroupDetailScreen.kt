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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing
import io.github.wlmosv.screenbox.presentation.home.formatBytes

/**
 * 组内对比页。
 *
 * 核心交互：
 * - 并排网格，一眼看出哪几张像
 * - 标出「建议保留」
 * - 「只保留这张」一键切换选择
 * - 保护项锁定不可选
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    groupId: Long,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onRequestDelete: (Set<Long>) -> Unit,
    vm: GroupDetailViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(groupId) { vm.load(groupId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("对比这组图片", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${state.members.size} 张 · 共 ${formatBytes(state.totalBytes)}",
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
                    IconButton(onClick = vm::dismissGroup) {
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

        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "逐张对比后决定保留哪些。系统已标出建议保留项，但最终由你决定。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = Spacing.md,
                    end = Spacing.md,
                    bottom = 120.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.members, key = { it.shot.id }) { m ->
                    GroupMemberCard(
                        member = m,
                        selected = state.selected.contains(m.shot.id),
                        onToggle = { vm.toggle(m.shot.id) },
                        onOpen = { onOpenDetail(m.shot.id) },
                        onKeepOnly = { vm.keepOnly(m.shot.id) },
                        onProtect = { vm.protect(m.shot.id) },
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
                                "保留 ${state.members.size - state.selected.size} 张",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
}

@Composable
private fun GroupMemberCard(
    member: GroupMember,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onKeepOnly: () -> Unit,
    onProtect: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.md)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.error, shape)
                    else Modifier
                )
                .clickable(onClick = onOpen),
        ) {
            AsyncImage(
                model = member.shot.contentUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // 建议保留标记
            if (member.isRecommendedKeep) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.xs)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.sm))
                        .padding(horizontal = Spacing.xs, vertical = 2.dp),
                ) {
                    Text(
                        "建议保留",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            // 保护标记
            if (member.isProtected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.xs)
                        .size(24.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = "已保护",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // 选中标记
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(Spacing.xs)
                    .size(24.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        when {
                            member.isProtected -> MaterialTheme.colorScheme.surfaceContainerHigh
                            selected -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                    )
                    .clickable(enabled = !member.isProtected, onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "将删除",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.xs))

        Text(
            member.shot.displayName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${formatBytes(member.shot.fileSize)} · ${member.shot.width}×${member.shot.height}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row {
            TextButton(
                onClick = onKeepOnly,
                contentPadding = PaddingValues(horizontal = Spacing.xs),
            ) {
                Text("只留这张", style = MaterialTheme.typography.labelSmall)
            }
            if (!member.isProtected) {
                TextButton(
                    onClick = onProtect,
                    contentPadding = PaddingValues(horizontal = Spacing.xs),
                ) {
                    Text("保护", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
