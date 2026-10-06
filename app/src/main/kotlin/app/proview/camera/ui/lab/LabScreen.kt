package app.proview.camera.ui.lab

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import app.proview.camera.develop.PhotoDeveloper
import app.proview.camera.develop.PhotoIo
import app.proview.camera.develop.Pixels
import app.proview.camera.ui.design.DesignFrame
import app.proview.camera.ui.design.Haptics
import app.proview.camera.ui.design.IconPaths
import app.proview.camera.ui.design.LocalDesign
import app.proview.camera.ui.design.Palette
import app.proview.camera.ui.design.SvgIcon
import app.proview.camera.ui.design.Type
import app.proview.camera.ui.design.glass
import app.proview.pipeline.enhance.EnhanceMetrics
import app.proview.pipeline.enhance.EnhanceParams
import app.proview.pipeline.enhance.SingleImageEnhancer
import app.proview.pipeline.look.Look
import app.proview.pipeline.look.Lut3d
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Lab accent, after the VWFNDR reference: a lime on black. */
private val Lime = Color(0xFFC8E24C)
private val OnLime = Color(0xFF0E1104)

/** Long edge of the interactive preview; the saved photo is processed at up to [SAVE_DIM]. */
private const val PREVIEW_DIM = 1600
private const val SAVE_DIM = 4096

private enum class Knob(val label: String) { DENOISE("DENOISE"), TONE("TONE"), DETAIL("DETAIL") }

/**
 * Test mode: run the single-image enhancement (docs/ALGORITHM.md) on any photo, compare before
 * and after with a split slider, push each stage's strength, and read the measured effect.
 */
