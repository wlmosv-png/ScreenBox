package io.github.wlmosv.screenbox.presentation.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing
import io.github.wlmosv.screenbox.domain.query.TimeWindow

/**
 * 筛选面板。
 *
 * 所有维度并列展示，任意组合。
 * 每个 chip 上直接显示该条件下的图片数 —— 用户不用盲选。
 */
@Composable
fun FilterPanel(
    state: BrowseUiState,
    vm: BrowseViewModel,
    onDone: () -> Unit,
) {
    val f = state.filter
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 1200.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xxl),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("筛选", style = MaterialTheme.typography.titleLarge)
                Text(
                    "当前条件命中 ${state.total} 张",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = vm::clearAll) { Text("全部清除") }
            Button(onClick = onDone) { Text("完成") }
        }

        // ── 已保存的条件：点一下恢复 ──
        if (state.savedFilters.isNotEmpty()) {
            SectionLabel("已保存的条件")
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                state.savedFilters.forEach { saved ->
                    item {
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
        }

        // 保存入口只放一个，和「全部清除」「完成」同排，避免按钮打架。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { saveName = ""; showSaveDialog = true }) {
                Text("保存当前条件", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (f.isEmpty) "未设置条件" else "条件已生效",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 内容分类 ──
        SectionLabel("内容用途")
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
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

        Spacer(Modifier.height(Spacing.md))

        // ── 来源应用 ──
        SectionLabel("来源应用")
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
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

        Spacer(Modifier.height(Spacing.md))

        // ── 图片特征 ──
        // 长截图、二维码、大文件、完全重复…… 这些是图片自身属性，
        // 与「内容用途」「来源应用」并列，可任意组合。
        if (state.tags.isNotEmpty()) {
            SectionLabel("图片特征")
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.tags.size) { i ->
                    val t = state.tags[i]
                    FilterChip(
                        selected = t.key in f.featureTags,
                        onClick = { vm.toggleFeatureTag(t.key) },
                        label = { Text("${t.displayName} ${t.count}", style = MaterialTheme.typography.labelMedium) },
                        shape = RoundedCornerShape(Radius.pill),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }

        // ── 时间 ──
        SectionLabel("时间")
        val now = System.currentTimeMillis()
        val timeOptions = listOf(
            TimeWindow.all(),
            TimeWindow.today(now),
            TimeWindow.lastDays(7, now),
            TimeWindow.lastDays(30, now),
            TimeWindow.lastDays(90, now),
            TimeWindow.olderThan(90, now),
            TimeWindow.olderThan(365, now),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
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

        Spacer(Modifier.height(Spacing.md))

        // ── 文件大小 ──
        SectionLabel("文件大小")
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            val sizes = listOf(
                "不限" to null,
                "≥ 1 MB" to 1L * 1024 * 1024,
                "≥ 2 MB" to 2L * 1024 * 1024,
                "≥ 5 MB" to 5L * 1024 * 1024,
                "≥ 10 MB" to 10L * 1024 * 1024,
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

        Spacer(Modifier.height(Spacing.md))

        // ── 状态 ──
        SectionLabel("状态")
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
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
                val sel = f.ocrStatuses.isNotEmpty() &&
                    f.ocrStatuses.all {
                        it == io.github.wlmosv.screenbox.data.db.OcrStatus.SUCCESS ||
                            it == io.github.wlmosv.screenbox.data.db.OcrStatus.PARTIAL
                    }
                FilterChip(
                    selected = sel,
                    onClick = vm::toggleOcrRecognized,
                    label = { Text("已识别", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
            item {
                val target = setOf(
                    io.github.wlmosv.screenbox.data.db.OcrStatus.NOT_STARTED,
                    io.github.wlmosv.screenbox.data.db.OcrStatus.QUEUED,
                    io.github.wlmosv.screenbox.data.db.OcrStatus.FAILED,
                )
                FilterChip(
                    selected = f.ocrStatuses == target,
                    onClick = vm::toggleOcrUnrecognized,
                    label = { Text("未识别", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))

        // ── 重复关系 ──
        SectionLabel("重复与相似")
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                FilterChip(
                    selected = f.exactDuplicateOnly,
                    onClick = vm::toggleExactDuplicate,
                    label = { Text("完全重复", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
            item {
                FilterChip(
                    selected = f.similarOnly,
                    onClick = vm::toggleSimilar,
                    label = { Text("画面相似", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
            item {
                FilterChip(
                    selected = f.redundantOnly,
                    onClick = vm::toggleRedundant,
                    label = { Text("任一冗余", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(Radius.pill),
                )
            }
        }

        Spacer(Modifier.height(Spacing.xl))
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存筛选条件") },
            text = {
                Column {
                    Text(
                        "之后可以在本页或搜索页一键恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        singleLine = true,
                        placeholder = { Text("例如：微信里的大图") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.saveCurrentFilter(saveName)
                        showSaveDialog = false
                    },
                    enabled = saveName.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    )
}