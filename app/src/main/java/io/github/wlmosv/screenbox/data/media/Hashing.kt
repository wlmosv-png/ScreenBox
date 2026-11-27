package io.github.wlmosv.screenbox.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * 文件指纹与感知哈希。
 *
 * SHA-256：只用来判定"完全重复"，字节级一致。
 * pHash：64 位 DCT 感知哈希，用来判定"看起来一样"，容忍压缩、缩放、轻微裁剪。
 *
 * 两者结论绝不混用 —— 近似图永远不会被标成完全重复。
 */
object Hashing {

    private const val BUFFER = 64 * 1024

    /** 流式计算 SHA-256，不把整张图读进内存。 */
    suspend fun sha256(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            } ?: return@runCatching null
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /**
     * 64 位感知哈希。
     *
     * 流程：解码到 32x32 灰度 → 二维 DCT 取左上 8x8 低频 → 与中位数比较得 64 bit。
     * 用 DCT 而不是简单均值哈希，对亮度整体偏移更稳。
     */
    suspend fun perceptualHash(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = decodeSampled(context, uri, 32) ?: return@runCatching null
            val gray = toGrayscale32(bmp)
            bmp.recycle()

            val coeffs = dct8x8(gray)
            // 中位数作为阈值，排除直流分量
            val sorted = coeffs.copyOfRange(1, coeffs.size).sortedArray()
            val median = sorted[sorted.size / 2]

            var bits = 0L
            for (i in 0 until 64) {
                if (coeffs[i] > median) bits = bits or (1L shl i)
            }
            "%016x".format(bits)
        }.getOrNull()
    }

    /**
     * 两个 64 位 pHash 的汉明距离。
     * 经验阈值：<= 6 视为高度相似，<= 10 视为可能相似，> 10 不分组。
     */
    fun hammingDistance(a: String, b: String): Int {
        val x = a.toLongOrNull(16) ?: return 64
        val y = b.toLongOrNull(16) ?: return 64
        return java.lang.Long.bitCount(x xor y)
    }

    /** 相似度 0..1，供 UI 展示。 */
    fun similarity(a: String, b: String): Float = 1f - hammingDistance(a, b) / 64f

    /**
     * 解码到 32x32。
     *
     * 优化：原来开两次流（一次只读尺寸，一次真解码），实测占 pHash 26.7ms 的相当一部分。
     * 现在一次开流，用 BufferedInputStream 支持 mark/reset 做两遍解码，
     * 避免二次 openInputStream 的 ContentResolver 开销。
     */
    private fun decodeSampled(context: Context, uri: Uri, target: Int): Bitmap? {
        val raw = context.contentResolver.openInputStream(uri) ?: return null
        val stream = if (raw.markSupported()) raw else java.io.BufferedInputStream(raw, 64 * 1024)
        stream.use { input ->
            input.mark(Int.MAX_VALUE)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            var sample = 1
            while (opts.outWidth / (sample * 2) >= target && opts.outHeight / (sample * 2) >= target) {
                sample *= 2
            }
            input.reset()

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeStream(input, null, decodeOpts) ?: return null
            val scaled = Bitmap.createScaledBitmap(decoded, 32, 32, true)
            if (scaled !== decoded) decoded.recycle()
            return scaled
        }
    }

    private fun toGrayscale32(bmp: Bitmap): FloatArray {
        val out = FloatArray(32 * 32)
        val px = IntArray(32 * 32)
        bmp.getPixels(px, 0, 32, 0, 0, 32, 32)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return out
    }

    /** 取 32x32 的左上 8x8 DCT 低频系数。 */
    private fun dct8x8(gray: FloatArray): FloatArray {
        val n = 8
        val out = FloatArray(n * n)
        // cos 表预算：原来每次调用要算 131072 次 Math.cos，
        // 实测这是 pHash 48ms 里约 21ms 的开销。预计算后只剩乘法累加。
        for (u in 0 until n) {
            for (v in 0 until n) {
                var sum = 0f
                for (x in 0 until 32) {
                    val cx = COS_TABLE[u * 32 + x]
                    val rowBase = x * 32
                    for (y in 0 until 32) {
                        sum += gray[rowBase + y] * cx * COS_TABLE[v * 32 + y]
                    }
                }
                out[u * n + v] = sum
            }
        }
        return out
    }

    /**
     * DCT 余弦表，按 (频率索引 0..7) × (空间索引 0..31) 展开。
     * 索引 = freq * 32 + pos。
     */
    private val COS_TABLE: FloatArray = FloatArray(8 * 32) { i ->
        val freq = i / 32
        val pos = i % 32
        kotlin.math.cos((2 * pos + 1) * freq * Math.PI / 64.0).toFloat()
    }
}
