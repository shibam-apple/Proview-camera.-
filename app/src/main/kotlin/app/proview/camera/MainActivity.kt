package app.proview.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.proview.camera.capture.CameraSettings
import app.proview.camera.capture.Mode
import app.proview.camera.capture.ProCamera
import app.proview.camera.capture.Steps
import app.proview.camera.device.DeviceProbe
import app.proview.camera.device.DeviceReport
import app.proview.camera.photos.PhotoRecord
import app.proview.camera.photos.PhotoStore
import app.proview.camera.ui.DeviceCheckScreen
import app.proview.camera.ui.camera.CameraScreen
import app.proview.camera.ui.design.Palette
import app.proview.camera.ui.design.Type
import app.proview.camera.ui.detail.DetailScreen
import app.proview.camera.ui.lab.LabScreen
import app.proview.camera.ui.library.LibraryScreen
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.proview.camera.ui.camera.BurstPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Screen { CAMERA, LIBRARY, DETAIL, DEVICE, LAB }

class MainActivity : ComponentActivity() {

    private val camera by lazy { ProCamera(applicationContext) }
    private val store by lazy { PhotoStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }

    @Composable
    private fun App() {
        var granted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

        var screen by remember { mutableStateOf(Screen.CAMERA) }
        var settings by remember { mutableStateOf(CameraSettings()) }
        var photos by remember { mutableStateOf<List<PhotoRecord>>(emptyList()) }
        var openPhoto by remember { mutableStateOf<PhotoRecord?>(null) }
        var labUri by remember { mutableStateOf<Uri?>(null) }

        LaunchedEffect(Unit) { photos = withContext(Dispatchers.IO) { store.all() } }

        BackHandler(enabled = screen != Screen.CAMERA) {
            screen = when (screen) {
                Screen.DETAIL -> Screen.LIBRARY
                Screen.LAB -> if (openPhoto != null) Screen.DETAIL else Screen.LIBRARY
                else -> Screen.CAMERA
            }
        }

        fun toggleFavourite(p: PhotoRecord) {
            store.setFavourite(p.uri, !p.favourite)
            photos = photos.map { if (it.uri == p.uri) it.copy(favourite = !p.favourite) else it }
            if (openPhoto?.uri == p.uri) openPhoto = openPhoto?.copy(favourite = !p.favourite)
        }

        when (screen) {
            Screen.CAMERA -> if (granted) {
                CameraScreen(
                    camera = camera,
                    settings = settings,
                    onSettings = { settings = it },
                    latestPhoto = photos.firstOrNull()?.uri,
                    onShot = { shot ->
                        val record = PhotoRecord(
                            uri = shot.uri,
                            takenAt = System.currentTimeMillis(),
                            frame = store.nextFrame(),
                            iso = shot.iso,
                            exposureNs = shot.exposureNs,
                            aperture = Steps.apertures[settings.apertureIndex],
                            whiteBalance = settings.whiteBalance.label,
                        )
                        store.add(record)
                        photos = listOf(record) + photos
                    },
                    onOpenLibrary = { screen = Screen.LIBRARY },
                    backgroundScope = lifecycleScope,
                )
            } else {
                PermissionScreen { request.launch(Manifest.permission.CAMERA) }
            }

            Screen.LIBRARY -> LibraryScreen(
                photos = photos,
                onOpen = { openPhoto = it; screen = Screen.DETAIL },
                onFavourite = ::toggleFavourite,
                onCamera = { screen = Screen.CAMERA },
                onDeviceCheck = { screen = Screen.DEVICE },
            )

            Screen.DETAIL -> openPhoto?.let { p ->
                DetailScreen(
                    photo = p,
                    onBack = { screen = Screen.LIBRARY },
                    onShootThisLook = {
                        // Load the photo's exposure into Manual, nearest to each step.
                        settings = settings.copy(
                            mode = Mode.MANUAL,
                            isoIndex = CameraSettings.nearestLog(Steps.isos, p.iso.toDouble()),
                            shutterIndex = CameraSettings.nearestLog(Steps.shutterDenominators, 1e9 / p.exposureNs.coerceAtLeast(1)),
                            apertureIndex = Steps.apertures.indexOf(p.aperture).coerceAtLeast(0),
                            wbIndex = Steps.whiteBalances.indexOfFirst { it.label == p.whiteBalance }.coerceAtLeast(0),
                            aeAfLocked = false,
                        )
                        screen = Screen.CAMERA
                    },
                    onFavourite = { toggleFavourite(p) },
                    onShare = { share(p) },
                    onEnhance = { labUri = p.uri; screen = Screen.LAB },
                )
            } ?: run { screen = Screen.LIBRARY }

            Screen.LAB -> LabScreen(
                initial = labUri,
                onBack = { screen = if (openPhoto != null) Screen.DETAIL else Screen.LIBRARY },
                onToast = { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() },
            )

            Screen.DEVICE -> {
                val report by produceState<DeviceReport?>(initialValue = null) {
                    value = withContext(Dispatchers.Default) { DeviceProbe(applicationContext).probe() }
                }
                var saveBursts by remember { mutableStateOf(BurstPrefs.saveBursts(this@MainActivity)) }
                var rawDay by remember { mutableStateOf(BurstPrefs.rawDay(this@MainActivity)) }
                DeviceCheckScreen(
                    report = report,
                    onShare = { report?.let(::shareReport) },
                    saveBursts = saveBursts,
                    onSaveBursts = { on -> saveBursts = on; BurstPrefs.setSaveBursts(this@MainActivity, on) },
                    rawDay = rawDay,
                    onRawDay = { on -> rawDay = on; BurstPrefs.setRawDay(this@MainActivity, on) },
                )
            }
        }
    }

    private fun share(p: PhotoRecord) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, p.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share photo"))
    }

    private fun shareReport(report: DeviceReport) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Proview device report")
            putExtra(Intent.EXTRA_TEXT, report.toText())
        }
        startActivity(Intent.createChooser(send, "Share report"))
    }
}

@Composable
private fun PermissionScreen(onAllow: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Proview needs the camera", style = Type.Ttl, color = Palette.Text1)
        Text("Photos are processed on your phone and saved to Pictures/Proview.", style = Type.Body, color = Palette.Text2)
        Button(
            onClick = onAllow,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
        ) { Text("Allow camera", style = Type.Button) }
    }
}
