package io.github.wlmosv.screenbox.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 媒体权限状态（v2）。
 *
 * ══ 为什么重写 ══
 *
 * 旧版三个致命问题，都导致"点了没反应"：
 *
 * 1. 把 READ_MEDIA_VISUAL_USER_SELECTED 和 READ_MEDIA_IMAGES 一起请求。
 *    系统只认第一个请求，第二个永远返回拒绝 —— 回调里判断
 *    "全部拒绝" 就误判成用户拒绝，然后什么都不做。
 *
 * 2. 状态只在 ViewModel init 时查一次。用户授权回来后
 *    没有任何人通知刷新，UI 永远停在权限页。
 *
 * 3. 把"被拒绝"和"被永久拒绝"混在一起，
 *    导致该去设置的场景弹系统框（无反应），该弹框的场景跳设置。
 *
 * ══ 新版设计 ══
 *
 * 1. 分版本请求正确的权限组合
 *    Android 13: 只请求 READ_MEDIA_IMAGES
 *    Android 14+: 请求 READ_MEDIA_IMAGES + READ_MEDIA_VISUAL_USER_SELECTED
 *                 （后者不是"请求"而是"声明"，系统会展示"选择照片"选项）
 *    Android 12-: 只请求 READ_EXTERNAL_STORAGE
 *
 * 2. 六态语义，每个都有明确的后续动作
 *
 * 3. 提供 shouldShowRationale 用于区分"能再问"和"只能去设置"
 */
enum class MediaPermissionState {
    /** 从未请求过。首次启动。 */
    NOT_REQUESTED,

    /** 被拒绝，但仍可再次请求（系统会弹框）。 */
    DENIED,

    /** 被永久拒绝，只能去系统设置。 */
    PERMANENTLY_DENIED,

    /** 只授权了部分照片（Android 14+ 的"选择照片"）。 */
    PARTIAL,

    /** 完整授权。 */
    FULL,

    /** 权限被系统或用户撤销（之前有，现在没了）。 */
    REVOKED,

    /** 当前系统版本上无法获取该权限（极端情况，如被 MDM 限制）。 */
    UNAVAILABLE,
}

/**
 * 权限判定的纯逻辑。
 *
 * 抽出来的唯一原因：设备只有一台 Android 15，无法在 10–14 上真机验证。
 * 把「SDK 版本 + 三个授权布尔」映射成状态的规则做成纯函数后，
 * 可以用单元测试穷举 29–35 的每一种组合，至少保证分支本身是对的。
 *
 * 这不能替代真机验证，但能排除"代码根本没写这个分支"的错误。
 */
object MediaPermissionRules {

    fun requiredPermissions(sdk: Int): Array<String> = when {
        sdk >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun fullAccessPermission(sdk: Int): String =
        if (sdk >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun supportsPartial(sdk: Int): Boolean = sdk >= 34

    fun decide(
        sdk: Int,
        fullGranted: Boolean,
        partialGranted: Boolean,
        hadPermissionBefore: Boolean,
    ): MediaPermissionState {
        if (fullGranted) return MediaPermissionState.FULL
        if (supportsPartial(sdk) && partialGranted) return MediaPermissionState.PARTIAL
        return if (hadPermissionBefore) MediaPermissionState.REVOKED
        else MediaPermissionState.NOT_REQUESTED
    }
}

object MediaPermissions {

    /**
     * 需要请求的权限列表。
     *
     * 关键：READ_MEDIA_VISUAL_USER_SELECTED 在 Android 14+ 必须声明，
     * 但它不是通过 requestPermissions 单独请求的 —— 系统在请求
     * READ_MEDIA_IMAGES 时会自动提供"选择照片"选项。
     * 一起传进去是安全的（系统会忽略它），但不能只传它。
     */
    fun requiredPermissions(): Array<String> =
        MediaPermissionRules.requiredPermissions(Build.VERSION.SDK_INT)

    /** 当前版本用于判断"完整访问"的权限名。 */
    fun fullAccessPermission(): String =
        MediaPermissionRules.fullAccessPermission(Build.VERSION.SDK_INT)

    /** 当前版本是否支持"部分照片"授权。 */
    fun supportsPartial(): Boolean =
        MediaPermissionRules.supportsPartial(Build.VERSION.SDK_INT)

    /**
     * 检查真实权限状态。
     *
     * @param context
     * @param hadPermissionBefore 上次是否已授权。用于区分 REVOKED 和 NOT_REQUESTED。
     */
    fun check(context: Context, hadPermissionBefore: Boolean = false): MediaPermissionState {
        val fullGranted = ContextCompat.checkSelfPermission(context, fullAccessPermission()) ==
            PackageManager.PERMISSION_GRANTED
        val partialGranted = supportsPartial() && ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED

        // 没权限时区分「从没有过」与「曾经有但被撤销」，由纯函数统一决定。
        return MediaPermissionRules.decide(
            sdk = Build.VERSION.SDK_INT,
            fullGranted = fullGranted,
            partialGranted = partialGranted,
            hadPermissionBefore = hadPermissionBefore,
        )
    }

    /**
     * 是否还能通过系统弹框请求。
     *
     * shouldShowRationale = true  → 用户拒绝过一次但没勾"不再询问"，可以再问
     * shouldShowRationale = false 且 没权限 → 要么从没问过，要么永久拒绝
     *
     * 调用方需要结合"是否请求过"来判断。这里只给原始信号。
     */
    fun shouldShowRationale(activity: android.app.Activity): Boolean =
        requiredPermissions().any { activity.shouldShowRequestPermissionRationale(it) }

    /** 是否能看到一些图片。 */
    fun canReadAnything(state: MediaPermissionState): Boolean =
        state == MediaPermissionState.FULL || state == MediaPermissionState.PARTIAL

    /** 是否需要向用户展示权限引导页。 */
    fun needsPermissionGate(state: MediaPermissionState): Boolean =
        !canReadAnything(state)

    /**
     * 给用户看的说明文案。
     * 每种状态都有明确的下一步动作，不允许"点了没反应"。
     */
    fun explain(state: MediaPermissionState): Pair<String, String> = when (state) {
        MediaPermissionState.NOT_REQUESTED -> "需要访问截图" to
            "ScreenBox 需要读取你的截图才能建立索引。所有处理都在本地完成，图片不会上传。"

        MediaPermissionState.DENIED -> "需要照片访问权限" to
            "没有照片访问权限，ScreenBox 无法读取截图。点击下方按钮重新授权。"

        MediaPermissionState.PERMANENTLY_DENIED -> "权限已被拒绝" to
            "你之前拒绝了照片访问权限。请到系统设置 → 应用 → ScreenBox → 权限，手动开启。"

        MediaPermissionState.PARTIAL -> "当前只授权了部分照片" to
            "ScreenBox 只能看到你选中的那几张图片。如需整理全部截图，请重新选择访问范围。"

        MediaPermissionState.FULL -> "权限正常" to ""

        MediaPermissionState.REVOKED -> "权限已被撤销" to
            "照片访问权限被撤销了。ScreenBox 无法读取新截图，已有索引仍可浏览。请重新授权。"

        MediaPermissionState.UNAVAILABLE -> "无法获取照片权限" to
            "当前系统环境不允许访问照片。如果你使用了工作资料或受限模式，请联系管理员。"
    }
}