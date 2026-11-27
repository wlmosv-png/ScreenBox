package io.github.wlmosv.screenbox.presentation.browse

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing
import io.github.wlmosv.screenbox.presentation.home.formatBytes
import kotlinx.coroutines.launch

/**
 * 时间轴批量清理。
 *
 * 用户最常见的清理动作是"删掉三个月以前的所有截图"。
 * 这个页面按年月分组，每组显示数量和占用，勾选后一键进删除清单。
 *
 * 关键：时间用文件修改时间，不是 OCR 内容日期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    onBack: () -> Unit,
    onRequestDelete: (Set<Long>) -> Unit,
    vm: TimelineViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("按时间清理", style = MaterialTheme.typography.titleLarge)
                        if (!state.loading) {
                            Text(
                                "${state.totalCount} 张 · ${formatBytes(state.totalBytes)}",
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // 粒度切换
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    TimeGranularity.entries.forEach { g ->
                        FilterChip(
                            selected = g == state.granularity,
                            onClick = { vm.setGranularity(g) },
                            label = { Text(g.label, style = MaterialTheme.typography.labelMedium) },
                            shape = RoundedCornerShape(Radius.pill),
                        )
                    }
                }

                // 批量操作
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = vm::selectAllBuckets) { Text("全选") }
                    TextButton(onClick = vm::invertBuckets) { Text("反选") }
                    TextButton(onClick = vm::clearSelection) { Text("清除") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${state.selectedCount} 张",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                    return@Scaffold
                }

                LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                    items(state.buckets, key = { it.key }) { bucket ->
                        TimeBucketRow(
                            bucket = bucket,
                            selected = bucket.key in state.selectedBuckets,
                            onClick = { vm.toggleBucket(bucket.key) },
                        )
                    }
                }
            }

            if (state.selectedCount > 0) {
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
                                    "已选 ${state.selectedCount} 张",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    formatBytes(state.selectedBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = {
                                scope.launch {
                                    val ids = vm.resolveSelectedIds()
                                    if (ids.isNotEmpty()) onRequestDelete(ids.toSet())
                                }
                            }) {
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
}

@Composable
private fun TimeBucketRow(
    bucket: TimeBucket,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh
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
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                bucket.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${bucket.count} 张 · ${formatBytes(bucket.bytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}