package io.github.wlmosv.screenbox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.core.permission.MediaPermissionState
import io.github.wlmosv.screenbox.core.permission.MediaPermissions
import io.github.wlmosv.screenbox.core.permission.PermissionStateHolder
import io.github.wlmosv.screenbox.core.ui.theme.ScreenBoxTheme
import io.github.wlmosv.screenbox.presentation.navigation.ScreenBoxApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主 Activity。
 *
 * ══ R4 关键修复 ══
 *
 * 旧版"点击选择照片访问范围无响应"的根因：
 * 1. 权限回调只判断"全部拒绝"，授权成功时什么也不做
 * 2. 没有通知任何 ViewModel 刷新权限状态
 * 3. UI 永远停在权限页，用户看到的就是"点了没反应"
 *
 * 新版：
 * 1. 回调无论成功失败都调 PermissionStateHolder.onPermissionResult()
 * 2. 所有页面订阅 PermissionStateHolder.state，实时响应
 * 3. onResume 自动重查（覆盖"去设置改完回来"）
 */
class MainActivity : ComponentActivity() {

    private var pendingDeleteOpId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Graph.init(this)
        PermissionStateHolder.init(this)
        PermissionStateHolder.observeLifecycle(this)

        setContent {
            ScreenBoxTheme {
                var showSettingsHint by remember { mutableStateOf(false) }
                var permissionError by remember { mutableStateOf<String?>(null) }

                // 权限请求回调
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    // ══ 关键修复 ══
                    // 不管结果如何，都要更新状态。
                    // 旧版只在"全部拒绝"时处理，授权成功时什么都不做 —— 这就是无响应的根因。
                    PermissionStateHolder.onPermissionResult(this@MainActivity)

                    val state = PermissionStateHolder.state.value
                    when (state) {
                        MediaPermissionState.FULL -> {
                            // 授权成功：立即触发首次扫描
                            Graph.init(applicationContext)
                        }
                        MediaPermissionState.PARTIAL -> {
                            // 部分授权：提示用户，但允许使用
                            permissionError = "当前只授权了部分照片，只能看到你选中的图片。"
                        }
                        MediaPermissionState.PERMANENTLY_DENIED -> {
                            showSettingsHint = true
                        }
                        MediaPermissionState.DENIED -> {
                            permissionError = "未获得照片访问权限，无法读取截图。"
                        }
                        else -> Unit
                    }
                }

                // 系统删除授权
                val deleteLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult(),
                ) { result ->
                    val opId = pendingDeleteOpId
                    pendingDeleteOpId = -1
                    if (opId > 0) {
                        val confirmed = result.resultCode == RESULT_OK
                        CoroutineScope(Dispatchers.IO).launch {
                            Graph.deleteRepo.finalizeOperation(opId, confirmed)
                        }
                    }
                }

                val requestPermission: () -> Unit = {
                    // 先刷新真实状态，再决定动作
                    PermissionStateHolder.refresh()
                    when (PermissionStateHolder.state.value) {
                        MediaPermissionState.PERMANENTLY_DENIED -> showSettingsHint = true
                        else -> {
                            runCatching {
                                permissionLauncher.launch(MediaPermissions.requiredPermissions())
                            }.onFailure { e ->
                                // 请求本身失败（如没有 Activity 上下文）也要有反馈
                                permissionError = "无法打开权限请求：${e.message}"
                            }
                        }
                    }
                }

                ScreenBoxApp(
                    onRequestPermission = requestPermission,
                    onOpenAppSettings = { openAppSettings() },
                )

                if (showSettingsHint) {
                    AlertDialog(
                        onDismissRequest = { showSettingsHint = false },
                        title = { Text("需要在系统设置中开启权限") },
                        text = {
                            Text(
                                "你之前拒绝了照片访问权限，系统不会再弹框。\n\n" +
                                    "请到：设置 → 应用 → ScreenBox → 权限 → 照片和视频，选择「允许全部」。"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showSettingsHint = false
                                openAppSettings()
                            }) { Text("去设置") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSettingsHint = false }) { Text("稍后") }
                        },
                    )
                }

                if (permissionError != null) {
                    AlertDialog(
                        onDismissRequest = { permissionError = null },
                        title = { Text("提示") },
                        text = { Text(permissionError!!) },
                        confirmButton = {
                            TextButton(onClick = { permissionError = null }) { Text("知道了") }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台都重查真实权限状态
        // 覆盖"用户去系统设置改完权限回来"的场景
        PermissionStateHolder.refresh()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }
}