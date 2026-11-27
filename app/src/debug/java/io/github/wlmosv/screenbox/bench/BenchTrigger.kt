package io.github.wlmosv.screenbox.bench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.wlmosv.screenbox.core.Graph

/**
 * 无界面基准触发器。
 * 打开就 enqueue 一个 OcrBenchWorker 然后立刻关掉，
 * 不占用用户屏幕，结果写文件。
 */
class BenchTrigger : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<OcrBenchWorker>().build()
        )
        finish()
    }
}
