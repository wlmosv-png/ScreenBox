package io.github.wlmosv.screenbox.core.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * ScreenBox 视觉系统。
 *
 * 设计取向：中性底 + 单一强调色 + 大面积留白。
 * 截图本身是视觉主体，界面不许抢戏。
 * 不用渐变、不用玻璃、不用彩色堆叠。
 */

// 品牌强调色：低饱和的青蓝，安静、不像清理软件的红黄警报色。
private val Brand = Color(0xFF2F6F6B)
private val BrandDark = Color(0xFF7FC9C2)

private val LightScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3EAE7),
    onPrimaryContainer = Color(0xFF0C2B29),
    secondary = Color(0xFF4F635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E5E2),
    onSecondaryContainer = Color(0xFF101F1D),
    tertiary = Color(0xFF6B5D3E),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAF9),
    onBackground = Color(0xFF17191A),
    surface = Color(0xFFFAFAF9),
    onSurface = Color(0xFF17191A),
    surfaceVariant = Color(0xFFEEEFEE),
    onSurfaceVariant = Color(0xFF5D6160),
    surfaceContainer = Color(0xFFF3F4F3),
    surfaceContainerHigh = Color(0xFFEDEEED),
    surfaceContainerHighest = Color(0xFFE7E8E7),
    outline = Color(0xFFC6C9C8),
    outlineVariant = Color(0xFFE1E3E2),
    error = Color(0xFFA63B34),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDDDA),
    onErrorContainer = Color(0xFF3B0906),
)

private val DarkScheme = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF06201E),
    primaryContainer = Color(0xFF1B4A46),
    onPrimaryContainer = Color(0xFFD3EAE7),
    secondary = Color(0xFFB7CCC7),
    onSecondary = Color(0xFF22322F),
    secondaryContainer = Color(0xFF364744),
    onSecondaryContainer = Color(0xFFD9E5E2),
    tertiary = Color(0xFFD8C6A0),
    onTertiary = Color(0xFF3A2F14),
    background = Color(0xFF111312),
    onBackground = Color(0xFFE6E8E7),
    surface = Color(0xFF111312),
    onSurface = Color(0xFFE6E8E7),
    surfaceVariant = Color(0xFF232625),
    onSurfaceVariant = Color(0xFFA9AEAC),
    surfaceContainer = Color(0xFF191B1A),
    surfaceContainerHigh = Color(0xFF1F2221),
    surfaceContainerHighest = Color(0xFF262928),
    outline = Color(0xFF4A4F4D),
    outlineVariant = Color(0xFF2E3231),
    error = Color(0xFFF2A9A3),
    onError = Color(0xFF3B0906),
    errorContainer = Color(0xFF7A2822),
    onErrorContainer = Color(0xFFFBDDDA),
)

/**
 * 语义色：清理风险等级与保护状态专用。
 * 不进 Material colorScheme，避免污染动态取色。
 */
data class ScreenBoxColors(
    val riskLow: Color,
    val riskMedium: Color,
    val riskHigh: Color,
    val protectedMark: Color,
    val scrim: Color,
)

private val LightExtra = ScreenBoxColors(
    riskLow = Color(0xFF3F7A52),
    riskMedium = Color(0xFF8A6A21),
    riskHigh = Color(0xFF9E3B33),
    protectedMark = Color(0xFF2F6F6B),
    scrim = Color(0x99000000),
)

private val DarkExtra = ScreenBoxColors(
    riskLow = Color(0xFF8BC49C),
    riskMedium = Color(0xFFD9BE72),
    riskHigh = Color(0xFFE9A49D),
    protectedMark = Color(0xFF7FC9C2),
    scrim = Color(0xCC000000),
)

val LocalScreenBoxColors = staticCompositionLocalOf { LightExtra }

/**
 * 间距标尺。全局只用这几档，禁止随手写 dp。
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val huge = 48.dp
}

object Radius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 18.dp
    val pill = 999.dp
}

/**
 * 字体层级：只用五级。
 * 大标题压缩字距做出质感，正文放松行高保证中文可读。
 */
private val ScreenBoxTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun ScreenBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 动态取色默认关闭：品牌一致性优先，用户可在设置里打开。 */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val extra = if (darkTheme) DarkExtra else LightExtra

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalScreenBoxColors provides extra) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ScreenBoxTypography,
            content = content,
        )
    }
}
