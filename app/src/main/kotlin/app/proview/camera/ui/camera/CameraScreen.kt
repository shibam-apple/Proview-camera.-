package app.proview.camera.ui.camera

import android.net.Uri
import android.os.StatFs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.proview.camera.capture.CameraSettings
import app.proview.camera.capture.Control
import app.proview.camera.capture.Meter
import app.proview.camera.capture.Mode
import app.proview.camera.capture.ProCamera
import app.proview.camera.ui.design.DesignFrame
import app.proview.camera.ui.design.Haptics
import app.proview.camera.ui.design.LocalDesign
import app.proview.camera.ui.design.Palette
import app.proview.camera.ui.design.Type
import app.proview.camera.ui.design.glass
import app.proview.camera.ui.design.spillBrush
import app.proview.camera.night.FrameRole
import app.proview.camera.night.GyroSample
import app.proview.camera.night.MotionSensor
import app.proview.camera.night.NightDetector
import app.proview.camera.night.NightPlan
import app.proview.camera.night.NightPlanner
import app.proview.camera.night.SceneMotion
import app.proview.camera.night.Steadiness
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A photo just taken, with the sensor values it was exposed at. */
data class Shot(val uri: Uri, val iso: Int, val exposureNs: Long)

/** The camera screen from Camera.dc.html (UI_SPEC §2). */
@Composable
fun CameraScreen(
    camera: ProCamera,
    settings: CameraSettings,
    onSettings: (CameraSettings) -> Unit,
    latestPhoto: Uri?,
    onShot: (Shot) -> Unit,
    onOpenLibrary: () -> Unit,
    backgroundScope: CoroutineScope,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val live by camera.live.collectAsState()
    val histogram by camera.histogram.collectAsState()
    val facts by camera.facts.collectAsState()

    // Push settings to the sensor whenever they change.
    LaunchedEffect(settings) { camera.apply(settings) }

    val vf = remember { ViewfinderState() }
    var selected by remember { mutableStateOf(defaultControl(settings.mode)) }
    var gridOn by remember { mutableStateOf(false) }
    var histoOn by remember { mutableStateOf(true) }
    var slots by remember { mutableStateOf(listOf(Shortcut.GRID, Shortcut.HISTO)) }
    var shots by remember { mutableIntStateOf(0) }
    var modeChanges by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastKey by remember { mutableIntStateOf(0) }
    var suggestionApplied by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(remainingShots(context)) }

    // Night mode (docs/NIGHT_MODE.md): detection, steadiness, plan and capture progress.
    val motionSensor = remember { MotionSensor(context.applicationContext) }
    DisposableEffect(Unit) {
        motionSensor.start()
        onDispose { motionSensor.stop() }
    }
    val motion by motionSensor.reading.collectAsState()
    val sceneMotion by camera.sceneMotion.collectAsState()
    val detector = remember { NightDetector() }
    var nightAuto by remember { mutableStateOf(false) }
    var nightOff by remember { mutableStateOf(false) }
    var nightProgress by remember { mutableStateOf<Float?>(null) }
    var nightCountdown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(live.meter) {
        nightAuto = detector.update(live.meter, System.nanoTime())
        if (!nightAuto) nightOff = false
    }

    fun showToast(text: String) { toast = text; toastKey++ }

    // Zoom: rail position 0..n, mapped to focal length and then to a zoom ratio.
    val stops = remember(facts.baseFocalMm) { focalStops(facts.baseFocalMm) }
    val zoom = remember { Animatable(0f) }
    LaunchedEffect(zoom.value, stops) {
        camera.setZoomRatio(focalAt(stops, zoom.value) / stops.first())
    }
    val focalNow = focalAt(stops, zoom.value).roundToInt()

    val pro = settings.mode.isPro
    val vfH by animateFloatAsState(if (pro) 350f else 592f, spring(dampingRatio = 0.7f, stiffness = 260f), label = "vfH")
    val panel by animateFloatAsState(if (pro) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 300f), label = "panel")

    val plan = settings.exposurePlan(live.meter, facts.isoRange)
    val meterStops = settings.meterStops(live.meter, plan)
    val suggestion = suggestionFor(settings, live.meter)

    val nightActive = settings.mode == Mode.AUTO && nightAuto && !nightOff
    val subjectMoving = sceneMotion > SceneMotion.MOTION_THRESHOLD && motion.steadiness != Steadiness.HANDHELD
    val nightPlan = live.meter?.takeIf { nightActive }?.let {
        NightPlanner.plan(it, motion.steadiness, subjectMoving, facts.isoRange, facts.exposureRangeNs)
    }
    val capturingNight = nightProgress != null

    fun setMode(m: Mode) {
        if (m == settings.mode) return
        val next = if (m == Mode.MANUAL) settings.toManual(live.meter) else settings.copy(mode = m)
        onSettings(next.copy(aeAfLocked = false))
        selected = defaultControl(m)
        modeChanges++
        if (!settings.mode.isPro && m.isPro) Haptics.heavy(view)
    }

    fun shootNight(plan: NightPlan) {
        busy = true
        Haptics.heavy(view)
        shots++
        motionSensor.resetDrift()
        motionSensor.startRecording()
        nightProgress = 0f
        val totalNs = plan.totalNs
        val photo = plan.frames.first { it.role == FrameRole.PHOTO }
        val dir = File(context.cacheDir, "bursts/" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
        // Smooth countdown from sensor time; frame callbacks keep it honest.
        val ticker = scope.launch {
            val start = System.nanoTime()
            while (true) {
                val t = (System.nanoTime() - start).toFloat() / totalNs
                nightProgress = maxOf(nightProgress ?: 0f, t.coerceAtMost(0.98f))
                val left = ((1f - t) * totalNs / 1e9f).coerceAtLeast(0f)
                nightCountdown = if (left > 0.05f) kotlin.math.ceil(left).toInt().toString() else null
                delay(50)
            }
        }
        scope.launch {
            val result = runCatching {
                camera.captureNight(plan, dir) { done, total -> nightProgress = maxOf(nightProgress ?: 0f, done.toFloat() / total) }
            }
            ticker.cancel()
            val gyro = motionSensor.stopRecording()
            nightProgress = null
            nightCountdown = null
            vf.blinkKey++
            result.onSuccess { burst ->
                val jpeg = burst.photoJpeg
                if (jpeg != null) {
                    runCatching { camera.saveJpeg(jpeg) }
                        .onSuccess { uri -> onShot(Shot(uri, photo.iso, photo.exposureNs)); remaining = remainingShots(context) }
                        .onFailure { showToast("Couldn't save the photo") }
                } else {
                    showToast("Night photo failed")
                }
                val keep = BurstPrefs.saveBursts(context)
                backgroundScope.launch(Dispatchers.IO) {
                    runCatching {
                        File(burst.dir, "gyro.json").writeText(gyroJson(gyro))
                        if (keep) exportBurst(context.applicationContext, burst.dir)
                    }
                    burst.dir.deleteRecursively()
                }
                if (keep) showToast("Burst saved · ${burst.rawFrames} RAW frames")
            }.onFailure {
                showToast("Night capture failed: ${it.message ?: it.javaClass.simpleName}")
                dir.deleteRecursively()
            }
            busy = false
        }
    }

    fun shoot() {
        if (busy) return
        nightPlan?.let { shootNight(it); return }
        busy = true
        Haptics.heavy(view)
        vf.blinkKey++
        shots++
        val iso = live.iso
        val exposure = live.exposureNs
        scope.launch {
            runCatching { camera.capture() }
                .onSuccess { uri ->
                    onShot(Shot(uri, iso, exposure))
                    remaining = remainingShots(context)
                }
                .onFailure { showToast("Couldn't save the photo") }
            busy = false
        }
    }

    DesignFrame {
        val ds = LocalDesign.current

        // Ambient light: the bottom fade and the warm glow behind the top bar.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x1A080809), 0.18f to Color(0x00080809),
                        0.5f to Color(0x1A080809), 1f to Color(0xD1080809),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(ds.d(230))
                .background(Brush.verticalGradient(listOf(Color(0x24FFBE7D), Color(0x00FFBE7D)))),
        )

        // Top bar: mode name + subtitle, focal pill.
        Row(
            Modifier.then(ds.at(22f, 48f, 352f, 40f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ds.d(1))) {
                Text(settings.mode.title, style = ds.text(Type.Ttl), color = Palette.Text1)
                if (settings.mode.subtitle.isNotEmpty()) {
                    Text(settings.mode.subtitle, style = ds.text(Type.Cap), color = Palette.Text2)
                }
            }
            Row(
                Modifier
                    .height(ds.d(40))
                    .background(spillBrush(0.13f), RoundedCornerShape(ds.d(20)))
                    .glass(RoundedCornerShape(ds.d(20)))
                    .pointerInput(stops) {
                        detectTapGestures(onTap = {
                            Haptics.tap(view)
                            val next = ((zoom.value.roundToInt() + 1) % stops.size).toFloat()
                            scope.launch { zoom.animateTo(next, tween(400)) }
                        })
                    }
                    .padding(horizontal = ds.d(18)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(focalNow.toString(), style = ds.text(Type.Val), color = Palette.Text1)
                Text(" mm", style = ds.text(Type.Cap), color = Palette.Text2)
            }
        }

        // Viewfinder.
        Viewfinder(
            camera = camera,
            state = vf,
            locked = settings.aeAfLocked,
            gridOn = gridOn,
            onTapFocus = { o ->
                vf.previewView?.let { camera.focusAt(it, o.x, o.y, hold = false) }
                vf.focusAt = o
                vf.focusKey++
            },
            onHoldLock = { o ->
                vf.previewView?.let { camera.focusAt(it, o.x, o.y, hold = true) }
                vf.lockAt = o
                if (settings.mode == Mode.MANUAL) showToast("Focus locked") else onSettings(settings.copy(aeAfLocked = true))
            },
            onUnlock = {
                camera.releaseFocus()
                vf.lockAt = null
                onSettings(settings.copy(aeAfLocked = false))
            },
            modifier = Modifier.then(ds.at(12f, 94f, 366f, vfH)),
        ) {
            if (nightPlan != null || capturingNight) {
                SuggestionLine(
                    text = if (capturingNight) "Hold still" else "Night · hold still for ${kotlin.math.ceil(nightPlan!!.totalSeconds).toInt()} s",
                    applied = false,
                    onTap = null,
                    modifier = Modifier.then(ds.at(12f, 8f, 342f, 44f)),
                )
            } else if (suggestion != null) {
                SuggestionLine(
                    text = suggestion.text,
                    applied = suggestionApplied,
                    onTap = suggestion.apply?.let { apply ->
                        {
                            Haptics.tap(view)
                            onSettings(apply(settings))
                            suggestionApplied = true
                            scope.launch { delay(1400); suggestionApplied = false }
                        }
                    },
                    modifier = Modifier.then(ds.at(12f, 8f, 342f, 44f)),
                )
            }
            Toast(toast, toastKey, Modifier.then(ds.at(0f, 56f, 366f, 30f)))

            if (capturingNight) {
                CapturingVeil()
                NightCrosshair(motion)
            }
            if (!pro && nightAuto && !capturingNight) {
                NightChip(
                    active = nightActive,
                    seconds = nightPlan?.totalSeconds ?: 0f,
                    onToggle = { nightOff = !nightOff; Haptics.tap(view) },
                    modifier = Modifier.then(ds.at(14f, vfH - 46f, 200f, 30f)),
                )
            } else if (!pro && !capturingNight) {
                AutoInfoRow(
                    scene = sceneLabel(live.meter, settings.aeAfLocked),
                    remaining = remaining,
                    modifier = Modifier.then(ds.at(14f, vfH - 46f, 338f, 32f)),
                )
            } else if (histoOn) {
                HistogramBox(histogram, Modifier.then(ds.at(14f, vfH - 64f, 104f, 50f)))
            }

            FocalRail(
                zoom = zoom,
                stops = stops,
                onDrag = { z -> scope.launch { zoom.snapTo(z) } },
                onSnap = { z -> scope.launch { zoom.animateTo(z, spring(dampingRatio = 0.6f, stiffness = 300f)); Haptics.tap(view) } },
                modifier = Modifier.then(ds.at(300f, (vfH - 200f) / 2f + 14f, 56f, 200f)),
            )
        }

        // Pro instrument panel: springs up from below in P/A/S/M.
        if (panel > 0.01f) {
            ProPanel(
                settings = settings,
                live = live,
                meterStops = meterStops,
                selected = selected,
                onSelect = { selected = it },
                onSettings = onSettings,
                onNextMode = {
                    val order = listOf(Mode.PROGRAM, Mode.APERTURE, Mode.SHUTTER, Mode.MANUAL)
                    setMode(order[(order.indexOf(settings.mode) + 1) % order.size])
                },
                gridOn = gridOn,
                onToggleGrid = { gridOn = !gridOn },
                remaining = remaining,
                modifier = Modifier
                    .then(ds.at(12f, 448f, 366f, 238f))
                    .graphicsLayer {
                        alpha = panel.coerceIn(0f, 1f)
                        val s = 0.93f + 0.07f * panel
                        scaleX = s
                        scaleY = s
                        translationY = (1f - panel) * 46f * ds.scale * density
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    },
            )
        }

        ModeDial(settings.mode, ::setMode, Modifier.then(ds.at(22f, 692f, 346f, 40f)))

        LibraryThumb(latestPhoto, onOpenLibrary, Modifier.then(ds.at(28f, 752f, 56f, 56f)))
        TickRing(shots * 30f + modeChanges * 24f, Modifier.then(ds.at(145f, 730f, 100f, 100f)))
        ShutterButton(
            enabled = !busy,
            onShoot = ::shoot,
            modifier = Modifier.then(ds.at(151f, 736f, 88f, 88f)),
            progress = nightProgress,
            label = nightCountdown,
            night = nightActive,
        )

        slots.forEachIndexed { k, sc ->
            val on = when (sc) {
                Shortcut.GRID -> gridOn
                Shortcut.HISTO -> histoOn
                Shortcut.LOCK -> settings.aeAfLocked
            }
            ShortcutPill(
                shortcut = sc,
                on = on,
                onToggle = {
                    when (sc) {
                        Shortcut.GRID -> gridOn = !gridOn
                        Shortcut.HISTO -> {
                            histoOn = !histoOn
                            if (!pro) showToast(if (histoOn) "Histogram shows in P/A/S/M" else "Histogram off")
                        }
                        Shortcut.LOCK -> {
                            if (settings.aeAfLocked) {
                                camera.releaseFocus()
                                vf.lockAt = null
                                onSettings(settings.copy(aeAfLocked = false))
                            } else {
                                vf.previewView?.let { pv ->
                                    val c = androidx.compose.ui.geometry.Offset(pv.width / 2f, pv.height / 2f)
                                    camera.focusAt(pv, c.x, c.y, hold = true)
                                    vf.lockAt = c
                                }
                                onSettings(settings.copy(aeAfLocked = true))
                            }
                        }
                    }
                },
                onReassign = {
                    val others = slots.filterIndexed { i, _ -> i != k }
                    var next = sc
                    do {
                        next = Shortcut.entries[(next.ordinal + 1) % Shortcut.entries.size]
                    } while (next in others && next != sc)
                    slots = slots.toMutableList().also { it[k] = next }
                    showToast("Shortcut · ${next.label}")
                },
                modifier = Modifier.then(ds.at(258f + k * 58f, 757f, 46f, 46f)),
            )
        }
    }
}

