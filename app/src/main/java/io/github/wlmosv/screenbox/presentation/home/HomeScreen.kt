package io.github.wlmosv.screenbox.presentation.home

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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.wlmosv.screenbox.core.permission.MediaPermissionState
import io.github.wlmosv.screenbox.core.permission.MediaPermissions
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing
import io.github.wlmosv.screenbox.data.repo.ScanPhase
import io.github.wlmosv.screenbox.data.repo.TaskStatus

@Composable
fun HomeScreen(
    onOpenGallery: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit = {},
    onOpenExactDuplicates: () -> Unit,
    onOpenSimilar: () -> Unit,
    onOpenSuggestions: () -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenExpired: () -> Unit,
    onOpenLarge: () -> Unit,
    onOpenUnrecognized: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        val msg = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.consumeToast()
    }

    Box(Modifier.fillMaxSize()) {
        when {
            MediaPermissions.needsPermissionGate(state.permission) -> PermissionGate(
                state = state.permission,
                onRequest = onRequestPermission,
                onOpenSettings = onOpenAppSettings,
            )
            state.loading -> LoadingGate()
            !state.libraryReady && state.scan.isRunning -> FirstScanProgress(
                scan = state.scan,
                onCancel = vm::cancelScan,
            )
            !state.libraryReady -> FirstRunGate(
                onScan = { vm.startScan(full = true) },
                lastError = state.scan.takeIf { it.phase == ScanPhase.FAILED }?.message,
            )
            else -> HomeContent(
                state = state,
                onRequestPermission = onRequestPermission,
                onOpenAppSettings = onOpenAppSettings,
                onOpenGallery = onOpenGallery,
                onOpenSearch = onOpenSearch,
                onOpenDetail = onOpenDetail,
                onOpenSettings = onOpenSettings,
                onScan = { vm.startScan(full = false) },
                onFullScan = { vm.startScan(full = true) },
                onCancelScan = vm::cancelScan,
                onStartOcr = { vm.startOcr() },
                onPauseOcr = vm::pauseOcr,
                onResumeOcr = vm::resumeOcr,
                onCancelOcr = vm::cancelOcr,
                onRetryFailedOcr = vm::retryFailedOcr,
                onStartFingerprint = vm::startFingerprint,
                onStartAnalysis = vm::startAnalysis,
                onOpenExactDuplicates = onOpenExactDuplicates,
                onOpenSimilar = onOpenSimilar,
                onOpenSuggestions = onOpenSuggestions,
                onOpenBrowse = onOpenBrowse,
                onOpenTimeline = onOpenTimeline,
                onOpenExpired = onOpenExpired,
                onOpenLarge = onOpenLarge,
                onOpenUnrecognized = onOpenUnrecognized,
            )
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenGallery: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onScan: () -> Unit,
    onFullScan: () -> Unit,
    onCancelScan: () -> Unit,
    onStartOcr: () -> Unit,
    onPauseOcr: () -> Unit,
    onResumeOcr: () -> Unit,
    onCancelOcr: () -> Unit,
    onRetryFailedOcr: () -> Unit,
    onStartFingerprint: () -> Unit,
    onStartAnalysis: () -> Unit,
    onOpenExactDuplicates: () -> Unit,
    onOpenSimilar: () -> Unit,
    onOpenSuggestions: () -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenExpired: () -> Unit,
    onOpenLarge: () -> Unit,
    onOpenUnrecognized: () -> Unit,
    onRequestPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.huge),
    ) {
        item { HomeHeader(onOpenSettings = onOpenSettings) }

        // 部分授权提示 —— 实测：撤销完整权限后系统仍保留
        // READ_MEDIA_VISUAL_USER_SELECTED，状态变 PARTIAL，
        // 但首页没有任何提示，用户看到 8137 张却不知道只授权了一部分。
        if (state.permission == io.github.wlmosv.screenbox.core.permission.MediaPermissionState.PARTIAL) {
            item {
                PartialAccessBanner(
                    onReauthorize = onRequestPermission,
                    onOpenSettings = onOpenAppSettings,
                )
            }
        }

        // ── 搜索入口：首页直达 ──
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                shape = RoundedCornerShape(Radius.md),
                color = MaterialTheme.colorScheme.surfaceContainer,
                onClick = onOpenSearch,
            ) {
                Row(
                    Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        "搜索截图里的文字、订单号、金额…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 资产概览：三个数字，定义明确 ──
        item {
            HeroBlock(
                total = state.stats.totalScreenshots,
                recognized = state.stats.recognizedCount,
                bytes = state.stats.totalBytes,
                onOpenGallery = onOpenGallery,
                onOpenUnrecognized = onOpenUnrecognized,
                onOpenLarge = onOpenLarge,
            )
        }

        // ── 三个独立任务的进度 ──
        if (state.scan.isRunning) {
            item {
                TaskRow(
                    label = "建立图库",
                    detail = state.scan.message ?: state.scan.phase.label,
                    done = state.scan.current,
                    total = state.scan.total,
                    fraction = state.scan.fraction.takeIf { state.scan.total > 0 },
                    actionLabel = "取消",
                    onAction = onCancelScan,
                )
            }
        }
        if (state.fingerprint.running) {
            item {
                TaskRow(
                    label = "计算指纹",
                    detail = "重复检测的前置步骤",
                    done = state.fingerprint.done,
                    total = state.fingerprint.total,
                    fraction = state.fingerprint.fraction.takeIf { state.fingerprint.total > 0 },
                    actionLabel = null,
                    onAction = null,
                )
            }
        }
        // 分析任务：运行中或未完成时都显示，让用户能继续
        if (state.analysis.running || state.analysis.hasWork) {
            item {
                TaskRow(
                    label = if (state.analysis.running) "分析图片" else "分析图片（已暂停）",
                    detail = "计算指纹与画面复杂度，用于重复检测",
                    done = state.analysis.done,
                    total = state.analysis.total,
                    fraction = state.analysis.fraction.takeIf { state.analysis.total > 0 },
                    actionLabel = if (state.analysis.running) null else "继续",
                    onAction = if (state.analysis.running) null else onStartAnalysis,
                )
            }
        }
        if (state.ocr.status != TaskStatus.IDLE && state.ocr.status != TaskStatus.COMPLETED) {
            item {
                TaskRow(
                    label = when (state.ocr.status) {
                        TaskStatus.PAUSED -> "文字识别（已暂停）"
                        TaskStatus.FAILED -> "文字识别（失败）"
                        TaskStatus.STOPPING -> "文字识别（正在停止）"
                        else -> "文字识别"
                    },
                    detail = buildString {
                        append(state.ocr.policy.label)
                        append(" · 待处理 ${state.stats.pendingOcrCount} 张")
                        if (state.ocr.throughput > 0) {
                            append(" · %.1f 张/秒".format(state.ocr.throughput))
                        }
                        if (state.ocr.failed > 0) append(" · 失败 ${state.ocr.failed}")
                        if (state.ocr.thermalPaused) append(" · 温度过高")
                    },
                    done = state.ocr.done,
                    total = state.ocr.total,
                    fraction = state.ocr.fraction.takeIf { state.ocr.total > 0 },
                    actionLabel = if (state.ocr.paused) "继续" else "暂停",
                    onAction = if (state.ocr.paused) onResumeOcr else onPauseOcr,
                    secondaryLabel = "停止",
                    onSecondary = onCancelOcr,
                    note = state.ocr.healthNote,
                )
            }
        }
        // OCR 还没开始但有待识别
        if (state.ocr.status == TaskStatus.IDLE && state.stats.pendingOcrCount > 0) {
            item {
                ActionRow(
                    icon = Icons.Outlined.Search,
                    title = "${state.stats.pendingOcrCount} 张还没识别文字",
                    subtitle = "识别后才能搜索截图里的内容",
                    onClick = onStartOcr,
                )
            }
        }

        // ── 处理中的缩略图（让用户看到"系统真的在工作"）──
        if (state.processingShots.isNotEmpty() &&
            (state.ocr.running || state.scan.isRunning)
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    state.processingShots.take(6).forEach { shot ->
                        AsyncImage(
                            model = shot.contentUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 46.dp, height = 76.dp)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "最近处理",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Bottom),
                    )
                }
            }
        }

        // ── 整理入口：每个都有独立页面 ──
        item { SectionHeader("整理你的截图") }

        if (state.stats.exactGroupCount > 0) {
            item {
                ActionRow(
                    icon = Icons.Outlined.ContentCopy,
                    title = "${state.stats.exactGroupCount} 组完全重复",
                    subtitle = "多出 ${state.stats.exactExtraFiles} 个文件" +
                        if (state.stats.reclaimableBytes > 0) "，可释放 ${formatBytes(state.stats.reclaimableBytes)}" else "",
                    onClick = onOpenExactDuplicates,
                )
            }
        }
        if (state.stats.similarGroupCount > 0) {
            item {
                ActionRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "${state.stats.similarGroupCount} 组画面相似",
                    subtitle = "相似不等于重复，需要你对比后决定",
                    onClick = onOpenSimilar,
                )
            }
        }
        item {
            ActionRow(
                icon = Icons.Outlined.FilterList,
                title = "分类与筛选",
                subtitle = "按用途、来源、时间、大小自由组合筛选",
                onClick = onOpenBrowse,
            )
        }
        item {
            ActionRow(
                icon = Icons.Outlined.Schedule,
                title = "按时间清理",
                subtitle = "按年月分组，勾选整批加入清单",
                onClick = onOpenTimeline,
            )
        }
        if (state.stats.cleanupCandidates > 0) {
            item {
                ActionRow(
                    icon = Icons.Outlined.Warning,
                    title = "${state.stats.cleanupCandidates} 项待你确认",
                    subtitle = "每一条都有具体理由，删除前逐张核对",
                    onClick = onOpenSuggestions,
                )
            }
        }
        item {
            ActionRow(
                icon = Icons.Outlined.ErrorOutline,
                title = "过期与临时信息",
                subtitle = "截图中出现已过期的日期",
                onClick = onOpenExpired,
            )
        }
        if (state.stats.largeFileCount > 0) {
            item {
                ActionRow(
                    icon = Icons.Outlined.Storage,
                    title = "${state.stats.largeFileCount} 张大文件",
                    subtitle = "单张超过 2 MB，可优先检查",
                    onClick = onOpenLarge,
                )
            }
        }
        if (state.stats.pendingOcrCount > 0) {
            item {
                ActionRow(
                    icon = Icons.Outlined.Search,
                    title = "${state.stats.pendingOcrCount} 张未识别",
                    subtitle = "重新识别或手动分类",
                    onClick = onOpenUnrecognized,
                )
            }
        }

        // ── 最近截图 ──
        item {
            SectionHeader(title = "最近截图", actionLabel = "查看全部", onAction = onOpenGallery)
        }
        item {
            if (state.recentShots.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(Spacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有截图",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(state.recentShots, key = { it.id }) { shot ->
                        AsyncImage(
                            model = shot.contentUri,
                            contentDescription = shot.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 108.dp, height = 192.dp)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { onOpenDetail(shot.id) },
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(Spacing.xl))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                    enabled = !state.scan.isRunning,
                ) { Text("扫描新截图") }
                OutlinedButton(
                    onClick = onFullScan,
                    modifier = Modifier.weight(1f),
                    enabled = !state.scan.isRunning,
                ) { Text("完整重扫") }
            }
        }
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.md, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "ScreenBox",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 资产概览。
 *
 * 只放三个数字，每个都有明确含义：
 * - 总数：当前有效索引
 * - 已识别：OCR 完成数
 * - 占用：总文件大小
 *
 * 不放"可搜索百分比"这类派生指标 —— 用户不关心百分比，只关心能不能搜到。
 */
