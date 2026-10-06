package app.proview.camera.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import app.proview.camera.capture.CameraSettings
import app.proview.camera.capture.Control
import app.proview.camera.capture.LiveReadout
import app.proview.camera.capture.Mode
import app.proview.camera.capture.Steps
import app.proview.camera.ui.design.Haptics
import app.proview.camera.ui.design.IconPaths
import app.proview.camera.ui.design.LocalDesign
import app.proview.camera.ui.design.Palette
import app.proview.camera.ui.design.SvgIcon
import app.proview.camera.ui.design.Type
import app.proview.camera.ui.design.glass
import app.proview.camera.ui.design.spillBrush
import kotlin.math.roundToInt

/** Design px per value step when swiping each control (UI_SPEC §2, "Gestures"). */
private fun stepPx(c: Control) = when (c) {
    Control.APERTURE, Control.SHUTTER -> 20f
    Control.ISO, Control.FOCUS -> 24f
    Control.WB -> 28f
    Control.EV -> 12f
}

/** The control a mode selects by default (the design's modeSel table). */
fun defaultControl(mode: Mode) = when (mode) {
    Mode.PROGRAM -> Control.EV
    Mode.SHUTTER -> Control.SHUTTER
    else -> Control.APERTURE
}

/**
 * The glass instrument panel shown in P/A/S/M (UI_SPEC §2, item 4). Every value cell can be
 * swiped up/down or sideways; swiping a value the camera is choosing switches to Manual,
 * seeded with what auto-exposure had picked.
 */