private fun gyroJson(samples: List<GyroSample>): String {
    val arr = org.json.JSONArray()
    samples.forEach { arr.put(org.json.JSONArray().put(it.timestampNs).put(it.x.toDouble()).put(it.y.toDouble()).put(it.z.toDouble())) }
    return org.json.JSONObject().put("format", "[timestampNs, x, y, z] rad/s").put("samples", arr).toString()
}

private class Suggestion(val text: String, val apply: ((CameraSettings) -> CameraSettings)?)

/** One quiet hint from the current metering (the design's "AI suggestion" line). */
private fun suggestionFor(s: CameraSettings, meter: Meter?): Suggestion? {
    meter ?: return null
    if (s.mode == Mode.MANUAL) return null
    return when {
        meter.iso >= 1600 -> Suggestion("Low light. Hold still for a sharper shot", null)
        meter.iso <= 100 && meter.exposureNs <= 1_000_000 && s.evThirds > -2 ->
            Suggestion("Bright scene. Tap for −0.7 EV to keep highlights") { it.copy(evThirds = -2) }
        else -> null
    }
}

private fun sceneLabel(meter: Meter?, locked: Boolean): String {
    if (locked) return "AE · AF locked"
    meter ?: return "Auto"
    return when {
        meter.iso >= 1600 -> "Auto · Low light"
        meter.exposureNs <= 1_000_000 -> "Auto · Bright"
        else -> "Auto · Daylight"
    }
}

/** Remaining 12 MP JPEGs that fit in free storage (about 4.5 MB each), capped at 999. */
private fun remainingShots(context: android.content.Context): Int {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
    return (free / 4_500_000L).coerceIn(0L, 999L).toInt()
}
