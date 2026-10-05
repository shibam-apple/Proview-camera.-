package app.proview.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import app.proview.camera.device.CameraInfo
import app.proview.camera.device.DeviceReport
import app.proview.camera.device.Tier
import app.proview.camera.ui.design.Palette
import app.proview.camera.ui.design.Type
import app.proview.camera.ui.design.glass

/**
 * M0 build only: shows what Camera2 exposes on this phone, so the pipeline tier and lens
 * mapping can be confirmed on real hardware. Moves to a debug menu once the camera UI lands.
 */
@Composable
fun DeviceCheckScreen(report: DeviceReport?, onShare: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = androidx.compose.runtime.remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text("PROVIEW · BUILD $version".uppercase(), style = Type.Over, color = Palette.Text2)
                    Spacer(Modifier.height(10.dp))
                    Text("Device check", style = Type.Header, color = Palette.Text1)
                }
            }
            if (report == null) {
                item { Text("Reading cameras…", style = Type.Body, color = Palette.Text2, modifier = Modifier.padding(8.dp)) }
            } else {
                item { TierCard(report) }
                items(report.cameras, key = { it.id }) { CameraCard(it) }
            }
        }

        ShareButton(
            enabled = report != null,
            onClick = onShare,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 30.dp),
        )
    }
}

private fun tierMeaning(tier: Tier?) = when (tier) {
    Tier.A -> "RAW burst merge · best quality"
    Tier.B -> "YUV burst merge"
    Tier.C -> "Single frame + enhancement"
    null -> "No back camera found"
}

@Composable
private fun TierCard(report: DeviceReport) {
    val shape = RoundedCornerShape(34.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .glass(shape)
            // The design's warm spill at the top of glass panels.
            .background(Brush.verticalGradient(listOf(Palette.Accent.copy(alpha = 0.14f), Palette.Bg.copy(alpha = 0f))), shape)
            .padding(20.dp),
    ) {
        Column {
            Text("BEST TIER", style = Type.Over, color = Palette.Accent)
            Spacer(Modifier.height(8.dp))
            Text(report.bestBackTier?.name ?: "—", style = Type.Disp, color = Palette.Text1)
            Spacer(Modifier.height(6.dp))
            Text(tierMeaning(report.bestBackTier), style = Type.Body, color = Palette.Text1)
            Spacer(Modifier.height(14.dp))
            Text("${report.manufacturer} ${report.model}", style = Type.Ttl, color = Palette.Text1)
            Spacer(Modifier.height(6.dp))
            val soc = report.socModel?.let { " · $it" }.orEmpty()
            Text("Android ${report.androidVersion} (SDK ${report.sdkInt})$soc", style = Type.Cap, color = Palette.Text2)
        }
    }
}

@Composable
private fun CameraCard(camera: CameraInfo) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .glass(shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Camera ${camera.id} · ${camera.facing.name.lowercase()}",
                style = Type.Ttl,
                color = Palette.Text1,
                modifier = Modifier.weight(1f),
            )
            TierChip(camera.tier)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("FOCAL", camera.equivalentFocalMm?.let { "$it mm" } ?: "—", Modifier.weight(1f))
            Stat("SENSOR", "%.1f MP".format(camera.megapixels), Modifier.weight(1f))
            Stat("RAW", if (camera.supportsRaw) "Yes" else "No", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("ISO", camera.isoRange?.let { "${it.first}–${it.last}" } ?: "—", Modifier.weight(1f))
            Stat("SHUTTER", camera.exposureRangeNs?.let { shutterRange(it) } ?: "—", Modifier.weight(2f))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Level ${camera.hardwareLevel} · manual ${yesNo(camera.supportsManualSensor)} · burst ${yesNo(camera.supportsBurst)}",
            style = Type.Cap,
            color = Palette.Text2,
        )
    }
}

@Composable
private fun TierChip(tier: Tier) {
    val shape = RoundedCornerShape(12.dp)
    val best = tier == Tier.A
    Box(
        Modifier
            .height(24.dp)
            .then(if (best) Modifier.background(Palette.Accent, shape) else Modifier.glass(shape))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("TIER ${tier.name}", style = Type.Over, color = if (best) Palette.OnAccent else Palette.Text1)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = Type.Over, color = Palette.Text2)
        Spacer(Modifier.height(6.dp))
        Text(value, style = Type.Val, color = Palette.Text1)
    }
}

@Composable
private fun ShareButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Palette.Accent,
            contentColor = Palette.OnAccent,
            disabledContainerColor = Palette.GlassFill,
            disabledContentColor = Palette.Text3,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text("Share report", style = Type.Button)
    }
}

private fun yesNo(b: Boolean) = if (b) "yes" else "no"

/** Exposure range as photographers read it: "1/100000 – 30 s". */
private fun shutterRange(ns: LongRange): String {
    fun fmt(n: Long): String {
        val s = n / 1e9
        return if (s >= 1) "%.0f s".format(s) else "1/%d".format(Math.round(1 / s))
    }
    return "${fmt(ns.first)} – ${fmt(ns.last)}"
}
