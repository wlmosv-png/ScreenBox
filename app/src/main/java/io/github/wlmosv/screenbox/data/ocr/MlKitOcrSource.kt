package io.github.wlmosv.screenbox.data.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地 OCR。
 *
 * 引擎：ML Kit on-device 中文识别（含拉丁字母与数字）。
 * 模型随 APK 打包，首次使用不联网下载，符合"本地优先"原则。
 *
 * ── 本轮实测发现的三个问题（都已处理）──
 *
 * 1. 每张图重建 TFLite interpreter
 *    日志里 "Replacing 44 out of 46 node(s) with delegate (TfLiteXNNPackDelegate)"
 *    每识别一张就出现 4 次（3 并发时更密）。实测 540 张出现 152 次重建。
 *    这是 ML Kit 内部行为，应用层无法直接控制 interpreter 生命周期。
 *    缓解手段：让 recognizer 尽量长时间存活（不 close），
 *    并把并发压在 3 以内，避免同时重建多份模型。
 *
 * 2. Firebase 遥测在冷启动阻塞 60 秒
 *    实测日志：TransportRuntime.CctTransportBackend ... ETIMEDOUT (Connection timed out)
 *    这是 ML Kit 自带的 Firebase 分析组件在尝试上传，无网络时超时。
 *    应用层处理：manifest 里给 Firebase 组件加 network security 限制，
 *    并在 Application 里调 setAnalyticsCollectionEnabled(false)。
 *
 * 3. 温度与内存代价
 *    3 并发跑 540 张：温度 70.5 → 89.2°C，RSS 215 → 505MB。
 *    这个代价对"用户手机"不可接受，必须加温度/内存守卫（见 OcrRepository）。
 *
 * 实测限制：
 * - 长截图（高度 > 4096）需要分段，ML Kit 对超大图会降采样导致小字丢失。
 * - 极低分辨率、强噪点、旋转 90° 的图识别率明显下降。
 * - 手写体基本不可用。
 */
data class OcrResult(
    val text: String,
    val blockBoundsJson: String?,
    /** 行级坐标（R8）。比块级细，用于布局结构分析。 */
    val lineBoundsJson: String? = null,
    val isEmpty: Boolean,
)

class MlKitOcrSource(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 统计实际调用次数，便于诊断"是否真的在复用同一个 recognizer"。 */
    private val callCount = AtomicInteger(0)

    /**
     * 在途识别计数 + 关闭闸门。
     *
     * 真机实测崩溃（Android 15，07:56）：
     *   java.lang.IllegalStateException: Pipeline did not stop successfully.
     *     at ...TextRecognizerTaskWithResource.release
     *
     * 根因：任务暂停/取消时 finally 里直接 recognizer.close()，
     * 而此时并发通道（CONCURRENCY=3）里仍有 process(image) 在途。
     * ML Kit 的 pipeline 被从底层拆掉，正在跑的识别回调报错 → 进程崩溃。
     *
     * 修复：close() 先关门（closing=true，新调用直接拒绝），
     * 再等所有在途识别结束，最后才真正 close recognizer。
     */
    private val inFlight = AtomicInteger(0)
    @Volatile private var closing = false

    suspend fun recognize(uri: Uri): Result<OcrResult> = withContext(Dispatchers.IO) {
        if (closing) {
            return@withContext Result.failure(IllegalStateException("recognizer closing"))
        }
        inFlight.incrementAndGet()
        try {
            runCatching {
                callCount.incrementAndGet()
                val image = InputImage.fromFilePath(context, uri)
                val result = recognizer.process(image).await()

            val blocks = JSONArray()
            val lines = JSONArray()
            val sb = StringBuilder()
            for (block in result.textBlocks) {
                val b = block.boundingBox
                if (b != null) {
                    blocks.put(
                        JSONObject().apply {
                            put("l", b.left)
                            put("t", b.top)
                            put("r", b.right)
                            put("b", b.bottom)
                        }
                    )
                }
                // ══ R8：同时保存行级坐标 ══
                //
                // 只存块级坐标不够用：一个 block 往往是整段文字，
                // 宽度横跨全屏，左右交错（聊天气泡）这类结构特征被抹平。
                // 行级坐标才能反映真实排版：气泡行短且偏左/偏右。
                for (line in block.lines) {
                    val lb = line.boundingBox
                    if (lb != null) {
                        lines.put(
                            JSONObject().apply {
                                put("l", lb.left)
                                put("t", lb.top)
                                put("r", lb.right)
                                put("b", lb.bottom)
                            }
                        )
                    }
                }
                val blockText = block.text
                if (blockText.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(blockText)
                }
            }
            OcrResult(
                text = sb.toString(),
                blockBoundsJson = blocks.takeIf { it.length() > 0 }?.toString(),
                lineBoundsJson = lines.takeIf { it.length() > 0 }?.toString(),
                isEmpty = sb.isBlank(),
            )
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    fun processedCount(): Int = callCount.get()

    /**
     * 安全关闭：先拒绝新调用，再等所有在途识别结束（最多 5 秒），最后才 close。
     * 不在有在途识别时拆 pipeline，避免 "Pipeline did not stop successfully" 崩溃。
     *
     * 用 NonCancellable 包住等待：本方法常在 finally 里、且取消异常在传播中，
     * 普通 delay 会立刻抛 CancellationException，等不到在途识别收尾。
     */
    suspend fun close() {
        closing = true
        withContext(NonCancellable) {
            val deadline = System.currentTimeMillis() + 5_000
            while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
                delay(50)
            }
            if (inFlight.get() > 0) {
                // 仍有在途：放弃关闭，让 recognizer 继续存活（比崩溃安全）
                closing = false
                return@withContext
            }
            runCatching { recognizer.close() }
        }
    }

    companion object {
        const val ENGINE_VERSION = "mlkit-text-chinese-16.0.0"
    }
}
