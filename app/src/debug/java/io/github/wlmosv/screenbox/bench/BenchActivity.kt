package io.github.wlmosv.screenbox.bench

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.OcrStatus
import io.github.wlmosv.screenbox.data.media.Hashing
import io.github.wlmosv.screenbox.data.media.MediaStoreSource
import io.github.wlmosv.screenbox.data.media.ScreenshotDetector
import io.github.wlmosv.screenbox.data.ocr.MlKitOcrSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 性能基准测试。
 *
 * 只在 debug 变体里存在，不进 release 包。
 * 用真实图库数据分阶段计时，输出 P50/P95/失败率/内存/温度。
 */
class BenchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        BenchAppHolder.app = application

        setContent {
            MaterialTheme {
                BenchScreen()
            }
        }
    }
}

@Composable
private fun BenchScreen() {
    var log by remember { mutableStateOf("准备就绪。点击开始基准测试。") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("ScreenBox 性能基准", fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                running = true
                log = "运行中…"
                scope.launch(Dispatchers.IO) {
                    val result = runBench()
                    withContext(Dispatchers.Main) {
                        log = result
                        running = false
                    }
                }
            },
            enabled = !running,
        ) { Text(if (running) "运行中…" else "开始基准测试") }
        Spacer(Modifier.height(12.dp))
        Text(log, fontSize = 11.sp)
    }
}

private fun now() = System.nanoTime()

private fun ms(from: Long) = (System.nanoTime() - from) / 1_000_000.0

private fun pct(xs: List<Double>, p: Double): Double {
    if (xs.isEmpty()) return 0.0
    val s = xs.sorted()
    val idx = ((s.size - 1) * p).toInt().coerceIn(0, s.lastIndex)
    return s[idx]
}

private fun stat(label: String, xs: List<Double>): String {
    if (xs.isEmpty()) return "$label: 无样本"
    val avg = xs.average()
    return "$label  n=${xs.size}  avg=%.1fms  p50=%.1f  p95=%.1f  min=%.1f  max=%.1f".format(
        avg, pct(xs, 0.50), pct(xs, 0.95), xs.min(), xs.max()
    )
}

private fun readTempC(): Double {
    // 取一组有效温度区的最大值
    val dir = File("/sys/class/thermal")
    var max = 0.0
    dir.listFiles { f -> f.name.startsWith("thermal_zone") }?.forEach { zone ->
        runCatching {
            val t = File(zone, "temp").readText().trim().toDouble() / 1000.0
            if (t > 0 && t < 120 && t > max) max = t
        }
    }
    return max
}

private fun readCpuFreqMhz(): Int = runCatching {
    File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
        .readText().trim().toInt() / 1000
}.getOrDefault(0)

