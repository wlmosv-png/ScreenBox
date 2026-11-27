package io.github.wlmosv.screenbox.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import kotlin.math.abs

/**
 * 可缩放图片查看器。
 *
 * 这是 v2 的硬功能缺失：详情页图片被 heightIn(380dp) 锁死，
 * 长截图上的文字根本看不清。
 *
 * 支持：
 * - 双指捏合缩放（1x ~ 6x）
 * - 双击放大到 2.5x / 还原
 * - 放大后单指拖动平移
 * - 边界约束：不会把图拖出屏幕
 * - 缩放时图片不重新加载（painter 状态由 Coil 缓存保持）
 * - 加载中 / 失败占位
 *
 * 关键实现细节：
 * 用 graphicsLayer 做变换，而不是改尺寸重新请求图片 ——
 * 后者会导致每次缩放都重新解码，卡顿且丢状态。
 */
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 6f,
    /** 单击回调。详情页用它进入全屏，全屏页用它退出。 */
    onClick: (() -> Unit)? = null,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    val animatedScale by animateFloatAsState(targetValue = scale, label = "zoom")

    // 把平移限制在图片可见范围内，避免拖飞
    fun clampOffset(next: Offset, s: Float): Offset {
        if (containerSize == androidx.compose.ui.unit.IntSize.Zero) return next
        val maxX = (containerSize.width * (s - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (containerSize.height * (s - 1f) / 2f).coerceAtLeast(0f)
        return Offset(
            next.x.coerceIn(-maxX, maxX),
            next.y.coerceIn(-maxY, maxY),
        )
    }

    Box(
        modifier = modifier
            // 关键：缩放后必须裁剪在容器内，否则图会穿透到下方信息区
            .clipToBounds()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(minScale, maxScale)
                    // 缩放到 1x 时自动回中
                    val nextOffset = if (nextScale <= 1.01f) {
                        Offset.Zero
                    } else {
                        clampOffset(offset + pan, nextScale)
                    }
                    scale = nextScale
                    offset = nextOffset
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick?.invoke() },
                    onDoubleTap = { tap ->
                    if (scale > 1.05f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        // 以双击点为中心放大
                        val next = 2.5f
                        val center = Offset(
                            containerSize.width / 2f,
                            containerSize.height / 2f,
                        )
                        val delta = (center - tap) * (next - 1f)
                        scale = next
                        offset = clampOffset(delta, next)
                    }
                })
            },
    ) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    translationX = offset.x
                    translationY = offset.y
                    // 记录容器尺寸用于边界约束
                    if (containerSize.width == 0 && size.width > 0) {
                        containerSize = androidx.compose.ui.unit.IntSize(
                            size.width.toInt(),
                            size.height.toInt(),
                        )
                    }
                },
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = "图片加载失败",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(36.dp),
                    )
                }
            },
        )
    }
}
