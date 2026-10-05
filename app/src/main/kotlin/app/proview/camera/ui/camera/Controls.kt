package app.proview.camera.ui.camera

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import app.proview.camera.capture.Mode
import app.proview.camera.ui.design.Haptics
import app.proview.camera.ui.design.LocalDesign
import app.proview.camera.ui.design.Palette
import app.proview.camera.ui.design.SvgIcon
import app.proview.camera.ui.design.Type
import app.proview.camera.ui.design.glass
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Mode dial: Auto · P · A · S · M, a draggable white thumb that detents with a tick.
// ---------------------------------------------------------------------------------------------

private val DIAL_OFFSETS = floatArrayOf(3f, 79f, 145f, 211f, 277f)
private val DIAL_WIDTHS = floatArrayOf(76f, 66f, 66f, 66f, 66f)

private fun dialIndexAt(x: Float): Int {
    var b = 0
    for (k in DIAL_OFFSETS.indices) if (x >= DIAL_OFFSETS[k]) b = k
    return b
}

@Composable
fun ModeDial(mode: Mode, onMode: (Mode) -> Unit, modifier: Modifier = Modifier) {
    val ds = LocalDesign.current
    val view = LocalView.current
    val density = LocalDensity.current
    val unit = with(density) { ds.d(1f).toPx() }
    val index = mode.ordinal
    var dragX by remember { mutableStateOf<Float?>(null) }
    val currentMode by rememberUpdatedState(mode)
    val setMode by rememberUpdatedState(onMode)

    fun pick(xDesign: Float) {
        val n = dialIndexAt(xDesign)
        if (n != currentMode.ordinal) {
            Haptics.tick(view)
            setMode(Mode.entries[n])
        }
    }

    val w = DIAL_WIDTHS[index]
    val targetLeft = dragX?.let { (it - w / 2).coerceIn(3f, 343f - w) } ?: DIAL_OFFSETS[index]
    val left by animateFloatAsState(
        targetLeft,
        if (dragX != null) spring(stiffness = 2000f) else spring(dampingRatio = 0.62f, stiffness = 380f),
        label = "dialThumb",
    )
    val thumbScale by animateFloatAsState(if (dragX != null) 1.07f else 1f, label = "dialScale")

    Box(
        modifier
            .glass(RoundedCornerShape(ds.d(20)))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o -> pick(o.x / unit) })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragX = o.x / unit; pick(o.x / unit) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val x = change.position.x / unit
                        dragX = x
                        pick(x)
                    },
                    onDragEnd = { dragX = null; Haptics.tap(view) },
                    onDragCancel = { dragX = null },
                )
            },
    ) {
        Box(
            Modifier
                .offset(x = ds.d(left), y = ds.d(3))
                .size(ds.d(DIAL_WIDTHS[index]), ds.d(34))
                .scale(thumbScale)
                .background(Palette.Text1, RoundedCornerShape(ds.d(17))),
        )
        Mode.entries.forEachIndexed { k, m ->
            Box(
                Modifier
                    .offset(x = ds.d(DIAL_OFFSETS[k]))
                    .size(ds.d(DIAL_WIDTHS[k]), ds.d(40)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.dialLabel,
                    style = ds.text(Type.Leg),
                    color = if (k == index) Palette.Bg else Color(0xD9F5F5F7),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Focal rail: 56 x 200, ticks every 10 px, majors at each lens stop, fling-free snapping.
// ---------------------------------------------------------------------------------------------

/** Focal stops in 35 mm-equivalent mm: the camera's base focal, then 35/50/85 (UI_SPEC §2). */
fun focalStops(baseMm: Int): List<Int> = listOf(baseMm) + listOf(35, 50, 85).filter { it > baseMm + 2 }

/** Focal length at rail position [z] (0 = first stop), interpolating between stops. */
fun focalAt(stops: List<Int>, z: Float): Float {
    val zc = z.coerceIn(0f, (stops.size - 1).toFloat())
    val i = floor(zc).toInt().coerceAtMost(stops.size - 2).coerceAtLeast(0)
    if (stops.size == 1) return stops[0].toFloat()
    val t = zc - i
    return stops[i] + (stops[i + 1] - stops[i]) * t
}

@Composable
fun FocalRail(
    zoom: Animatable<Float, AnimationVector1D>,
    stops: List<Int>,
    onSnap: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ds = LocalDesign.current
    val view = LocalView.current
    val density = LocalDensity.current
    val drag by rememberUpdatedState(onDrag)
    val snap by rememberUpdatedState(onSnap)
    val stepPx = with(density) { ds.d(70f).toPx() }
    val unitPx = with(density) { ds.d(1f).toPx() }
    val last = (stops.size - 1).toFloat()
    var lastTick by remember { mutableStateOf(0) }
    val z = zoom.value

    Box(
        modifier
            .clip(RoundedCornerShape(ds.d(28)))
            .background(Color(0x800C0C0E))
            .pointerInput(stops) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        val nz = (zoom.value - dy / stepPx).coerceIn(0f, last)
                        val tick = (nz * 7).roundToInt()
                        if (tick != lastTick) { Haptics.tick(view); lastTick = tick }
                        drag(nz)
                    },
                    onDragEnd = { snap(zoom.value.roundToInt().toFloat()) },
                    onDragCancel = { snap(zoom.value.roundToInt().toFloat()) },
                )
            }
            .pointerInput(stops) {
                detectTapGestures(onTap = { o ->
                    val target = (zoom.value + (o.y / unitPx - 100f) / 70f).roundToInt().coerceIn(0, last.toInt())
                    snap(target.toFloat())
                })
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / 56f
            for (i in -14..35) {
                val y = 100f - z * 70f + i * 10f
                if (y < -6f || y > 206f) continue
                val inRange = i >= 0 && i <= (last * 7).toInt()
                val major = i % 7 == 0 && inRange
                val dist = abs(y - 100f)
                val w = minOf(30f, (if (major) 18f else 10f) + (10f - dist / 6f).coerceIn(0f, 10f))
                // Fade towards the ends, like the design's mask.
                val fade = when {
                    y < 52f -> (y / 52f).coerceIn(0f, 1f)
                    y > 148f -> ((200f - y) / 52f).coerceIn(0f, 1f)
                    else -> 1f
                }
                val c = when {
                    dist < 4f && inRange -> Palette.Accent
                    !inRange -> Color(0x29F5F5F7)
                    major -> Color(0xE6F5F5F7)
                    else -> Color(0x6BF5F5F7)
                }
                drawLine(
                    c.copy(alpha = c.alpha * fade),
                    Offset((56f - 9f - w) * k, y * k),
                    Offset((56f - 9f) * k, y * k),
                    strokeWidth = 1.5f * k,
                )
            }
            val notch = Path().apply {
                moveTo(0f, 94f * k); lineTo(8f * k, 100f * k); lineTo(0f, 106f * k); close()
            }
            drawPath(notch, Palette.Accent)
        }
        stops.forEachIndexed { i, mm ->
            val y = 100f - z * 70f + i * 70f
            if (y in -6f..206f) {
                val near = abs(y - 100f) < 18f
                Text(
                    mm.toString(),
                    style = ds.text(Type.Cap),
                    color = if (near) Palette.Text1 else Color(0x80F5F5F7),
                    modifier = Modifier.offset(x = ds.d(9), y = ds.d(y - 6f)),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom row: library thumbnail, rotating tick ring, shutter, shortcut pills.
// ---------------------------------------------------------------------------------------------

@Composable
fun LibraryThumb(uri: Uri?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val ds = LocalDesign.current
    val shape = RoundedCornerShape(ds.d(16))
    val pop = remember { Animatable(1f) }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        pop.snapTo(0.7f)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
    }
    Box(
        modifier
            .scale(pop.value)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF2A2840), 0.52f to Color(0xFFC9784F),
                    0.52f to Color(0xFF241E33), 1f to Color(0xFF0D0B12),
                ),
            )
            .border(ds.d(0.5f), Color(0x4DFFFFFF), shape)
            .pointerInput(onOpen) { detectTapGestures(onTap = { onOpen() }) },
    ) {
        if (uri != null) {
            AsyncImage(model = uri, contentDescription = "Open library", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Dotted ring around the shutter; turns 30° per shot and 24° per mode change. */
@Composable
fun TickRing(rotation: Float, modifier: Modifier = Modifier) {
    val r by animateFloatAsState(rotation, spring(dampingRatio = 0.55f, stiffness = 200f), label = "ring")
    Canvas(modifier.rotate(r)) {
        val k = size.width / 100f
        drawCircle(
            Color(0x61F5F5F7),
            radius = 48f * k,
            style = Stroke(
                width = 1.2f * k,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * k, 7.55f * k)),
            ),
        )
    }
}

@Composable
fun ShutterButton(enabled: Boolean, onShoot: () -> Unit, modifier: Modifier = Modifier) {
    val ds = LocalDesign.current
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val disc by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "disc")
    val ring by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "ring")
    val shoot by rememberUpdatedState(onShoot)
    val canShoot by rememberUpdatedState(enabled)
    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                if (!canShoot) return@detectTapGestures
                pressed = true
                Haptics.tap(view)
                val released = tryAwaitRelease()
                pressed = false
                if (released) shoot()
            })
        },
    ) {
        Canvas(Modifier.fillMaxSize().scale(ring)) {
            val k = size.width / 88f
            drawCircle(Color(0xC7F5F5F7), radius = 42.5f * k, style = Stroke(width = 1.5f * k))
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(ds.d(9))
                .scale(disc)
                .background(Palette.Accent, CircleShape),
        )
    }
}

