package app.proview.camera.ui.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import app.proview.camera.night.MotionReading
import app.proview.camera.night.Steadiness
import app.proview.camera.ui.design.LocalDesign
import app.proview.camera.ui.design.Palette
import app.proview.camera.ui.design.SvgIcon
import app.proview.camera.ui.design.Type
import app.proview.camera.ui.design.glass
import java.io.File

/** Crescent moon, 24-unit viewBox. */
const val MOON_PATH = "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"

/**
 * Steadiness crosshair shown while a night burst is captured: a fixed cross at the centre and
 * a dot that drifts with the phone's rotation. Keep the dot on the cross for a sharp photo.
 */
@Composable
fun NightCrosshair(motion: MotionReading, modifier: Modifier = Modifier) {
    val steady = motion.steadiness != Steadiness.HANDHELD
    Canvas(modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, size.height / 2)
        val arm = size.minDimension * 0.06f
        val line = Color(0x99F5F5F7)
        drawLine(line, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = 2f)
        drawLine(line, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = 2f)
        // About 1 degree of rotation moves the dot most of the way to the end of an arm.
        val k = arm * 50f
        val dx = (motion.driftY * k).coerceIn(-arm * 2, arm * 2)
        val dy = (motion.driftX * k).coerceIn(-arm * 2, arm * 2)
        drawCircle(if (steady) Palette.Accent else Palette.Text1, radius = arm * 0.22f, center = Offset(c.x + dx, c.y + dy))
    }
}

/** Auto mode's scene chip when night mode is in play: tap to turn night off for now, or back on. */
@Composable
fun NightChip(active: Boolean, seconds: Float, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val ds = LocalDesign.current
    val toggle by androidx.compose.runtime.rememberUpdatedState(onToggle)
    Row(
        modifier
            .height(ds.d(30))
            .glass(RoundedCornerShape(ds.d(15)))
            .pointerInput(Unit) { detectTapGestures(onTap = { toggle() }) }
            .padding(start = ds.d(10), end = ds.d(13)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ds.d(7)),
    ) {
        SvgIcon(MOON_PATH, ds.d(14), if (active) Palette.Accent else Palette.Text2, filled = active, strokeWidth = 1.8f)
        Text(
            if (active) "Night · %.1f s".format(seconds) else "Night off",
            style = ds.text(Type.Cap),
            color = if (active) Palette.Text1 else Palette.Text2,
        )
    }
}

/** "Developing night photo…" while bursts are processed in the background. */
@Composable
fun DevelopingChip(count: Int, modifier: Modifier = Modifier) {
    val ds = LocalDesign.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(ds.d(30))
                .glass(RoundedCornerShape(ds.d(15)))
                .padding(horizontal = ds.d(13)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ds.d(7)),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(ds.d(12)),
                color = Palette.Accent,
                strokeWidth = ds.d(1.5f),
            )
            Text(
                if (count > 1) "Developing $count night photos…" else "Developing night photo…",
                style = ds.text(Type.Cap),
                color = Palette.Text1,
            )
        }
    }
}

/** Dims the frozen viewfinder while the camera is busy with a burst. */
@Composable
fun CapturingVeil(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color(0x59000000)))
}

/** Debug preference: keep each night burst for the test set (off by default: ~25 MB per frame). */
object BurstPrefs {
    private const val FILE = "debug"
    private const val KEY = "saveBursts"

    fun saveBursts(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setSaveBursts(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/**
 * Copies a burst folder to Download/Proview/Bursts/<name>/ so it can be shared from the Files
 * app. Returns the number of files copied. Android 10+ only (MediaStore Downloads).
 */
fun exportBurst(context: Context, dir: File): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
    val resolver = context.contentResolver
    var copied = 0
    dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (f.name.endsWith(".dng")) "image/x-adobe-dng" else "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Proview/Bursts/${dir.name}")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@forEach
        resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 20) } }
        copied++
    }
    return copied
}
