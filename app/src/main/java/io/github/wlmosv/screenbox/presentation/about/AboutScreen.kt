@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.wlmosv.screenbox.presentation.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wlmosv.screenbox.BuildConfig
import io.github.wlmosv.screenbox.R
import io.github.wlmosv.screenbox.core.ui.theme.Radius
import io.github.wlmosv.screenbox.core.ui.theme.Spacing

/**
 * 关于页。
 *
 * 信息层级：品牌 → 能力 → 开发者 → 其他项目 → 隐私 → 许可 → 反馈。
 *
 * 所有链接都是核实过的；BehaviorAudit 没有公开仓库，就不放链接，
 * 也不写「即将上线」。未确认的东西不假装存在。
 */
private data class Project(
    val name: String,
    val tagline: String,
    val description: String,
    val icon: ImageVector? = null,
    val tint: Color,
    val gitHub: String? = null,
    val site: String? = null,
    val status: String,
    val statusNote: String = "",
)

private val projects = listOf(
    Project(
        name = "WPAForge",
        tagline = "Android Wi-Fi 安全审计",
        description = "用真实关联验证而不是猜测来测试自家 Wi-Fi 的密码强度，" +
            "支持 WPS、离线握手包校验与信道诊断。需要 Root。",
        tint = Color(0xFF2F6F6B),
        gitHub = "https://github.com/wlmosv-png/wpaforge",
        site = "https://wlmosv.dpdns.org",
        status = "持续维护",
        statusNote = "仓库 2026-11 仍在更新",
    ),
    Project(
        name = "TGAutoSign",
        tagline = "Telegram 自动签到",
        description = "在 Telegram 客户端里自动完成每日签到，支持回调按钮、" +
            "文本指令、群组或多账号，并提供内置管理面板。",
        tint = Color(0xFF3B6FA8),
        gitHub = "https://github.com/wlmosv-png/TGAutoSign",
        status = "持续维护",
        statusNote = "仓库 2026-11 仍在更新",
    ),
    Project(
        name = "BehaviorAudit",
        tagline = "应用行为与权限审计",
        description = "记录应用实际申请、使用权限的过程，把权限声明与真实行为" +
            "放在一起对照，输出可读的审计报告。",
        tint = Color(0xFF6B5D3E),
        status = "开发中",
        statusNote = "尚未公开仓库，因此不提供下载链接",
    ),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于", style = MaterialTheme.typography.titleLarge) },
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
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.huge),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { BrandHeader() }

            item { GroupLabel("它做什么") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FeatureRow(
                        Icons.Outlined.Search,
                        "按内容找图",
                        "搜截图中出现过的文字，也能按日期、金额、订单号定位。",
                    )
                    FeatureRow(
                        Icons.Outlined.AutoAwesome,
                        "自动归类",
                        "识别聊天、订单、二维码、游戏等用途，识别不出来就如实留空。",
                    )
                    FeatureRow(
                        Icons.Outlined.CleaningServices,
                        "有依据地清理",
                        "只对完全重复、明确过期、近乎空白的图片给出建议，并附上原因。",
                    )
                    FeatureRow(
                        Icons.Outlined.Shield,
                        "本地处理",
                        "全程在本机完成，不需要账号，也不会上传截图或文字。",
                    )
                }
            }

            item { GroupLabel("开发者") }
            item {
                Surface(
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Spacing.lg)) {
                        Text("wlmosv", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "独立开发者，做 Android 系统层工具：自动化、审计与逆向。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            LinkChip("GitHub", "https://github.com/wlmosv-png") { openUrl(context, it) }
                            LinkChip("个人站点", "https://wlmosv.dpdns.org") { openUrl(context, it) }
                        }
                    }
                }
            }

            item { GroupLabel("其他项目") }
            items(projects.size) { i ->
                ProjectCard(projects[i]) { openUrl(context, it) }
            }

            item { GroupLabel("隐私") }
            item {
                Surface(
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Spacing.lg)) {
                        Text(
                            "ScreenBox 不申请网络权限，因此没有能力把图片或识别结果发出去。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        listOf(
                            "只读取你授权的照片，不访问其他应用的数据。",
                            "文字识别使用随应用安装的本地模型，离线运行。",
                            "收藏、保护与删除清单只存在本机数据库。",
                            "没有广告 SDK，没有统计 SDK，不需要注册。",
                        ).forEach { line ->
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text("·", color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item { GroupLabel("开源与许可") }
            item {
                Surface(
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showLicenses = true },
                ) {
                    Row(
                        Modifier.padding(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text("第三方组件声明", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "AndroidX、Compose、Room、WorkManager、Coil、ML Kit",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Outlined.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            item { GroupLabel("反馈与更新") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LinkRow(
                        "问题反馈",
                        "在 GitHub 上描述你遇到的复现步骤",
                    ) { openUrl(context, "https://github.com/wlmosv-png") }
                    LinkRow(
                        "项目主页",
                        "查看其他工具与更新记录",
                    ) { openUrl(context, "https://wlmosv.dpdns.org") }
                }
            }

            item { BuildFooter() }
        }
    }

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
}

@Composable
private fun BrandHeader() {
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.lg, bottom = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            modifier = Modifier.size(76.dp).clip(RoundedCornerShape(Radius.lg)),
        )
        Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f)) {
            Text("ScreenBox", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "让截图更容易找到、整理和清理。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectCard(p: Project, onOpen: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(p.tint),
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = when (p.name) {
                        "WPAForge" -> R.drawable.ic_project_wpaforge
                        "TGAutoSign" -> R.drawable.ic_project_tgautosign
                        else -> R.drawable.ic_project_behavioraudit
                    }
                    Image(
                        painter = painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        p.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        p.status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (p.status == "开发中") {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                p.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (p.statusNote.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    p.statusNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (p.gitHub != null || p.site != null) {
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    p.gitHub?.let { LinkChip("GitHub", it, onOpen) }
                    p.site?.let { LinkChip("官网", it, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun LinkChip(label: String, url: String, onOpen: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = { onOpen(url) },
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, detail: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun BuildFooter() {
    Spacer(Modifier.height(Spacing.lg))
    Text(
        "构建 ${BuildConfig.BUILD_TYPE} · ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = Spacing.xs),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "ScreenBox 不提供云同步，卸载前请确认删除清单为空。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = Spacing.xs),
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs),
    )
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val rows = listOf(
        "AndroidX / Jetpack Compose" to "Apache License 2.0",
        "Kotlin & Kotlin Coroutines" to "Apache License 2.0",
        "Room" to "Apache License 2.0",
        "WorkManager" to "Apache License 2.0",
        "Navigation Compose" to "Apache License 2.0",
        "Coil" to "Apache License 2.0",
        "ML Kit 文字识别（中文）" to "Google ML Kit Terms · 本地模型",
        "SQLite" to "Public Domain",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("第三方组件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "以下组件随应用一同分发，均按其原始许可使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rows.forEach { (name, license) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            license,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 打开外部链接；没有可用浏览器时给出明确反馈，不静默失败。 */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "没有可以打开链接的应用", Toast.LENGTH_SHORT).show()
        }
}
