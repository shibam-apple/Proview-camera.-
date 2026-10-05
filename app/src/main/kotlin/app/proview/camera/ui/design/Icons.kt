package app.proview.camera.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/**
 * Draws an SVG path from the design (24-unit viewBox unless [viewBox] says otherwise),
 * stroked like the design's inline icons.
 */
@Composable
fun SvgIcon(
    d: String,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 1.9f,
    viewBox: Float = 24f,
    filled: Boolean = false,
) {
    val path = remember(d) { PathParser().parsePathString(d).toPath() }
    Canvas(modifier.size(size)) {
        val k = this.size.width / viewBox
        scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(
                path,
                color,
                style = if (filled) Fill else Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** Icon paths copied from the design files. */
object IconPaths {
    const val GRID = "M9 3v18M15 3v18M3 9h18M3 15h18"
    const val HISTO = "M4 20V12M9 20V6M14 20V9M19 20V14"
    const val LOCK = "M5 11h14v9H5zM8 11V8a4 4 0 0 1 8 0v3"
    const val SPARKLE = "M12 2l1.9 6.1L20 10l-6.1 1.9L12 18l-1.9-6.1L4 10l6.1-1.9z"
    const val BACK = "M15 5l-7 7 7 7"
    const val SHARE_ARROW = "M12 15V4M8 8l4-4 4 4"
    const val SHARE_TRAY = "M5 12v7h14v-7"
    const val HEART = "M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.5A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"
    const val SLIDERS = "M4 7h10M18 7h2M4 17h2M10 17h10"
    const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
    const val FOCUS_BRACKETS = "M4 18V4h14M46 4h14v14M60 46v14H46M18 60H4V46"
    const val LOCK_SMALL = "M8 11V8a4 4 0 0 1 8 0v3"
}
