package io.github.wlmosv.screenbox.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import org.json.JSONArray

/**
 * 带 OCR 文字框叠加的图片查看器。
 *
 * ══ 为什么需要 ══
 *
 * 用户看到 OCR 文本时，往往想知道"这段文字在图上哪里"。
 * 尤其长截图、多区域布局时，纯文本列表无法定位。
 *
 * ══ 实现要点 ══
 *
 * 1. 坐标归一化：OCR 存的是原图像素坐标，需按显示尺寸等比缩放
 * 2. 必须和 ContentScale.Fit 的实际绘制区域对齐，不能简单按容器尺寸算
 * 3. 框线细、半透明，不遮挡图片主体
 * 4. 只在用户开启"显示文字位置"时绘制
 */
@Composable
fun OcrOverlayImage(
    model: Any?,
    contentDescription: String?,
    blockBoundsJson: String?,
    showBoxes: Boolean,
    modifier: Modifier = Modifier,
    /** 文字框颜色默认取主题主色，深色模式下保持对比度。 */
    boxColor: Color = MaterialTheme.colorScheme.primary,
    sourceWidth: Int,
    sourceHeight: Int,
) {
    val rects = remember(blockBoundsJson) { parseBounds(blockBoundsJson) }

    Box(modifier) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        if (showBoxes && rects.isNotEmpty() && sourceWidth > 0 && sourceHeight > 0) {
            Canvas(Modifier.fillMaxSize()) {
                // 计算 ContentScale.Fit 下的实际绘制区域
                val scale = minOf(
                    size.width / sourceWidth.toFloat(),
                    size.height / sourceHeight.toFloat(),
                )
                val drawW = sourceWidth * scale
                val drawH = sourceHeight * scale
                val offsetX = (size.width - drawW) / 2f
                val offsetY = (size.height - drawH) / 2f

                for (r in rects) {
                    val left = offsetX + r[0] * scale
                    val top = offsetY + r[1] * scale
                    val right = offsetX + r[2] * scale
                    val bottom = offsetY + r[3] * scale

                    // 框线：1.5dp 等效，半透明，不抢戏
                    drawRect(
                        color = boxColor.copy(alpha = 0.75f),
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 2f),
                    )
                    // 淡填充，帮助识别区域
                    drawRect(
                        color = boxColor.copy(alpha = 0.10f),
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                    )
                }
            }
        }
    }
}

/** 解析 [{"l":x,"t":y,"r":x2,"b":y2}, ...] 为矩形数组。 */
private fun parseBounds(json: String?): List<FloatArray> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    floatArrayOf(
                        o.optDouble("l", 0.0).toFloat(),
                        o.optDouble("t", 0.0).toFloat(),
                        o.optDouble("r", 0.0).toFloat(),
                        o.optDouble("b", 0.0).toFloat(),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}