@Composable
private fun HeroBlock(
    total: Int,
    recognized: Int,
    bytes: Long,
    onOpenGallery: () -> Unit,
    onOpenUnrecognized: () -> Unit,
    onOpenLarge: () -> Unit,
) {
    Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.sm))
                .clickable(onClick = onOpenGallery)
                .padding(vertical = 2.dp),
        ) {
            Text(
                text = total.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "张截图",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        // 每个数字都能点进去看对应图片 —— 统计不应该是死数字
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            MiniStat(
                "已识别",
                if (total == 0) "—" else recognized.toString(),
                Modifier.weight(1f),
                onClick = onOpenGallery,
            )
            MiniStat(
                "未识别",
                if (total == 0) "—" else (total - recognized).toString(),
                Modifier.weight(1f),
                onClick = onOpenUnrecognized,
            )
            MiniStat("占用空间", formatBytes(bytes), Modifier.weight(1f), onClick = onOpenLarge)
        }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.xl, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
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
                    .size(34.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 任务进度行。
 * 三个任务共用，视觉一致，用户一眼能分清谁在跑。
 */
@Composable
private fun TaskRow(
    label: String,
    detail: String,
    done: Int,
    total: Int,
    fraction: Float?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    note: String? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 3.dp),
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            // 标题 + 进度数字同一行（数字固定宽度，不挤标题）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (total > 0) {
                    Text(
                        text = "$done / $total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 详情独占一行，避免被按钮挤到截断
            Text(
                text = note ?: detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (note != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 操作按钮单独一行，右对齐
            if (actionLabel != null || secondaryLabel != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (actionLabel != null && onAction != null) {
                        TextButton(
                            onClick = onAction,
                            contentPadding = PaddingValues(horizontal = Spacing.sm),
                        ) { Text(actionLabel, style = MaterialTheme.typography.labelMedium) }
                    }
                    if (secondaryLabel != null && onSecondary != null) {
                        TextButton(
                            onClick = onSecondary,
                            contentPadding = PaddingValues(horizontal = Spacing.sm),
                        ) { Text(secondaryLabel, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            if (fraction != null) {
                Spacer(Modifier.height(Spacing.sm))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(Radius.pill)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

@Composable
private fun PermissionGate(
    state: MediaPermissionState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val (title, body) = MediaPermissions.explain(state)
    // 永久拒绝只能去设置，其余可以再请求
    val primaryIsSettings = state == MediaPermissionState.PERMANENTLY_DENIED ||
        state == MediaPermissionState.UNAVAILABLE

    Column(
        Modifier.fillMaxSize().padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 隐私说明只在首次请求时展示
        if (state == MediaPermissionState.NOT_REQUESTED) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                "所有识别和整理都在手机本地完成。图片不会上传，也不需要注册账号。\n\n" +
                    "你可以只授权部分照片，之后随时在系统设置里调整范围。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.xl))
        Button(
            onClick = if (primaryIsSettings) onOpenSettings else onRequest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (state) {
                    MediaPermissionState.NOT_REQUESTED -> "选择照片访问范围"
                    MediaPermissionState.DENIED -> "重新授权"
                    MediaPermissionState.REVOKED -> "重新授权"
                    MediaPermissionState.PARTIAL -> "重新选择访问范围"
                    MediaPermissionState.PERMANENTLY_DENIED -> "去系统设置开启"
                    MediaPermissionState.UNAVAILABLE -> "查看应用设置"
                    MediaPermissionState.FULL -> "已完成"
                }
            )
        }

        // 次按钮：去设置（非永久拒绝时也提供，方便用户手动改）
        if (!primaryIsSettings && state != MediaPermissionState.FULL) {
            Spacer(Modifier.height(Spacing.sm))
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("去系统设置查看权限")
            }
        }

        // 部分授权时提示可以继续用
        if (state == MediaPermissionState.PARTIAL) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                "也可以先继续使用当前范围，之后再扩大授权。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LoadingGate() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun FirstRunGate(onScan: () -> Unit, lastError: String? = null) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            "开始整理你的截图",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            "首次扫描会读取截图并建立本地索引。截图越多，第一次会越久，之后都是增量更新。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (lastError != null) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                "上次扫描失败：$lastError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        Button(onClick = onScan) { Text("开始扫描") }
    }
}

@Composable
private fun FirstScanProgress(
    scan: io.github.wlmosv.screenbox.data.repo.ScanProgress,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.xxl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = scan.phase.label,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = scan.message ?: "正在准备…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
        LinearProgressIndicator(
            progress = { if (scan.total > 0) scan.fraction else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(Radius.pill)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        if (scan.total > 0) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "${scan.current} / ${scan.total}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = "扫描期间可以随时暂停，已完成的进度会保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        TextButton(onClick = onCancel) { Text("暂停扫描") }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}


/**
 * 部分授权提示条。
 *
 * 实测场景：用户在系统设置里撤销"全部照片"后，Android 14+ 仍保留
 * READ_MEDIA_VISUAL_USER_SELECTED，应用状态是 PARTIAL 而非 DENIED。
 * 此时图库只能看到用户选中的那几张，但界面毫无提示 ——
 * 用户会以为自己的 8137 张截图丢了。
 */
@Composable
private fun PartialAccessBanner(
    onReauthorize: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "只授权了部分照片",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "ScreenBox 目前只能看到你选中的那几张。要整理全部截图，请重新授权。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        TextButton(onClick = onReauthorize) { Text("重新授权") }
    }
}
