package io.github.wlmosv.screenbox.core.permission

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 权限状态唯一可信源。
 *
 * ══ 为什么需要它 ══
 *
 * 旧版每个 ViewModel 各自调 MediaPermissions.check()，
 * 而且只在 init 时查一次。用户授权回来后没有任何人刷新，
 * UI 永远停在权限页 —— 这就是"点了没反应"的根因。
 *
 * ══ 设计 ══
 *
 * 1. 全局单例，所有页面共享同一个 StateFlow
 * 2. Activity onResume 时自动重查（覆盖"去设置里改完回来"的场景）
 * 3. 权限回调后主动 refresh
 * 4. 记录"是否曾经授权过"，用于区分 REVOKED 和 NOT_REQUESTED
 */
object PermissionStateHolder {

    private const val PREFS = "screenbox_permission"
    private const val KEY_HAD_PERMISSION = "had_permission"

    private val _state = MutableStateFlow(MediaPermissionState.NOT_REQUESTED)
    val state: StateFlow<MediaPermissionState> = _state.asStateFlow()

    /** 是否已经向用户请求过一次（用于区分 NOT_REQUESTED 和 PERMANENTLY_DENIED）。 */
    private var requestedOnce = false

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    /**
     * 重新检查真实权限状态。
     *
     * 每次调用都会读系统真实值，不信任何缓存。
     */
    fun refresh() {
        if (!::appContext.isInitialized) return
        val had = hadPermissionBefore()
        val next = MediaPermissions.check(appContext, had)
        if (next == MediaPermissionState.FULL || next == MediaPermissionState.PARTIAL) {
            markHadPermission()
        }
        _state.value = next
    }

    /** 权限请求回调后调用。 */
    fun onPermissionResult(activity: Activity) {
        requestedOnce = true
        val had = hadPermissionBefore()
        var next = MediaPermissions.check(appContext, had)

        // 没权限时，区分"能再问"和"只能去设置"
        if (next == MediaPermissionState.NOT_REQUESTED || next == MediaPermissionState.REVOKED) {
            val canAsk = MediaPermissions.shouldShowRationale(activity)
            next = if (canAsk || !requestedOnce) {
                MediaPermissionState.DENIED
            } else {
                // 请求过、系统不再展示 rationale、仍然没权限 → 永久拒绝
                MediaPermissionState.PERMANENTLY_DENIED
            }
        }

        if (next == MediaPermissionState.FULL || next == MediaPermissionState.PARTIAL) {
            markHadPermission()
        }
        _state.value = next
    }

    /** 挂到 Activity 生命周期，onResume 自动刷新。 */
    fun observeLifecycle(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_RESUME) {
                    refresh()
                }
            }
        })
    }

    private fun hadPermissionBefore(): Boolean =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAD_PERMISSION, false)

    private fun markHadPermission() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAD_PERMISSION, true)
            .apply()
    }
}