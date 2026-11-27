package io.github.wlmosv.screenbox.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.wlmosv.screenbox.core.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 调试用广播接收器：不占用屏幕，通过 adb 触发 OCR。
 *
 * adb shell am broadcast -a io.github.wlmosv.screenbox.BENCH_OCR \
 *   -n io.github.wlmosv.screenbox/.bench.BenchReceiver
 *
 * 只在 debug 变体存在。
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context.applicationContext)
        val count = intent.getIntExtra("count", 120)

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                Graph.ocrRepo.run(maxCount = count)
            }
        }
    }
}

/** 触发指纹计算。 */
class FpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context.applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Graph.scanEngine.runFingerprint() }
        }
    }
}

/** 触发扫描（不占屏幕）。 */
class ScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context.applicationContext)
        val full = intent.getBooleanExtra("full", false)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                Graph.scanEngine.scan(full)
                Graph.duplicateRepo.rebuild()
                Graph.suggestionRepo.rebuild()
            }
        }
    }
}