@Composable
fun ProPanel(
    settings: CameraSettings,
    live: LiveReadout,
    meterStops: Float,
    selected: Control,
    onSelect: (Control) -> Unit,
    onSettings: (CameraSettings) -> Unit,
    onNextMode: () -> Unit,
    gridOn: Boolean,
    onToggleGrid: () -> Unit,
    remaining: Int,
    modifier: Modifier = Modifier,
    tint: Color = app.proview.camera.ui.design.DesignSpill,
) {
    val ds = LocalDesign.current
    val shape = RoundedCornerShape(ds.d(34))
    val current by rememberUpdatedState(settings)
    val meter by rememberUpdatedState(live.meter)
    val select by rememberUpdatedState(onSelect)
    val update by rememberUpdatedState(onSettings)
    val view = LocalView.current
    val density = LocalDensity.current

    fun Modifier.valueGestures(c: Control): Modifier = this
        .pointerInput(c) {
            detectTapGestures(onTap = { Haptics.tick(view); select(c) })
        }
        .pointerInput(c) {
            var start = 0
            var acc = 0f
            var active = false
            val step = with(density) { ds.d(stepPx(c)).toPx() }
            detectDragGestures(
                onDragStart = {
                    select(c)
                    var s = current
                    active = s.isUserSet(c) || c != Control.EV
                    if (active && !s.isUserSet(c)) {
                        // Taking control of an auto value: switch to Manual seeded from the meter.
                        s = s.toManual(meter)
                        update(s)
                        Haptics.heavy(view)
                    }
                    start = s.index(c)
                    acc = 0f
                },
                onDrag = { change, drag ->
                    if (!active) return@detectDragGestures
                    change.consume()
                    acc += drag.x - drag.y
                    val n = (start + (acc / step).roundToInt()).coerceIn(0, current.stepCount(c) - 1)
                    if (n != current.index(c)) {
                        Haptics.tick(view)
                        update(current.withIndex(c, n))
                    }
                },
            )
        }

    Box(
        modifier
            .clip(shape)
            .background(spillBrush(0.13f, tint), shape)
            .glass(shape),
    ) {
        // Hairlines and dividers.
        Hairline(14f, 53f, 338f, 1f)
        Hairline(14f, 150f, 338f, 1f)
        Hairline(14f, 194f, 338f, 1f)
        Hairline(76f, 15f, 1f, 34f)
        Hairline(155f, 15f, 1f, 34f)
        Hairline(274f, 155f, 1f, 34f)

        // Row 1: White balance, Focus, ISO.
        Cell(12f, 12f, 62f, 40f, selected == Control.WB, Modifier.valueGestures(Control.WB)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WbGlyph(if (selected == Control.WB) Palette.Accent else Palette.Text2)
                Spacer(Modifier.width(ds.d(6)))
                Text(settings.whiteBalance.label, style = ds.text(Type.Val, 14f), color = Palette.Text1)
            }
        }
        Cell(80f, 12f, 74f, 40f, selected == Control.FOCUS, Modifier.valueGestures(Control.FOCUS)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FocusGlyph(if (selected == Control.FOCUS) Palette.Accent else Palette.Text2)
                Spacer(Modifier.width(ds.d(6)))
                Text(Steps.focusLabel(settings.focusIndex), style = ds.text(Type.Val, 14f), color = Palette.Text1)
            }
        }
        Cell(190f, 12f, 164f, 40f, selected == Control.ISO, Modifier.valueGestures(Control.ISO), Alignment.CenterEnd) {
            val iso = if (settings.isUserSet(Control.ISO)) Steps.isos[settings.isoIndex] else live.iso
            Row(verticalAlignment = Alignment.Bottom) {
                Text("ISO", style = ds.text(Type.Over), color = if (selected == Control.ISO) Palette.Accent else Palette.Text2, modifier = Modifier.padding(bottom = ds.d(4)))
                Spacer(Modifier.width(ds.d(9)))
                Text(
                    if (iso > 0) iso.toString() else "—",
                    style = ds.text(Type.Disp, 30f),
                    color = if (settings.isUserSet(Control.ISO)) Palette.Text1 else Palette.Text2,
                )
            }
        }

        // Row 2: the hero numerals.
        val apUser = settings.isUserSet(Control.APERTURE)
        Hero(
            x = 12f, label = "APERTURE", prefix = "f/", value = Steps.apertures[settings.apertureIndex],
            dim = !apUser, selected = selected == Control.APERTURE, alignEnd = false,
            dots = Steps.apertures.size, dotIndex = settings.apertureIndex,
            gestures = Modifier.valueGestures(Control.APERTURE),
        )
        val shUser = settings.isUserSet(Control.SHUTTER)
        val shutterValue = if (shUser || live.exposureNs <= 0) {
            Steps.shutterDenominators[settings.shutterIndex].toString()
        } else {
            Steps.reciprocal(live.exposureNs).toString()
        }
        Hero(
            x = 186f, label = "SHUTTER", prefix = "1/", value = shutterValue,
            dim = !shUser, selected = selected == Control.SHUTTER, alignEnd = true,
            dots = Steps.shutterDenominators.size, dotIndex = settings.shutterIndex,
            gestures = Modifier.valueGestures(Control.SHUTTER),
        )

        // Row 3: EV meter and exposure compensation.
        EvMeter(meterStops, Modifier.then(ds.at(12f, 161f, 247f, 41f)))
        Cell(278f, 155f, 76f, 36f, selected == Control.EV, Modifier.valueGestures(Control.EV), Alignment.CenterEnd) {
            Row(
                Modifier.alpha(if (settings.isUserSet(Control.EV)) 1f else 0.5f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Steps.evLabel(settings.evThirds), style = ds.text(Type.Val, 16f), color = Palette.Text1)
                Spacer(Modifier.width(ds.d(6)))
                SvgIcon(
                    "M1.5 4.5a3 3 0 0 1 3-3h4a3 3 0 0 1 3 3v4a3 3 0 0 1-3 3h-4a3 3 0 0 1-3-3zM4 6.5H9M6.5 4V9",
                    ds.d(15),
                    if (selected == Control.EV) Palette.Accent else Palette.Text2,
                    viewBox = 13f,
                    strokeWidth = 1.5f,
                )
            }
        }

        // Row 4: mode letter, Eye AF (coming later), grid, remaining-shots counter.
        RoundButton(12f, 198f, 62f, 36f, 18f, onClick = onNextMode) {
            Text(settings.mode.letter, style = ds.text(Type.Disp, 22f), color = Palette.Accent)
        }
        RoundButton(84f, 198f, 36f, 36f, 18f, onClick = null, modifier = Modifier.alpha(0.35f)) {
            SvgIcon("M8 2a6 6 0 1 0 0 12a6 6 0 1 0 0-12zM8 5.6a2.4 2.4 0 1 0 0 4.8a2.4 2.4 0 1 0 0-4.8z", ds.d(18), Palette.Text2, viewBox = 16f, strokeWidth = 1.6f)
        }
        RoundButton(128f, 198f, 36f, 36f, 12f, onClick = onToggleGrid, outline = if (gridOn) Palette.Accent else null) {
            SvgIcon("M6 3h4a3 3 0 0 1 3 3v4a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z", ds.d(16), if (gridOn) Palette.Accent else Color(0xB3F5F5F7), viewBox = 16f, strokeWidth = 1.6f, filled = false)
        }
        Box(Modifier.then(ds.at(180f, 198f, 150f, 36f)), contentAlignment = Alignment.CenterEnd) {
            Text("[ ${remaining.toString().padStart(3, '0')} ]", style = ds.text(Type.Val, 20f), color = Palette.Text1)
        }
        Box(Modifier.then(ds.at(338f, 206f, 12f, 14f)).background(Palette.Accent, RoundedCornerShape(ds.d(4))))
    }
}

