package app.proview.camera.ui.camera

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.proview.camera.capture.ProCamera
import app.proview.camera.ui.design.Haptics
import app.proview.camera.ui.design.IconPaths
import app.proview.camera.ui.design.LocalDesign
import app.proview.camera.ui.design.Palette
import app.proview.camera.ui.design.SvgIcon
import app.proview.camera.ui.design.Type
import app.proview.camera.ui.design.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Overlays the viewfinder shows; everything is driven by CameraScreen state. */
class ViewfinderState {
    var previewView by mutableStateOf<PreviewView?>(null)
    var touching by mutableStateOf(false)
    var focusAt by mutableStateOf<Offset?>(null)
    var focusKey by mutableStateOf(0)
    var lockAt by mutableStateOf<Offset?>(null)
    var blinkKey by mutableStateOf(0)
}

/**
 * The rounded live view with its overlays (UI_SPEC §2, item 3). [heightDesign] is animated by
 * the caller: 592 in Auto, 350 with the pro panel.
 */
@Composable
fun Viewfinder(
    camera: ProCamera,
    state: ViewfinderState,
    locked: Boolean,
    gridOn: Boolean,
    onTapFocus: (Offset) -> Unit,
    onHoldLock: (Offset) -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    /** Sideways swipe: +1 = next look, -1 = previous (UI_SPEC §2, Gestures). */
    onSwipeLook: (Int) -> Unit = {},
    overlay: @Composable () -> Unit,
) {
    val ds = LocalDesign.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val shape = RoundedCornerShape(ds.d(36))
    val lockedNow by rememberUpdatedState(locked)
    val tapFocus by rememberUpdatedState(onTapFocus)
    val holdLock by rememberUpdatedState(onHoldLock)
    val unlock by rememberUpdatedState(onUnlock)
    val swipeLook by rememberUpdatedState(onSwipeLook)
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { ds.d(50).toPx() }

    DisposableEffect(Unit) {
        onDispose { camera.unbind() }
    }

    Box(
        modifier
            .clip(shape)
            .background(Color.Black)
            .border(ds.d(0.5f), Color(0x24FFFFFF), shape),
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // TextureView-backed, so the rounded clip applies to the video too.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    state.previewView = this
                    camera.bind(lifecycleOwner, this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Rule-of-thirds grid: while touching, or when Grid is on.
        if (gridOn || state.touching) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Color(0x57FFFFFF)
                for (k in 1..2) {
                    val x = size.width * k / 3
                    val y = size.height * k / 3
                    drawLine(c, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    drawLine(c, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
            }
        }

        // Gestures: tap to focus, hold 480 ms to lock AE/AF, tap again to unlock, swipe for looks.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var dx = 0f
                    androidx.compose.foundation.gestures.detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onHorizontalDrag = { change, d -> change.consume(); dx += d },
                        onDragEnd = {
                            if (kotlin.math.abs(dx) >= swipeThreshold) {
                                Haptics.tick(view)
                                swipeLook(if (dx < 0) 1 else -1)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            state.touching = true
                            tryAwaitRelease()
                            state.touching = false
                        },
                        onTap = { o ->
                            Haptics.tap(view)
                            if (lockedNow) unlock() else tapFocus(o)
                        },
                        onLongPress = { o ->
                            Haptics.heavy(view)
                            holdLock(o)
                        },
                    )
                },
        )

        FocusBracket(state)
        if (locked) LockMarker(state.lockAt, onUnlock)

        overlay()

        Blink(state.blinkKey)
    }
}

@Composable
private fun FocusBracket(state: ViewfinderState) {
    val ds = LocalDesign.current
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val accent = remember { Animatable(0f) }
    LaunchedEffect(state.focusKey) {
        if (state.focusKey == 0) return@LaunchedEffect
        launch { scale.snapTo(1.7f); scale.animateTo(1f, tween(290)) }
        launch { accent.snapTo(0f); delay(900); accent.animateTo(1f, tween(180)) }
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(290))
        delay(1200)
        alpha.animateTo(0f, tween(330))
    }
    val at = state.focusAt ?: return
    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { ds.d(64).toPx() }
    val color = androidx.compose.ui.graphics.lerp(Palette.Text1, Palette.Accent, accent.value)
    SvgIcon(
        d = IconPaths.FOCUS_BRACKETS,
        size = ds.d(64),
        color = color,
        viewBox = 64f,
        strokeWidth = 1.5f,
        modifier = Modifier
            .offset { IntOffset((at.x - sizePx / 2).roundToInt(), (at.y - sizePx / 2).roundToInt()) }
            .scale(scale.value)
            .alpha(alpha.value),
    )
}