@Composable
fun LabScreen(initial: Uri?, onBack: () -> Unit, onToast: (String) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var uri by remember { mutableStateOf(initial) }
    var source by remember { mutableStateOf<Pixels?>(null) }
    var before by remember { mutableStateOf<ImageBitmap?>(null) }
    var after by remember { mutableStateOf<ImageBitmap?>(null) }
    var metrics by remember { mutableStateOf<EnhanceMetrics?>(null) }
    var params by remember { mutableStateOf(EnhanceParams()) }
    var working by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var split by remember { mutableFloatStateOf(0.5f) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) uri = picked
    }

    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        error = null
        after = null
        metrics = null
        val loaded = withContext(Dispatchers.IO) { runCatching { PhotoIo.load(context, u, PREVIEW_DIM) } }
        loaded.onSuccess { px ->
            source = px
            before = Bitmap.createBitmap(px.argb, px.width, px.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.onFailure { error = "Couldn't open that image" }
    }

    // Re-run on every change, debounced so dragging a control stays smooth.
    LaunchedEffect(source, params) {
        val px = source ?: return@LaunchedEffect
        delay(120)
        working = true
        val result = withContext(Dispatchers.Default) {
            runCatching { SingleImageEnhancer.enhance(px.argb.copyOf(), px.width, px.height, params, Lut3d.bake(params.look)) }
        }
        result.onSuccess { (out, m) ->
            after = Bitmap.createBitmap(out, px.width, px.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            metrics = m
        }.onFailure { error = "Enhance failed: ${it.message}" }
        working = false
    }

    fun save() {
        val u = uri ?: return
        if (saving) return
        saving = true
        Haptics.heavy(view)
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val full = PhotoIo.load(context, u, SAVE_DIM)
                    val (out, _) = SingleImageEnhancer.enhance(full.argb, full.width, full.height, params, Lut3d.bake(params.look))
                    PhotoIo.saveJpeg(context, PhotoDeveloper.encode(out, full.width, full.height), "_LAB")
                }
            }
            saving = false
            onToast(if (result.isSuccess) "Saved to Pictures/Proview" else "Save failed: ${result.exceptionOrNull()?.message}")
        }
    }

    DesignFrame {
        val ds = LocalDesign.current
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = ds.d(12), end = ds.d(12), top = ds.d(54), bottom = ds.d(30)),
            verticalArrangement = Arrangement.spacedBy(ds.d(12)),
        ) {
            // Header.
            Row(Modifier.fillMaxWidth().height(ds.d(44)), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(ds.d(44))
                        .glass(CircleShape)
                        .pointerInput(Unit) { detectTapGestures(onTap = { Haptics.tap(view); onBack() }) },
                    contentAlignment = Alignment.Center,
                ) { SvgIcon(IconPaths.BACK, ds.d(22), Palette.Text1, strokeWidth = 2.4f) }
                Spacer(Modifier.width(ds.d(14)))
                Column(Modifier.weight(1f)) {
                    Text("PROVIEW · TEST MODE", style = ds.text(Type.Over), color = Lime)
                    Spacer(Modifier.height(ds.d(5)))
                    Text("Lab", style = ds.text(Type.Header, 28f), color = Palette.Text1)
                }
                Box(
                    Modifier
                        .height(ds.d(36))
                        .clip(RoundedCornerShape(ds.d(18)))
                        .border(ds.d(1), Lime, RoundedCornerShape(ds.d(18)))
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                Haptics.tap(view)
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                        }
                        .padding(horizontal = ds.d(16)),
                    contentAlignment = Alignment.Center,
                ) { Text("PICK PHOTO", style = ds.text(Type.Over), color = Lime) }
            }

            // Before / after.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(ds.d(24)))
                    .background(Color(0xFF111113))
                    .border(ds.d(1), Palette.Line, RoundedCornerShape(ds.d(24))),
                contentAlignment = Alignment.Center,
            ) {
                val b = before
                if (b == null) {
                    Text(
                        error ?: "Pick any photo to see what the Proview pipeline does to it.",
                        style = ds.text(Type.Body),
                        color = Palette.Text2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(ds.d(32)),
                    )
                } else {
                    Compare(b, after, split, onSplit = { split = it })
                    if (working || saving) {
                        Row(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = ds.d(14))
                                .height(ds.d(28))
                                .glass(RoundedCornerShape(ds.d(14)))
                                .padding(horizontal = ds.d(12)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ds.d(7)),
                        ) {
                            CircularProgressIndicator(Modifier.size(ds.d(11)), color = Lime, strokeWidth = ds.d(1.5f))
                            Text(if (saving) "Saving full resolution…" else "Processing…", style = ds.text(Type.Cap), color = Palette.Text1)
                        }
                    }
                }
            }

            // Control and readout grid, hairlines like the reference.
            val m = metrics
            Grid(
                rows = listOf(
                    listOf(
                        @Composable { KnobCell(Knob.DENOISE, params.denoise) { params = params.copy(denoise = it) } },
                        @Composable { KnobCell(Knob.TONE, params.tone) { params = params.copy(tone = it) } },
                        @Composable { KnobCell(Knob.DETAIL, params.detail) { params = params.copy(detail = it) } },
                    ),
                    listOf(
                        @Composable {
                            Cell("LOOK", params.look.label, highlight = true, onTap = {
                                val looks = Look.entries
                                params = params.copy(look = looks[(params.look.ordinal + 1) % looks.size])
                            })
                        },
                        @Composable { Cell("NOISE", m?.let { pct(-it.noiseReduction) } ?: "—") },
                        @Composable { Cell("SHADOWS", m?.let { shadowText(it) } ?: "—") },
                    ),
                    listOf(
                        @Composable { Cell("CONTRAST", m?.let { pct(it.localContrastAfter / it.localContrastBefore.coerceAtLeast(1e-6f) - 1f) } ?: "—") },
                        @Composable { Cell("CLIPPED", m?.let { "%.1f%%".format(it.clippedAfter * 100f) } ?: "—") },
                        @Composable { Cell("TIME", m?.let { "%.1f s".format(it.millis / 1000f) } ?: "—") },
                    ),
                ),
            )

            // Save button: the reference's big lime disc.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Drag the line to compare. Drag a control sideways to change it; tap LOOK to cycle.",
                    style = ds.text(Type.Cap).copy(lineHeight = ds.sp(15f)),
                    color = Palette.Text2,
                    modifier = Modifier.weight(1f).padding(end = ds.d(12)),
                )
                Box(
                    Modifier
                        .size(ds.d(76))
                        .clip(CircleShape)
                        .background(if (before != null) Lime else Lime.copy(alpha = 0.3f))
                        .pointerInput(Unit) { detectTapGestures(onTap = { save() }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("SAVE", style = ds.text(Type.Leg), color = OnLime)
                }
            }
        }
    }
}