/** A shortcut function for the two reassignable pills next to the shutter. */
enum class Shortcut(val label: String, val path: String) {
    GRID("Grid", "M9 3v18M15 3v18M3 9h18M3 15h18"),
    HISTO("Histo", "M4 20V12M9 20V6M14 20V9M19 20V14"),
    LOCK("Lock", "M5 11h14v9H5zM8 11V8a4 4 0 0 1 8 0v3"),
}

@Composable
fun ShortcutPill(shortcut: Shortcut, on: Boolean, onToggle: () -> Unit, onReassign: () -> Unit, modifier: Modifier = Modifier) {
    val ds = LocalDesign.current
    val view = LocalView.current
    val flip = remember { Animatable(1f) }
    LaunchedEffect(shortcut) { flip.snapTo(0.6f); flip.animateTo(1f, tween(320)) }
    val shape = CircleShape
    val toggle by rememberUpdatedState(onToggle)
    val reassign by rememberUpdatedState(onReassign)
    Box(
        modifier
            .scale(flip.value)
            .then(if (on) Modifier.background(Palette.Accent, shape) else Modifier.glass(shape))
            .pointerInput(shortcut) {
                detectTapGestures(
                    onTap = { Haptics.tap(view); toggle() },
                    onLongPress = { Haptics.heavy(view); reassign() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        SvgIcon(shortcut.path, ds.d(20), if (on) Palette.OnAccent else Palette.Text1)
    }
}
