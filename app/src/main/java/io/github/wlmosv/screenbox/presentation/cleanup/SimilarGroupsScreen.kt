package io.github.wlmosv.screenbox.presentation.cleanup

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
 * 相似图片组列表。
 *
 * 每组一行：代表图 + 组内缩略图条 + 相似度范围 + 可释放空间。
 * 点进组内做并排对比和选择。
 *
 * 设计原则：相似不等于重复，这里不提供"全部删除"，必须逐组看。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarGroupsScreen(
    onBack: () -> Unit,
    onOpenGroup: (Long) -> Unit,
    vm: SimilarGroupsViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("画面相似", style = MaterialTheme.typography.titleLarge)
                        if (!state.loading) {
                            Text(
                                "${state.totalGroups} 组 · ${state.totalImages} 张",
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "相似不等于重复。点进任意一组并排对比后再决定保留哪些。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                FilterChip(
                    selected = state.sortBySimilarity,
                    onClick = { vm.setSort(true) },
                    label = { Text("按相似度", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
                FilterChip(
                    selected = !state.sortBySimilarity,
                    onClick = { vm.setSort(false) },
                    label = { Text("按时间", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
                return@Scaffold
            }

            if (state.groups.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.height(Spacing.md))
                        Text("没有发现相似图片", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "画面差异较大的截图不会被分到一组。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@Scaffold
            }

            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.huge)) {
                items(state.groups, key = { it.groupId }) { g ->
                    SimilarGroupRow(
                        card = g,
                        onClick = { onOpenGroup(g.groupId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SimilarGroupRow(card: SimilarGroupCard, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 4.dp),
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick,
    ) {
        Column(Modifier.padding(Spacing.md)) {
            // 缩略图条：最多显示 5 张
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(card.members.take(5), key = { it.id }) { shot ->
                    Box {
                        AsyncImage(
                            model = shot.contentUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 56.dp, height = 92.dp)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        val protected = shot.protectionStatus ==
                            io.github.wlmosv.screenbox.data.db.ProtectionStatus.PROTECTED ||
                            shot.isFavorite
                        if (protected) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Shield,
                                    contentDescription = "受保护",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(9.dp),
                                )
                            }
                        }
                    }
                }
                if (card.members.size > 5) {
                    item {
                        Box(
                            Modifier
                                .size(width = 56.dp, height = 92.dp)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "+${card.members.size - 5}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${card.members.size} 张",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    if (card.minSimilarity >= 0.99f && card.maxSimilarity >= 0.99f)
                        "画面完全一致"
                    else "相似度 ${(card.minSimilarity * 100).toInt()}%–${(card.maxSimilarity * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (card.hasProtected) {
                    Text(
                        "含保护项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            Text(
                buildString {
                    if (card.timeSpanDays > 0) {
                        append("跨度 ${card.timeSpanDays} 天 · ")
                    }
                    append("最多可释放 ${formatBytes(card.reclaimableBytes)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}