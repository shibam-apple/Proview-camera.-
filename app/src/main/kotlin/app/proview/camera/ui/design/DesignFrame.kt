package app.proview.camera.ui.design

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The design canvas is 390 x 844; every measurement in UI_SPEC is in these units. */
const val DESIGN_W = 390f
const val DESIGN_H = 844f

/**
 * Converts design units to device units. One scale factor for the whole screen keeps every
 * position, size and font in the same proportions as the design.
 */
class DesignScale(val scale: Float, private val fontScale: Float) {
    fun d(v: Float): Dp = (v * scale).dp
    fun d(v: Int): Dp = (v * scale).dp

    /** Font size that ignores the system font scale, so text stays in proportion to the layout. */
    fun sp(v: Float): TextUnit = (v * scale / fontScale).sp

    /** Absolute placement in design units, as in the design's `position:absolute` layout. */
    fun at(x: Float, y: Float, w: Float, h: Float): Modifier =
        Modifier.offset(d(x), d(y)).size(d(w), d(h))

    /** A type role at design size, or resized to [size] design px, keeping its line-height ratio. */
    fun text(style: TextStyle, size: Float? = null): TextStyle {
        val base = size ?: style.fontSize.value
        val ratio = if (style.lineHeight.isSp && style.fontSize.value > 0f) style.lineHeight.value / style.fontSize.value else 1.2f
        return style.copy(fontSize = sp(base), lineHeight = sp(base * ratio))
    }
}

val LocalDesign = staticCompositionLocalOf { DesignScale(1f, 1f) }

/** Lays out [content] on a 390 x 844 design canvas, scaled to fit the screen and centred. */
@Composable
fun DesignFrame(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().background(Palette.Bg)) {
        val s = minOf(maxWidth.value / DESIGN_W, maxHeight.value / DESIGN_H)
        val fontScale = LocalDensity.current.fontScale
        val design = remember(s, fontScale) { DesignScale(s, fontScale) }
        CompositionLocalProvider(LocalDesign provides design) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size((DESIGN_W * s).dp, (DESIGN_H * s).dp),
                content = content,
            )
        }
    }
}

/** The warm light spill the design paints on the pro panel and focal pill. */
fun spillBrush(spill: Float): Brush = Brush.verticalGradient(
    listOf(Color(1f, 205 / 255f, 150 / 255f, spill), Color(20 / 255f, 20 / 255f, 22 / 255f, 0.46f)),
)

/** Haptics matching the design's vibrate() calls (UI_SPEC §1). */
object Haptics {
    fun tick(view: View) = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun tap(view: View) = view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    fun heavy(view: View) = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}