@Composable
private fun BoxScope.Hairline(x: Float, y: Float, w: Float, h: Float) {
    val ds = LocalDesign.current
    Box(Modifier.then(ds.at(x, y, w, h)).background(Palette.Line))
}

@Composable
private fun Cell(
    x: Float, y: Float, w: Float, h: Float,
    selected: Boolean,
    gestures: Modifier,
    align: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    val ds = LocalDesign.current
    Box(Modifier.then(ds.at(x, y, w, h)).then(gestures), contentAlignment = align) {
        content()
        SelectionBar(selected, Modifier.align(Alignment.BottomCenter).offset(y = -ds.d(3)))
    }
}

@Composable
private fun SelectionBar(on: Boolean, modifier: Modifier) {
    val ds = LocalDesign.current
    if (!on) return
    Box(modifier.size(ds.d(18), ds.d(2)).background(Palette.Accent, RoundedCornerShape(ds.d(2))))
}

@Composable
private fun Hero(
    x: Float, label: String, prefix: String, value: String,
    dim: Boolean, selected: Boolean, alignEnd: Boolean,
    dots: Int, dotIndex: Int, gestures: Modifier,
) {
    val ds = LocalDesign.current
    Box(Modifier.then(ds.at(x, 58f, 168f, 88f)).then(gestures)) {
        val labelAlign = if (alignEnd) Alignment.TopEnd else Alignment.TopStart
        Text(
            label,
            style = ds.text(Type.Over, 9f),
            color = if (selected) Palette.Accent else Palette.Text2,
            modifier = Modifier.align(labelAlign).padding(top = ds.d(8), start = ds.d(14), end = ds.d(14)),
        )
        Row(
            Modifier
                .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = ds.d(14)),
            verticalAlignment = Alignment.Bottom,
        ) {
            val color = if (dim) Palette.Text2 else Palette.Text1
            Text(prefix, style = ds.text(Type.Pre, 26f), color = color.copy(alpha = color.alpha * 0.85f), modifier = Modifier.padding(bottom = ds.d(6)))
            Text(value, style = ds.text(Type.Disp, 58f).copy(letterSpacing = Type.Disp.letterSpacing * 1.75f), color = color)
        }
        Row(
            Modifier
                .align(if (alignEnd) Alignment.BottomEnd else Alignment.BottomStart)
                .padding(bottom = ds.d(9), start = ds.d(14), end = ds.d(14)),
            horizontalArrangement = Arrangement.spacedBy(ds.d(4)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(dots) { i ->
                val on = i == dotIndex
                Box(
                    Modifier
                        .size(ds.d(if (on) 14 else 3), ds.d(3))
                        .background(
                            when {
                                on && selected -> Palette.Accent
                                on -> Color(0xD9F5F5F7)
                                else -> Color(0x47F5F5F7)
                            },
                            RoundedCornerShape(ds.d(2)),
                        ),
                )
            }
        }
    }
}

