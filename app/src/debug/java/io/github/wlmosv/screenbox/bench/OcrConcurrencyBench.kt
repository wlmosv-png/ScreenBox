package io.github.wlmosv.screenbox.bench

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.media.MediaStoreSource
import io.github.wlmosv.screenbox.data.media.ScreenshotDetector
import io.github.wlmosv.screenbox.data.ocr.MlKitOcrSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OCR 并发度测试。
 *
 * 目标：用真实设备决定并发配置，而不是拍脑袋。
 * 每档并发跑同一批图，记录吞吐、内存、温度、失败率。
 */
class OcrConcurrencyBench : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        BenchAppHolder.app = application

        setContent {
            MaterialTheme {
                var log by remember { mutableStateOf("准备就绪。") }
                var running by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                Column(
                    Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text("OCR 并发基准", fontSize = 20.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            running = true
                            log = "运行中…"
                            scope.launch(Dispatchers.IO) {
                                val r = runConcurrencyBench()
                                withContext(Dispatchers.Main) { log = r; running = false }
                            }
                        },
                        enabled = !running,
                    ) { Text(if (running) "运行中…" else "开始并发测试") }
                    Spacer(Modifier.height(12.dp))
                    Text(log, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun tempC(): Double {
    var max = 0.0
    File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.forEach { z ->
        runCatching {
            val t = File(z, "temp").readText().trim().toDouble() / 1000.0
            if (t > 0 && t < 120 && t > max) max = t
        }
    }
    return max
}

private fun rssMb(): Int = runCatching {
    File("/proc/self/status").readLines()
        .first { it.startsWith("VmRSS") }
        .split(Regex("\\s+"))[1].toInt() / 1024
}.getOrDefault(0)

private suspend fun runConcurrencyBench(): String = withContext(Dispatchers.IO) {
    val sb = StringBuilder()
    val app = BenchAppHolder.app
    val media = MediaStoreSource(app)

    sb.appendLine("=== OCR 并发基准 ===")
    sb.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
    sb.appendLine("起始温度: ${"%.1f".format(tempC())}°C  RSS: ${rssMb()}MB")
    sb.appendLine()

    // 取样本：只取已识别的图（尺寸分布已在前次基准确认）
    val all = mutableListOf<io.github.wlmosv.screenbox.data.media.MediaShot>()
    media.queryAllImages(pageSize = 500) { all += it }
    val shots = all
        .filter { ScreenshotDetector.classify(it) != io.github.wlmosv.screenbox.data.db.ScreenshotConfidence.LOW }
        .sortedBy { it.fileSize }

    // 固定样本：40 张，覆盖大小分布（每档并发用同一批，保证可比）
    val sample = buildList {
        val step = (shots.size / 40).coerceAtLeast(1)
        var i = 0
        while (i < shots.size && size < 40) { add(shots[i]); i += step }
    }
    sb.appendLine("样本: ${sample.size} 张（固定，各档并发复用）")
    sb.appendLine("大小: min=${sample.minOf { it.fileSize } / 1024}KB max=${sample.maxOf { it.fileSize } / 1024}KB")
    sb.appendLine()

    // 每档并发跑同一批
    for (concurrency in listOf(1, 2, 3, 4)) {
        // 每档之间留冷却
        kotlinx.coroutines.delay(3000)

        val tempBefore = tempC()
        val rssBefore = rssMb()
        val ocr = MlKitOcrSource(app)

        val t0 = System.nanoTime()
        var failCount = 0
        var charTotal = 0
        val perImageMs = mutableListOf<Double>()

        coroutineScope {
            sample.chunked(concurrency).map { chunk ->
                async(Dispatchers.Default) {
                    chunk.map { s ->
                        val ts = System.nanoTime()
                        val r = ocr.recognize(Uri.parse(s.contentUri))
                        val el = (System.nanoTime() - ts) / 1_000_000.0
                        r.fold(
                            onSuccess = { charTotal += it.text.length },
                            onFailure = { failCount++ },
                        )
                        el
                    }
                }
            }.awaitAll().forEach { perImageMs += it }
        }
        ocr.close()

        val wallMs = (System.nanoTime() - t0) / 1_000_000.0
        val throughput = sample.size / (wallMs / 1000.0)
        val tempAfter = tempC()

        sb.appendLine("--- 并发 $concurrency ---")
        sb.appendLine("总耗时: %.0fms  吞吐: %.2f 张/秒".format(wallMs, throughput))
        sb.appendLine("单张: avg=%.0fms p50=%.0f p95=%.0f".format(
            perImageMs.average(),
            perImageMs.sorted()[(perImageMs.size - 1) * 50 / 100],
            perImageMs.sorted()[(perImageMs.size - 1) * 95 / 100],
        ))
        sb.appendLine("失败: $failCount/${sample.size}  识别字符: avg=${charTotal / sample.size}")
        sb.appendLine("温度: %.1f → %.1f°C  RSS: ${rssBefore} → ${rssMb()}MB".format(tempBefore, tempAfter))
        sb.appendLine()

        Log.i("SCREENBOX_CC", "concurrency=$concurrency throughput=%.2f temp=%.1f".format(throughput, tempAfter))
    }

    val out = sb.toString()
    runCatching {
        File(app.getExternalFilesDir(null), "ocr_concurrency.txt").writeText(out)
    }
    Log.i("SCREENBOX_CC", out)
    out
}