@Composable
private fun LockMarker(at: Offset?, onUnlock: () -> Unit) {
    val ds = LocalDesign.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pos = at ?: return
    val box = with(density) { ds.d(72).toPx() }
    val appear = remember { Animatable(1.7f) }
    LaunchedEffect(at) { appear.snapTo(1.7f); appear.animateTo(1f, tween(450)) }
    Box(
        Modifier
            .offset { IntOffset((pos.x - box / 2).roundToInt(), (pos.y - box / 2).roundToInt()) }
            .size(ds.d(72))
            .scale(appear.value)
            .border(ds.d(2), Palette.Accent, RoundedCornerShape(ds.d(22))),
        contentAlignment = Alignment.Center,
    ) {
        SvgIcon(IconPaths.LOCK, ds.d(20), Palette.Accent, strokeWidth = 2.2f)
    }
    val tagW = with(density) { ds.d(104).toPx() }
    androidx.compose.material3.TextButton(
        onClick = onUnlock,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier
            .offset { IntOffset((pos.x - tagW / 2).roundToInt(), (pos.y + box / 2 + with(density) { ds.d(6).toPx() }).roundToInt()) }
            .width(ds.d(104))
            .height(ds.d(28))
            .glass(RoundedCornerShape(ds.d(14))),
    ) {
        Text("AE · AF LOCK", style = ds.text(Type.Over), color = Palette.Accent, textAlign = TextAlign.Center)
    }
}

/** The black blink on capture: .22 s, peaking at 92% opacity. */
@Composable
private fun Blink(key: Int) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(key) {
        if (key == 0) return@LaunchedEffect
        a.snapTo(0f)
        a.animateTo(0.92f, tween(60))
        a.animateTo(0f, tween(160))
    }
    if (a.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = a.value)))
}

/** Auto mode's bottom row: scene chip and remaining-shots counter. */
@Composable
fun AutoInfoRow(scene: String, remaining: Int, modifier: Modifier = Modifier) {
    val ds = LocalDesign.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .height(ds.d(30))
                .glass(RoundedCornerShape(ds.d(15)))
                .padding(start = ds.d(11), end = ds.d(13)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ds.d(8)),
        ) {
            Box(Modifier.size(ds.d(6)).background(Color(0xB3F5F5F7), CircleShape))
            Text(scene, style = ds.text(Type.Cap), color = Palette.Text1)
        }
        Spacer(Modifier.weight(1f))
        Text("[ ${remaining.toString().padStart(3, '0')} ]", style = ds.text(Type.Cap), color = Palette.Text1)
    }
}

/** Pro modes: 16-bar luminance histogram in a glass box (104 x 50). */
@Composable
fun HistogramBox(bins: FloatArray, modifier: Modifier = Modifier) {
    val ds = LocalDesign.current
    Box(
        modifier
            .glass(RoundedCornerShape(ds.d(14)))
            .padding(horizontal = ds.d(8), vertical = ds.d(7)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val n = bins.size
            val gap = size.width * (1.9f / 88f)
            val barW = (size.width - gap * (n - 1)) / n
            bins.forEachIndexed { i, v ->
                val h = maxOf(size.height * 2f / 36f, v * size.height)
                drawRoundRect(
                    color = Color(0xD1F5F5F7),
                    topLeft = Offset(i * (barW + gap), size.height - h),
                    size = androidx.compose.ui.geometry.Size(barW, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 3.6f),
                )
            }
        }
    }
}

/** The AI suggestion line at the top of the viewfinder. */
@Composable
fun SuggestionLine(text: String, applied: Boolean, onTap: (() -> Unit)?, modifier: Modifier = Modifier) {
    val ds = LocalDesign.current
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SvgIcon(IconPaths.SPARKLE, ds.d(13), Palette.Accent, filled = true)
            Spacer(Modifier.width(ds.d(7)))
            Text(
                if (applied) "Applied" else text,
                style = ds.text(Type.Body),
                color = if (applied) Palette.Text2 else Palette.Text1,
            )
        }
    }
    if (onTap != null) {
        androidx.compose.material3.TextButton(onClick = onTap, modifier = modifier) { content() }
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { content() }
    }
}

/** Short glass toast under the suggestion line. */
@Composable
fun Toast(text: String?, key: Int, modifier: Modifier = Modifier) {
    val ds = LocalDesign.current
    val a = remember { Animatable(0f) }
    LaunchedEffect(key) {
        if (key == 0) return@LaunchedEffect
        a.snapTo(0f)
        a.animateTo(1f, tween(170))
        delay(880)
        a.animateTo(0f, tween(350))
    }
    if (text != null && a.value > 0f) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .alpha(a.value)
                    .height(ds.d(30))
                    .glass(RoundedCornerShape(ds.d(15)))
                    .padding(horizontal = ds.d(14)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = ds.text(Type.Body), color = Palette.Text1)
            }
        }
    }
}
