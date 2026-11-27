package io.github.wlmosv.screenbox.presentation.cleanup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing
import io.github.wlmosv.screenbox.core.ui.components.ZoomableImage
import io.github.wlmosv.screenbox.presentation.home.formatBytes

/**
 * 删除清单页。
 *
 * 这是删除前的最后一道闸。用户在这里看到：
 * - 到底要删多少张、多大体积
 * - 按分类和来源的分布
 * - 被保护机制拦下了多少张
 * - 逐张文件清单
 *
 * 只有点「确认删除」并**在系统弹窗里点允许**，文件才会真正消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletePlanScreen(
    token: String,
    onBack: () -> Unit,
    vm: DeletePlanViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    /** 点击缩略图放大的目标。 */
    var previewShot by remember { mutableStateOf<io.github.wlmosv.screenbox.data.db.ScreenshotEntity?>(null) }

    LaunchedEffect(token) { vm.prepareFromToken(token) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val opId = state.pendingOperationId
        if (opId != null) {
            vm.finalize(opId, result.resultCode == Activity.RESULT_OK)
        }
    }

    LaunchedEffect(state.pendingSender) {
        val sender = state.pendingSender ?: return@LaunchedEffect
        runCatching {
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        }.onFailure {
            state.pendingOperationId?.let { op -> vm.finalize(op, false) }
        }
    }

    LaunchedEffect(state.resultMessage) {
        val msg = state.resultMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("删除清单", style = MaterialTheme.typography.titleLarge) },
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
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
            return@Scaffold
        }

        val plan = state.plan
        if (state.expired) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text("选择已失效", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "可能是应用被系统回收过。请返回重新选择要删除的截图。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    Button(onClick = onBack) { Text("返回重新选择") }
                }
            }
            return@Scaffold
        }
        if (plan == null || !plan.canProceed) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "没有可删除的截图",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "选中的截图都被保护、收藏，或文件已不存在。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            // 汇总
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    shape = RoundedCornerShape(Radius.md),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(Spacing.lg)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "${plan.deletable.size}",
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                "张截图",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                formatBytes(plan.totalBytes),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            // 排除提示
            if (state.protectedCount > 0 || state.favoriteCount > 0 || state.missingCount > 0) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                        shape = RoundedCornerShape(Radius.md),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Column(Modifier.padding(Spacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    "已自动排除",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Spacer(Modifier.height(Spacing.xs))
                            if (state.protectedCount > 0) {
                                Text(
                                    "· ${state.protectedCount} 张已保护",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            if (state.favoriteCount > 0) {
                                Text(
                                    "· ${state.favoriteCount} 张已收藏",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            if (state.missingCount > 0) {
                                Text(
                                    "· ${state.missingCount} 张文件已不存在",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }

            // 分类/来源分布
            if (state.breakdown.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                        Text(
                            "构成",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        state.breakdown.forEach { b ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    b.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${b.count} 张 · ${formatBytes(b.bytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
                Text(
                    "即将删除的文件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }

            // 文件清单
            items(plan.deletable, key = { it.id }) { shot ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 缩略图可点，进全屏查看 —— 用户必须能在删之前看清每一张
                    AsyncImage(
                        model = shot.contentUri,
                        contentDescription = "预览 ${shot.displayName}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 44.dp, height = 66.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { previewShot = shot },
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            shot.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${formatBytes(shot.fileSize)} · ${shot.width}×${shot.height}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 底部确认按钮
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
                            "确认后系统会再次询问",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = vm::execute,
                        enabled = !state.executing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        if (state.executing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Spacing.xs))
                            Text("删除这 ${plan.deletable.size} 张")
                        }
                    }
                }
            }
        }
    }

    // ── 全屏预览层 ──
    // 删除前必须能看清每一张。单击退出，双指缩放，双击放大。
    previewShot?.let { shot ->
        Surface(
            color = androidx.compose.ui.graphics.Color.Black,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                ZoomableImage(
                    model = shot.contentUri,
                    contentDescription = shot.displayName,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { previewShot = null },
                )
                // 顶部信息条：让用户确认这是哪张、多大
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { previewShot = null }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "关闭预览",
                            tint = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            shot.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${formatBytes(shot.fileSize)} · ${shot.width}×${shot.height}",
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}
