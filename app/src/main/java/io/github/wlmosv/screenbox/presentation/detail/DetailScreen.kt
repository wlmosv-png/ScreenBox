package io.github.wlmosv.screenbox.presentation.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShieldMoon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import io.github.wlmosv.screenbox.core.ui.components.OcrOverlayImage
import io.github.wlmosv.screenbox.core.ui.components.ZoomableImage
import io.github.wlmosv.screenbox.core.ui.components.MetaRow
import io.github.wlmosv.screenbox.core.ui.components.SectionLabel
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing
import io.github.wlmosv.screenbox.data.db.OcrStatus
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    screenshotId: Long,
    onBack: () -> Unit,
    onDelete: (Long) -> Unit,
    vm: DetailViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var textExpanded by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(true) }
    // 单击图片进入全屏查看
    var fullscreen by remember { mutableStateOf(false) }
    // 是否显示 OCR 文字框
    var showOcrBoxes by remember { mutableStateOf(false) }
    // 分类选择器
    var showCategoryPicker by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(screenshotId) {
        vm.load(screenshotId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::toggleFavorite) {
                        Icon(
                            if (state.shot?.isFavorite == true) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "收藏",
                            tint = if (state.shot?.isFavorite == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = vm::toggleProtect) {
                        Icon(
                            if (state.shot?.protectionStatus == ProtectionStatus.PROTECTED)
                                Icons.Outlined.ShieldMoon else Icons.Outlined.Shield,
                            contentDescription = "保护",
                            tint = if (state.shot?.protectionStatus == ProtectionStatus.PROTECTED)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
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
        val shot = state.shot
        if (shot == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                else Text("截图不存在或已被删除", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        Column(Modifier.padding(padding).fillMaxSize()) {
            // ── 图片区：占屏幕 45%，可缩放/双击/拖动 ──
            // 关键改动：v2 把图锁在 380dp 且不能缩放，长截图文字根本看不清。
            // 现在给足高度 + 完整手势支持，双击放大到 2.5x 看小字。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                // OCR 文字框叠加：用户能看到"识别出的文字在图上哪里"
                OcrOverlayImage(
                    model = shot.contentUri,
                    contentDescription = shot.displayName,
                    blockBoundsJson = state.ocr?.blockBounds,
                    showBoxes = showOcrBoxes,
                    sourceWidth = shot.width,
                    sourceHeight = shot.height,
                    modifier = Modifier.fillMaxSize().clickable { fullscreen = true },
                )
                // 缩放提示，首次进入显示 3 秒
                var hintVisible by remember { mutableStateOf(true) }
                androidx.compose.runtime.LaunchedEffect(shot.id) {
                    hintVisible = true
                    kotlinx.coroutines.delay(3000)
                    hintVisible = false
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = hintVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(Spacing.sm),
                        modifier = Modifier.padding(bottom = Spacing.md),
                    ) {
                        Text(
                            "点击图片放大查看",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        )
                    }
                }
            }

            // ── 信息区：独立滚动 ──
            LazyColumn(
                modifier = Modifier.weight(0.55f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = Spacing.huge),
            ) {

            // 保护状态提示
            if (shot.protectionStatus == ProtectionStatus.PROTECTED ||
                shot.protectionStatus == ProtectionStatus.FAVORITE ||
                shot.isFavorite
            ) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        shape = RoundedCornerShape(Radius.md),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            Modifier.padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(Spacing.sm))
                            Text(
                                "已保护，不会进入任何清理建议",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            // 敏感信息提醒
            if (state.sensitive.isNotEmpty()) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        shape = RoundedCornerShape(Radius.md),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text(
                                "这张截图可能包含敏感信息",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            state.sensitive.forEach { hit ->
                                Text(
                                    "· ${hit.kind}：${hit.sample}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "仅本地提示，不会上传，也不影响删除判断。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            // 分类
            item {
                SectionLabel("分类")
                Row(
                    Modifier.padding(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (state.categories.isEmpty()) {
                        Text(
                            "未分类",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.categories.take(3).forEach { (cat, rel) ->
                            AssistChip(
                                onClick = { showCategoryPicker = true },
                                label = {
                                    Text(
                                        cat.displayName,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (rel.isUserConfirmed)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                ),
                                shape = RoundedCornerShape(Radius.pill),
                            )
                        }
                    }
                    // 手动修改入口 —— 用户纠正必须能落地，且不被自动分析覆盖
                    TextButton(onClick = { showCategoryPicker = true }) {
                        Text("修改", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // OCR 文本
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.lg, bottom = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "识别文字",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.ocr?.blockBounds != null) {
                        IconButton(onClick = { showOcrBoxes = !showOcrBoxes }) {
                            Icon(
                                if (showOcrBoxes) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showOcrBoxes) "隐藏文字框" else "显示文字框",
                                modifier = Modifier.size(18.dp),
                                tint = if (showOcrBoxes) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.ocr?.recognizedText?.isNotBlank() == true) {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(state.ocr!!.recognizedText))
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { showText = !showText }) {
                            Icon(
                                if (showText) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = showText) {
                    Column(Modifier.padding(horizontal = Spacing.lg)) {
                        val ocr = state.ocr
                        when {
                            ocr == null || ocr.recognitionStatus == OcrStatus.NOT_STARTED -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "尚未识别",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = vm::reprocessOcr, enabled = !state.reprocessing) {
                                        Text(if (state.reprocessing) "识别中…" else "立即识别")
                                    }
                                }
                            }
                            ocr.recognitionStatus == OcrStatus.FAILED -> {
                                Column {
                                    Text(
                                        "识别失败：${ocr.errorMessage ?: "未知原因"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    TextButton(onClick = vm::reprocessOcr, enabled = !state.reprocessing) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.size(Spacing.xs))
                                        Text("重试")
                                    }
                                }
                            }
                            ocr.recognizedText.isBlank() -> {
                                Text(
                                    "这张截图里没有识别到文字。可能是纯图片，或文字太小、太模糊。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> {
                                Text(
                                    text = ocr.recognizedText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = if (textExpanded) Int.MAX_VALUE else 8,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (ocr.recognizedText.length > 200) {
                                    TextButton(onClick = { textExpanded = !textExpanded }) {
                                        Text(if (textExpanded) "收起" else "展开全文")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 元信息
            item {
                SectionLabel("文件信息")
                Column(Modifier.padding(horizontal = Spacing.lg)) {
                    MetaRow("文件名", shot.displayName)
                    MetaRow("路径", shot.relativePath.ifBlank { "—" })
                    MetaRow("大小", formatSize(shot.fileSize))
                    MetaRow("尺寸", "${shot.width} × ${shot.height}")
                    MetaRow("修改时间", formatDate(shot.dateModified))
                    MetaRow("识别状态", ocrStatusLabel(shot.ocrStatus))
                    MetaRow(
                        "截图判定",
                        when (shot.screenshotConfidence) {
                            io.github.wlmosv.screenbox.data.db.ScreenshotConfidence.HIGH -> "高（系统截图目录 + 命名）"
                            io.github.wlmosv.screenbox.data.db.ScreenshotConfidence.MEDIUM -> "中（路径或命名其一）"
                            io.github.wlmosv.screenbox.data.db.ScreenshotConfidence.LOW -> "低（仅尺寸推断）"
                        },
                    )
                }
            }

            item {
                Spacer(Modifier.height(Spacing.xl))
                HorizontalDivider(Modifier.padding(horizontal = Spacing.lg))
                Spacer(Modifier.height(Spacing.lg))
                Box(Modifier.padding(horizontal = Spacing.lg)) {
                    TextButton(
                        onClick = { onDelete(shot.id) },
                        enabled = shot.protectionStatus != ProtectionStatus.PROTECTED &&
                            shot.protectionStatus != ProtectionStatus.FAVORITE &&
                            !shot.isFavorite,
                    ) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = if (shot.protectionStatus == ProtectionStatus.PROTECTED || shot.isFavorite)
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(Spacing.sm))
                        Text(
                            if (shot.protectionStatus == ProtectionStatus.PROTECTED || shot.isFavorite)
                                "已保护，取消保护后才能删除"
                            else "删除这张截图",
                            color = if (shot.protectionStatus == ProtectionStatus.PROTECTED || shot.isFavorite)
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            }
        }
    }

    // 全屏查看（Dialog，不丢失详情页滚动位置）
    if (fullscreen) {
        state.shot?.let { shot ->
            FullscreenImageViewer(
                model = shot.contentUri,
                description = shot.displayName,
                onDismiss = { fullscreen = false },
            )
        }
    }

    // 分类选择器 —— 用户纠正后写入 isUserConfirmed=true，自动任务不再覆盖
    if (showCategoryPicker) {
        CategoryPickerDialog(
            categories = state.allCategories,
            onPick = { id ->
                vm.setUserCategory(id)
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false },
        )
    }
}

/**
 * 分类选择器。
 *
 * 为什么不直接用下拉框：分类有 19 个，下拉在长截图上会超出屏幕，
 * 而且用户需要看到全部分类名才能判断。
 */
@Composable
private fun CategoryPickerDialog(
    categories: List<io.github.wlmosv.screenbox.data.db.CategoryEntity>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改内容分类") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items(categories.size) { i ->
                    val c = categories[i]
                    Surface(
                        onClick = { onPick(c.id) },
                        shape = RoundedCornerShape(Radius.sm),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            c.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(
                                horizontal = Spacing.md,
                                vertical = Spacing.sm,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 全屏图片查看。
 *
 * 交互：单击退出，双指可继续缩放拖动。
 * 用 Dialog 而不是新页面 —— 退出时不会丢失详情页的滚动位置。
 */
@Composable
private fun FullscreenImageViewer(
    model: Any?,
    description: String?,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            ZoomableImage(
                model = model,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                onClick = onDismiss,
            )
            // 顶部提示，3 秒后消失
            var hint by remember { mutableStateOf(true) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                hint = true
                kotlinx.coroutines.delay(2500)
                hint = false
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = hint,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(Spacing.sm),
                    modifier = Modifier.padding(top = 48.dp),
                ) {
                    Text(
                        "单击关闭 · 双指缩放",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun ocrStatusLabel(status: OcrStatus): String = when (status) {
    OcrStatus.NOT_STARTED -> "未识别"
    OcrStatus.QUEUED -> "排队中"
    OcrStatus.PROCESSING -> "识别中"
    OcrStatus.SUCCESS -> "已识别"
    OcrStatus.PARTIAL -> "部分识别"
    OcrStatus.FAILED -> "识别失败"
    OcrStatus.UNSUPPORTED -> "不支持"
}
