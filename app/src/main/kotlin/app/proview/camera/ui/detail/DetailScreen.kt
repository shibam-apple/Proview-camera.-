package app.proview.camera.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import app.proview.camera.capture.Steps
import app.proview.camera.photos.PhotoRecord
import app.proview.camera.ui.design.DesignFrame
import app.proview.camera.ui.design.Haptics
import app.proview.camera.ui.design.IconPaths
import app.proview.camera.ui.design.LocalDesign
import app.proview.camera.ui.design.Palette
import app.proview.camera.ui.design.SvgIcon
import app.proview.camera.ui.design.Type
import app.proview.camera.ui.design.glass
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln

/** Detail.dc.html (UI_SPEC §4). */
@Composable
fun DetailScreen(
    photo: PhotoRecord,
    onBack: () -> Unit,
    onShootThisLook: () -> Unit,
    onFavourite: () -> Unit,
    onShare: () -> Unit,
    onEnhance: () -> Unit = {},
) {
    val view = LocalView.current
    DesignFrame {
        val ds = LocalDesign.current
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = ds.d(12), end = ds.d(12), top = ds.d(54), bottom = ds.d(34)),
            verticalArrangement = Arrangement.spacedBy(ds.d(12)),
        ) {
            // Nav.
            Row(Modifier.fillMaxWidth().height(ds.d(44)), verticalAlignment = Alignment.CenterVertically) {
                GlassCircle(44f, onClick = { Haptics.tap(view); onBack() }) {
                    SvgIcon(IconPaths.BACK, ds.d(22), Palette.Text1, strokeWidth = 2.4f)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(SimpleDateFormat("HH:mm", Locale.US).format(Date(photo.takenAt)), style = ds.text(Type.Ttl, 17f), color = Palette.Text1)
                    Spacer(Modifier.height(ds.d(5)))
                    Text(
                        SimpleDateFormat("d MMM", Locale.US).format(Date(photo.takenAt)).uppercase() + " · IMG " + photo.frame.toString().padStart(4, '0'),
                        style = ds.text(Type.Over),
                        color = Palette.Text2,
                    )
                }
                GlassCircle(44f, onClick = { Haptics.tap(view); onEnhance() }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ds.d(3.8f))) {
                        repeat(3) { Box(Modifier.size(ds.d(3.6f)).background(Palette.Text1, CircleShape)) }
                    }
                }
            }

            // Photo.
            val photoShape = RoundedCornerShape(ds.d(36))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(photoShape)
                    .background(Color(0xFF120F18))
                    .border(ds.d(1), Color(0x1AFFFFFF), photoShape),
            ) {
                AsyncImage(model = photo.uri, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x40000000), 0.28f to Color(0x00000000))))
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(ds.d(16))
                        .height(ds.d(30))
                        .glass(RoundedCornerShape(ds.d(15)))
                        .padding(horizontal = ds.d(12)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("JPEG", style = ds.text(Type.Over, 11f), color = Palette.Text1)
                }
            }

            // Shot settings as ring complications.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val apIdx = Steps.apertures.indexOf(photo.aperture).coerceAtLeast(0)
                Ring("f/${photo.aperture}", "Blur", 1f - apIdx / (Steps.apertures.size - 1f) * 0.85f, 18f, 0)
                val recip = Steps.reciprocal(photo.exposureNs.coerceAtLeast(1))
                Ring("1/$recip", "Freeze", (ln(recip.toDouble()) / ln(8000.0)).toFloat().coerceIn(0.05f, 1f), 16f, 1)
                Ring(photo.iso.toString(), "ISO", (ln(photo.iso / 100.0) / ln(64.0)).toFloat().coerceIn(0.05f, 1f), 18f, 2)
                Ring(photo.whiteBalance, "White", 0.58f, 15f, 3)
            }

            // Actions.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ds.d(10)), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(ds.d(56))
                        .background(Palette.Accent, RoundedCornerShape(ds.d(28)))
                        .clip(RoundedCornerShape(ds.d(28)))
                        .pointerInput(Unit) { detectTapGestures(onTap = { Haptics.tap(view); onShootThisLook() }) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SvgIcon(IconPaths.SLIDERS, ds.d(20), Palette.OnAccent, strokeWidth = 2.3f)
                    Spacer(Modifier.width(ds.d(10)))
                    Text("Shoot this look", style = ds.text(Type.Button), color = Palette.OnAccent)
                }
                HeartButton(photo.favourite) { Haptics.tap(view); onFavourite() }
                GlassCircle(56f, onClick = { Haptics.tap(view); onShare() }) {
                    Box {
                        SvgIcon(IconPaths.SHARE_ARROW, ds.d(24), Palette.Text1, strokeWidth = 2.1f)
                        SvgIcon(IconPaths.SHARE_TRAY, ds.d(24), Palette.Text1, strokeWidth = 2.1f)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassCircle(size: Float, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val ds = LocalDesign.current
    Box(
        Modifier
            .size(ds.d(size))
            .glass(CircleShape)
            .then(if (onClick != null) Modifier.pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) } else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun HeartButton(on: Boolean, onClick: () -> Unit) {
    val ds = LocalDesign.current
    val bump = remember { Animatable(1f) }
    LaunchedEffect(on) {
        bump.animateTo(1.45f, tween(80))
        bump.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 600f))
    }
    GlassCircle(56f, onClick) {
        Box(Modifier.scale(bump.value)) {
            if (on) SvgIcon(IconPaths.HEART, ds.d(24), Palette.Accent, filled = true)
            SvgIcon(IconPaths.HEART, ds.d(24), if (on) Palette.Accent else Palette.Text1, strokeWidth = 2.1f)
        }
    }
}

/** A 76 px ring complication: track, accent arc animated in with `ringIn`, value and caption. */
@Composable
private fun Ring(value: String, caption: String, fraction: Float, fontSize: Float, order: Int) {
    val ds = LocalDesign.current
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        delay(80L * order)
        sweep.animateTo(fraction, tween(900, easing = FastOutSlowInEasing))
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ds.d(6))) {
        Box(Modifier.size(ds.d(76)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / 76f
                val stroke = 6f * k
                val inset = 38f * k - 32f * k
                val arcSize = Size(64f * k, 64f * k)
                drawArc(Color(0x24FFFFFF), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(Palette.Accent, -90f, 360f * sweep.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(value, style = ds.text(Type.Val, fontSize), color = Palette.Text1)
        }
        Text(caption, style = ds.text(Type.Ttl, 12f), color = Palette.Text2)
    }
}
