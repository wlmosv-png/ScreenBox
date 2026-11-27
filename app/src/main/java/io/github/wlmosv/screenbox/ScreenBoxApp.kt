package io.github.wlmosv.screenbox

import android.app.Application
import io.github.wlmosv.screenbox.core.Graph

/**
 * Application。
 *
 * 存在的主要原因是关闭 ML Kit 自带的 Firebase 遥测。
 *
 * 实测证据（logcat，冷启动时）：
 *   TransportRuntime.CctTransportBackend: Caused by: android.system.ErrnoException:
 *     isConnected failed: ETIMEDOUT (Connection timed out)
 *   前后间隔正好 60 秒，期间 OCR 队列无法启动。
 *
 * ML Kit 附带的 Firebase 分析组件在没有 Google 服务的环境下
 * 会尝试上传并超时，把冷启动路径拖慢 60 秒。
 *
 * 处理：用纯反射禁用采集（Firebase 是 ML Kit 的传递依赖，
 * 不直接在我们的编译类路径上，所以不能静态引用）。
 * 这不影响 OCR 本身（模型是本地打包的）。
 */
class ScreenBoxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        disableFirebaseTelemetry()
        Graph.init(this)
        // 进程被系统杀掉后，WorkManager 不会自己续排 —— 由这里接手。
        io.github.wlmosv.screenbox.worker.TaskRecovery.resumeInterrupted(this)
    }

    private fun disableFirebaseTelemetry() {
        // FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(false)
        runCatching {
            val analyticsClass = Class.forName("com.google.firebase.analytics.FirebaseAnalytics")
            val getInstance = analyticsClass.getMethod("getInstance", android.content.Context::class.java)
            val instance = getInstance.invoke(null, this)
            val setEnabled = analyticsClass.getMethod(
                "setAnalyticsCollectionEnabled",
                java.lang.Boolean.TYPE,
            )
            setEnabled.invoke(instance, false)
        }

        // FirebaseApp.setAutomaticResourceManagementEnabled(false)
        runCatching {
            val appClass = Class.forName("com.google.firebase.FirebaseApp")
            val getInstance = appClass.getMethod("getInstance")
            val appInstance = getInstance.invoke(null)
            val setAuto = appClass.getMethod(
                "setAutomaticResourceManagementEnabled",
                java.lang.Boolean.TYPE,
            )
            setAuto.invoke(appInstance, false)
        }
    }
}