@Composable
private fun Compare(before: ImageBitmap, after: ImageBitmap?, split: Float, onSplit: (Float) -> Unit) {
    val ds = LocalDesign.current
    val set by rememberUpdatedState(onSplit)
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    set((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { o -> set((o.x / size.width).coerceIn(0f, 1f)) }) },
    ) {
        Image(before, contentDescription = "Before", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        if (after != null) {
            Image(
                after,
                contentDescription = "After",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(left = size.width * split) { this@drawWithContent.drawContent() }
                    },
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * split
            drawLine(Lime, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f * density)
            drawCircle(Lime, radius = 14f * density, center = Offset(x, size.height / 2))
            drawCircle(OnLime, radius = 4f * density, center = Offset(x, size.height / 2))
        }
        Tag("BEFORE", Modifier.align(Alignment.TopStart).padding(ds.d(12)))
        Tag("AFTER", Modifier.align(Alignment.TopEnd).padding(ds.d(12)))
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier) {
    val ds = LocalDesign.current
    Box(
        modifier
            .height(ds.d(24))
            .glass(RoundedCornerShape(ds.d(12)))
            .padding(horizontal = ds.d(10)),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = ds.text(Type.Over), color = Palette.Text1) }
}

/** A 3-column grid separated by hairlines. */
@Composable
private fun Grid(rows: List<List<@Composable () -> Unit>>) {
    val ds = LocalDesign.current
    Column(
        Modifier
            .fillMaxWidth()
            .border(ds.d(1), Palette.Line, RoundedCornerShape(ds.d(20)))
            .clip(RoundedCornerShape(ds.d(20))),
    ) {
        rows.forEachIndexed { r, row ->
            if (r > 0) Box(Modifier.fillMaxWidth().height(ds.d(1)).background(Palette.Line))
            Row(Modifier.fillMaxWidth().height(ds.d(70))) {
                row.forEachIndexed { c, cell ->
                    if (c > 0) Box(Modifier.width(ds.d(1)).fillMaxHeight().background(Palette.Line))
                    Box(Modifier.weight(1f).fillMaxHeight()) { cell() }
                }
            }
        }
    }
}

@Composable
private fun Cell(label: String, value: String, highlight: Boolean = false, onTap: (() -> Unit)? = null) {
    val ds = LocalDesign.current
    val view = LocalView.current
    val tap by rememberUpdatedState(onTap)
    Column(
        Modifier
            .fillMaxSize()
            .then(if (onTap != null) Modifier.pointerInput(Unit) { detectTapGestures(onTap = { Haptics.tick(view); tap?.invoke() }) } else Modifier)
            .padding(start = ds.d(12), top = ds.d(10), end = ds.d(8)),
    ) {
        Text(label, style = ds.text(Type.Over, 9f), color = Palette.Text2)
        Spacer(Modifier.height(ds.d(8)))
        Text(value, style = ds.text(Type.Disp, 26f), color = if (highlight) Lime else Palette.Text1, maxLines = 1)
    }
}

/** A strength control: drag sideways anywhere on the cell, 0..100. */
@Composable
private fun KnobCell(knob: Knob, value: Float, onChange: (Float) -> Unit) {
    val ds = LocalDesign.current
    val view = LocalView.current
    val density = LocalDensity.current
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(value)
    val perPercent = with(density) { ds.d(2.4f).toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var start = 0f
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start = current; acc = 0f },
                    onHorizontalDrag = { c, dx ->
                        c.consume()
                        acc += dx
                        val v = (start + acc / perPercent / 100f).coerceIn(0f, 1f)
                        if ((v * 100).roundToInt() != (current * 100).roundToInt()) {
                            if ((v * 100).roundToInt() % 5 == 0) Haptics.tick(view)
                            change(v)
                        }
                    },
                )
            },
    ) {
        Cell(knob.label, (value * 100).roundToInt().toString(), highlight = true)
        // Thin level bar along the bottom of the cell.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = ds.d(12), end = ds.d(12), bottom = ds.d(8))
                .fillMaxWidth()
                .height(ds.d(2))
                .background(Palette.Line),
        ) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(ds.d(2)).background(Lime))
        }
    }
}

private fun pct(v: Float): String {
    val p = (v * 100).roundToInt()
    return when {
        p > 0 -> "+$p%"
        p < 0 -> "−${-p}%"
        else -> "0%"
    }
}

private fun shadowText(m: EnhanceMetrics): String =
    if (m.shadowsBefore < 0.01f) "—" else pct(m.shadowsAfter / m.shadowsBefore - 1f)