private suspend fun runBench(): String = withContext(Dispatchers.IO) {
    val sb = StringBuilder()

    val app = BenchAppHolder.app
    val db = Graph.db
    val media = MediaStoreSource(app)

    sb.appendLine("=== ScreenBox 性能基准 ===")
    sb.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
    sb.appendLine("温度起始: ${"%.1f".format(readTempC())}°C  CPU: ${readCpuFreqMhz()}MHz")
    sb.appendLine()

    // ── 准备样本：200 张真实截图 ──
    sb.appendLine("--- 准备样本 ---")
    val allShots = mutableListOf<io.github.wlmosv.screenbox.data.media.MediaShot>()
    val tQuery = now()
    media.queryAllImages(pageSize = 500) { page ->
        allShots += page
    }
    val queryAllMs = ms(tQuery)
    sb.appendLine("MediaStore 全量枚举: %.0fms  共 %d 张".format(queryAllMs, allShots.size))

    val screenshots = allShots.filter {
        ScreenshotDetector.classify(it) != io.github.wlmosv.screenbox.data.db.ScreenshotConfidence.LOW
    }
    sb.appendLine("判定为截图: ${screenshots.size} 张")

    // 分层抽样 200 张：按文件大小分位取样，覆盖小图/大图/长截图
    val sorted = screenshots.sortedBy { it.fileSize }
    val sample = buildList {
        val step = (sorted.size / 200).coerceAtLeast(1)
        var i = 0
        while (i < sorted.size && size < 200) {
            add(sorted[i])
            i += step
        }
    }
    sb.appendLine("抽样: ${sample.size} 张（按文件大小分层）")
    sb.appendLine("样本大小分布: min=${sample.minOf { it.fileSize } / 1024}KB  max=${sample.maxOf { it.fileSize } / 1024}KB  avg=${sample.map { it.fileSize }.average().toLong() / 1024}KB")
    val longShots = sample.count { it.height > it.width * 2 }
    sb.appendLine("其中长截图(高>宽×2): $longShots 张")
    sb.appendLine()

    // ── A. MediaStore 分页查询 ──
    sb.appendLine("--- A. MediaStore 分页查询 ---")
    val queryTimes = mutableListOf<Double>()
    repeat(5) {
        val t = now()
        var cnt = 0
        media.queryAllImages(pageSize = 500) { page -> cnt += page.size }
        queryTimes += ms(t)
    }
    sb.appendLine(stat("全量枚举(500/页)", queryTimes))
    sb.appendLine()

    // ── B. 元数据读取（单条按 id）──
    sb.appendLine("--- B. 单条元数据读取 ---")
    val metaTimes = mutableListOf<Double>()
    val metaFail = sample.count { s ->
        val t = now()
        val r = media.findById(s.mediaStoreId)
        metaTimes += ms(t)
        r == null
    }
    sb.appendLine(stat("findById", metaTimes))
    sb.appendLine("失败: $metaFail/${sample.size}")
    sb.appendLine()

    // ── C. SHA-256 ──
    sb.appendLine("--- C. SHA-256 计算 ---")
    val shaTimes = mutableListOf<Double>()
    val shaFail = sample.count { s ->
        val t = now()
        val r = Hashing.sha256(app, Uri.parse(s.contentUri))
        shaTimes += ms(t)
        r == null
    }
    sb.appendLine(stat("sha256", shaTimes))
    sb.appendLine("失败: $shaFail/${sample.size}")
    val shaThroughput = if (shaTimes.isNotEmpty()) sample.size / (shaTimes.sum() / 1000.0) else 0.0
    sb.appendLine("吞吐: %.1f 张/秒".format(shaThroughput))
    sb.appendLine()

    // ── D. 图片解码 ──
    sb.appendLine("--- D. 图片解码 ---")
    val decodeTimes = mutableListOf<Double>()
    val decodeFail = sample.count { s ->
        val t = now()
        val bmp = runCatching {
            app.contentResolver.openInputStream(Uri.parse(s.contentUri))?.use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
        decodeTimes += ms(t)
        bmp?.recycle()
        bmp == null
    }
    sb.appendLine(stat("全尺寸解码", decodeTimes))
    sb.appendLine("失败: $decodeFail/${sample.size}")

    // 采样解码（pHash 实际用的路径）
    val sampledTimes = mutableListOf<Double>()
    sample.take(60).forEach { s ->
        val t = now()
        val bmp = runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            app.contentResolver.openInputStream(Uri.parse(s.contentUri))?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            var smp = 1
            while (opts.outWidth / (smp * 2) >= 32 && opts.outHeight / (smp * 2) >= 32) smp *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = smp }
            app.contentResolver.openInputStream(Uri.parse(s.contentUri))?.use {
                BitmapFactory.decodeStream(it, null, o2)
            }
        }.getOrNull()
        sampledTimes += ms(t)
        bmp?.recycle()
    }
    sb.appendLine(stat("采样解码(32px目标,含2次开流)", sampledTimes))
    sb.appendLine()

    // ── E. pHash ──
    sb.appendLine("--- E. pHash 计算 ---")
    val phashTimes = mutableListOf<Double>()
    val phashFail = sample.count { s ->
        val t = now()
        val r = Hashing.perceptualHash(app, Uri.parse(s.contentUri))
        phashTimes += ms(t)
        r == null
    }
    sb.appendLine(stat("perceptualHash", phashTimes))
    sb.appendLine("失败: $phashFail/${sample.size}")
    val phashThroughput = if (phashTimes.isNotEmpty()) sample.size / (phashTimes.sum() / 1000.0) else 0.0
    sb.appendLine("吞吐: %.1f 张/秒".format(phashThroughput))
    sb.appendLine()

    // ── F. ML Kit OCR ──
    sb.appendLine("--- F. ML Kit OCR ---")
    val ocr = MlKitOcrSource(app)
    val ocrTimes = mutableListOf<Double>()
    val ocrFail = mutableListOf<String>()
    val ocrChars = mutableListOf<Int>()
    // 只测 60 张，OCR 慢
    sample.take(60).forEach { s ->
        val t = now()
        val r = ocr.recognize(Uri.parse(s.contentUri))
        ocrTimes += ms(t)
        r.fold(
            onSuccess = { ocrChars += it.text.length },
            onFailure = { ocrFail += (it.message ?: "unknown").take(60) },
        )
    }
    ocr.close()
    sb.appendLine(stat("OCR 单张", ocrTimes))
    sb.appendLine("失败: ${ocrFail.size}/${ocrTimes.size}")
    if (ocrFail.isNotEmpty()) sb.appendLine("失败样例: ${ocrFail.take(3)}")
    sb.appendLine("识别字符数: avg=${ocrChars.average().toInt()}  min=${ocrChars.minOrNull()}  max=${ocrChars.maxOrNull()}")
    val ocrThroughput = if (ocrTimes.isNotEmpty()) ocrTimes.size / (ocrTimes.sum() / 1000.0) else 0.0
    sb.appendLine("吞吐: %.2f 张/秒".format(ocrThroughput))
    sb.appendLine()

    // ── G. Room 写入 ──
    sb.appendLine("--- G. Room 写入 ---")
    val ocrDao = db.ocrDao()
    val writeTimes = mutableListOf<Double>()
    val testIds = sample.take(50).mapNotNull { db.screenshotDao().byMediaStoreId(it.mediaStoreId)?.id }
    testIds.forEachIndexed { idx, id ->
        val t = now()
        ocrDao.upsert(
            io.github.wlmosv.screenbox.data.db.OcrEntity(
                screenshotId = id,
                recognizedText = "bench_$idx " + "测试文本".repeat(50),
                language = "zh+en",
                recognitionStatus = OcrStatus.SUCCESS,
                recognizedAt = System.currentTimeMillis(),
                engineVersion = "bench",
            )
        )
        writeTimes += ms(t)
    }
    sb.appendLine(stat("单条 OcrEntity upsert", writeTimes))

    val ftsTimes = mutableListOf<Double>()
    testIds.forEachIndexed { idx, id ->
        val t = now()
        ocrDao.insertFts(
            io.github.wlmosv.screenbox.data.db.OcrFtsEntity(id, "bench_$idx " + "测试文本".repeat(50))
        )
        ftsTimes += ms(t)
    }
    sb.appendLine(stat("单条 FTS 写入", ftsTimes))

    // 批量写对比
    val batchT = now()
    db.runInTransaction {
        // Room 的 runInTransaction 是阻塞式，这里仅测事务开销
    }
    sb.appendLine("空事务开销: %.2fms".format(ms(batchT)))
    sb.appendLine()

    // ── H. 搜索索引查询 ──
    sb.appendLine("--- H. 搜索查询 ---")
    val searchTimes = mutableListOf<Double>()
    repeat(10) {
        val t = now()
        ocrDao.searchSubstring("测试", 100, 0)
        searchTimes += ms(t)
    }
    sb.appendLine(stat("instr 子串搜索", searchTimes))
    sb.appendLine()

    // ── 内存与温度 ──
    sb.appendLine("--- 资源 ---")
    val rt = Runtime.getRuntime()
    sb.appendLine("Java 堆: used=${(rt.totalMemory() - rt.freeMemory()) / 1024 / 1024}MB  max=${rt.maxMemory() / 1024 / 1024}MB")
    sb.appendLine("Native 堆: ${Debug.getNativeHeapAllocatedSize() / 1024 / 1024}MB")
    sb.appendLine("温度结束: ${"%.1f".format(readTempC())}°C  CPU: ${readCpuFreqMhz()}MHz")

    val out = sb.toString()
    Log.i("SCREENBOX_BENCH", out)
    // 同时落文件，避免 logcat 环形缓冲冲掉
    runCatching {
        val f = File(app.getExternalFilesDir(null), "bench_result.txt")
        f.writeText(out)
    }
    out
}

/** 让非 Composable 代码拿到 Application。 */
object BenchAppHolder {
    lateinit var app: android.app.Application
}