/** The −3…+3 EV scale with an accent needle (UI_SPEC §2, row 3). */
@Composable
private fun EvMeter(stops: Float, modifier: Modifier) {
    val ds = LocalDesign.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(ds.d(78)).padding(start = ds.d(6)), verticalAlignment = Alignment.CenterVertically) {
            Text("EV", style = ds.text(Type.Over), color = Color(0x8CF5F5F7))
            Spacer(Modifier.width(ds.d(3)))
            Text(Steps.signedStops(stops), style = ds.text(Type.Val, 15f), color = Palette.Text1)
        }
        Spacer(Modifier.width(ds.d(8)))
        Box(Modifier.size(ds.d(170), ds.d(22))) {
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / 170f
                for (j in 0..18) {
                    val major = j % 3 == 0
                    val zero = j == 9
                    val y = if (zero) 6f else if (major) 10f else 13f
                    val h = if (zero) 16f else if (major) 10f else 5f
                    val c = when {
                        zero -> Color(0xE6F5F5F7)
                        major -> Color(0x99F5F5F7)
                        else -> Color(0x40F5F5F7)
                    }
                    val x = j / 18f * 170f
                    drawLine(c, Offset(x * k, y * k), Offset(x * k, (y + h) * k), strokeWidth = 1.5f * k)
                }
                val nx = (stops.coerceIn(-3f, 3f) + 3f) / 6f * 170f * k
                drawLine(Palette.Accent.copy(alpha = 0.35f), Offset(nx, 0f), Offset(nx, size.height), strokeWidth = 6f * k)
                drawLine(Palette.Accent, Offset(nx, 0f), Offset(nx, size.height), strokeWidth = 2f * k)
            }
            for (v in -2..2) {
                Text(
                    if (v == 0) "0" else (if (v > 0) "+" else "−") + kotlin.math.abs(v),
                    style = ds.text(Type.Over, 8f),
                    color = if (v == 0) Color(0xD9F5F5F7) else Color(0x66F5F5F7),
                    modifier = Modifier.offset(x = ds.d((v + 3) / 6f * 170f - 4f), y = -ds.d(8)),
                )
            }
        }
    }
}

@Composable
private fun RoundButton(
    x: Float, y: Float, w: Float, h: Float, radius: Float,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    outline: Color? = null,
    content: @Composable () -> Unit,
) {
    val ds = LocalDesign.current
    val view = LocalView.current
    val shape = RoundedCornerShape(ds.d(radius))
    Box(
        Modifier
            .then(ds.at(x, y, w, h))
            .then(modifier)
            .clip(shape)
            .background(Color(0x0AFFFFFF), shape)
            .border(ds.d(1), outline ?: Color(0x4DF5F5F7), shape)
            .then(
                if (onClick != null) Modifier.pointerInput(onClick) {
                    detectTapGestures(onTap = { Haptics.tap(view); onClick() })
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun WbGlyph(color: Color) {
    val ds = LocalDesign.current
    Box(Modifier.size(ds.d(15))) {
        SvgIcon("M6.5 1.5a5 5 0 1 0 0 10a5 5 0 1 0 0-10z", ds.d(15), color, viewBox = 13f, strokeWidth = 1.5f)
        SvgIcon("M6.5 1.5A5 5 0 0 1 6.5 11.5Z", ds.d(15), color, viewBox = 13f, filled = true)
    }
}

@Composable
private fun FocusGlyph(color: Color) {
    val ds = LocalDesign.current
    SvgIcon("M6.5 1.5a5 5 0 1 0 0 10a5 5 0 1 0 0-10zM6.5 0V3M6.5 10V13M0 6.5H3M10 6.5H13", ds.d(15), color, viewBox = 13f, strokeWidth = 1.4f)
}
