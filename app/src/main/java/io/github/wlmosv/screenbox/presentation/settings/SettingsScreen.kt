package io.github.wlmosv.screenbox.presentation.settings

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wlmosv.screenbox.R
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing

/**
 * 设置 / 维护页。
 *
 * ══ R5 ══
 * 旧版是装饰性页面：三个开关只改 remember，重启失效；
 * 版本号硬编码，与实际 APK 对不上；
 * 用户没有任何入口重建分类、标签、重复组。
 *
 * ══ R10 ══
 * · 顶部放品牌标记，让设置页和应用图标是同一套视觉
 * · 「已归类」改用真实分类口径，未归类的数量如实显示
 * · 行级坐标回填改成后台任务，有进度、能暂停、能继续
 * · 关于页从底部唯一入口进入
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshStats() }
    LaunchedEffect(state.lineTaskStatus) { vm.refreshStats() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置与维护", style = MaterialTheme.typography.titleLarge) },
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
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.huge),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_brand_mark),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column {
                        Text("ScreenBox", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "版本 ${vm.versionName}（${vm.versionCode}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { GroupTitle("图库状态") }
            item {
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                    StatLine("有效截图", "${state.totalActive} 张")
                    StatLine("已归类", "${state.categoryCovered} 张")
                    StatLine("尚未归类", "${state.categoryMissing} 张")
                    StatLine("特征标签种类", "${state.tagCount}")
                }
            }

            item { GroupTitle("行级文字坐标") }
            item {
                LineBackfillCard(
                    state = state,
                    onStart = vm::startLineBackfill,
                    onPause = vm::pauseLineBackfill,
                    onRetryFailed = vm::retryFailedLineBounds,
                )
            }

            item { GroupTitle("重建数据") }
            item {
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
                    Text(
                        "下面每一项都会直接改写本机数据库，不会上传任何内容，也不会删除原图。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                ActionRow(
                    title = "重建分类与来源",
                    subtitle = "重跑规则，不重新识别文字。约 1 分钟",
                    busy = state.busy == "正在重建分类与来源",
                    enabled = state.busy == null,
                    onClick = vm::rebuildClassification,
                )
            }
            item {
                ActionRow(
                    title = "重建特征标签",
                    subtitle = "长截图、二维码、大文件、空白等属性标签",
                    busy = state.busy == "正在重建特征标签",
                    enabled = state.busy == null,
                    onClick = vm::rebuildTags,
                )
            }
            item {
                ActionRow(
                    title = "重建重复分组",
                    subtitle = "完全重复（哈希一致）与视觉相似分组",
                    busy = state.busy == "正在重建重复分组",
                    enabled = state.busy == null,
                    onClick = vm::rebuildDuplicates,
                )
            }
            item {
                ActionRow(
                    title = "重建清理建议",
                    subtitle = "重新计算可清理候选，保护与收藏永不进入",
                    busy = state.busy == "正在重建清理建议",
                    enabled = state.busy == null,
                    onClick = vm::rebuildSuggestions,
                )
            }

            state.busy?.let { label ->
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(Spacing.sm))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            state.lastResult?.let { msg ->
                item {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    )
                }
            }

            item { GroupTitle("隐私") }
            item {
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                    Text(
                        "ScreenBox 的全部处理都在本机完成。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "应用没有申请网络权限，截图与识别文字都无法离开设备。" +
                            "不含广告 SDK，不需要注册账号。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { GroupTitle("关于") }
            item {
                Surface(
                    shape = RoundedCornerShape(Radius.md),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    onClick = onOpenAbout,
                ) {
                    Row(
                        Modifier.padding(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("关于 ScreenBox", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "版本信息、开发者、其他项目与开源许可",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LineBackfillCard(
    state: SettingsUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onRetryFailed: () -> Unit,
) {
    val running = state.lineTaskStatus == "RUNNING"
    val total = state.lineTaskTotal
    val done = state.lineTaskDone.coerceAtMost(total.coerceAtLeast(1))
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text("补齐文字行坐标", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                "用于分析截图版式（聊天、文档、界面）。后台运行，可暂停；" +
                    "已经完成的图片不会重复识别。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已完成 ${state.lineBoundsDone} 张",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "待回填 ${state.lineBoundsTodo} 张",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.lineBoundsTodo > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (state.lineBoundsFailed > 0) {
                Spacer(Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.lineBoundsFailed} 张读取失败（文件可能已删除）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onRetryFailed) {
                        Text("重试", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (total > 0) {
                Spacer(Modifier.height(Spacing.sm))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "${statusLabel(state.lineTaskStatus)} · 本次任务 $done / $total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.lineTaskError?.let { err ->
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    err,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(Spacing.md))
            Row {
                Button(
                    onClick = onStart,
                    enabled = !running && state.lineBoundsTodo > 0,
                ) {
                    Text(if (state.lineTaskStatus == "PAUSED") "继续" else "开始")
                }
                if (running) {
                    Spacer(Modifier.width(Spacing.sm))
                    OutlinedButton(onClick = onPause) { Text("暂停") }
                }
            }
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "RUNNING" -> "后台运行中"
    "PAUSED" -> "已暂停"
    "COMPLETED" -> "已完成"
    "FAILED" -> "已停止"
    else -> "未开始"
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onClick, enabled = enabled || busy) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Text("执行", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Column {
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
    }
}
