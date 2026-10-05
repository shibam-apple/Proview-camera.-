package app.proview.camera.capture

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Live values from the sensor, updated every preview frame. */
data class LiveReadout(
    val iso: Int = 0,
    val exposureNs: Long = 0,
    /** The last values auto-exposure chose; frozen while the sensor is driven manually. */
    val meter: Meter? = null,
)

/** Static facts about the bound camera. */
data class CameraFacts(
    val isoRange: IntRange = 100..3200,
    val minFocusDiopters: Float = 10f,
    val evStepThirds: Float = 1f / 3f,
    val maxZoomRatio: Float = 1f,
    /** 35 mm-equivalent focal length at 1x zoom. */
    val baseFocalMm: Int = 26,
)

/**
 * The capture side of the M1 camera: preview, manual controls, metering readout,
 * a luminance histogram and full-resolution JPEG capture, built on CameraX with Camera2 interop.
 *
 * Multi-frame capture replaces the single-frame [capture] in M2 (SPEC §10).
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class ProCamera(private val context: Context) {

    private val _live = MutableStateFlow(LiveReadout())
    val live: StateFlow<LiveReadout> = _live.asStateFlow()

    private val _histogram = MutableStateFlow(FloatArray(HISTOGRAM_BINS))
    val histogram: StateFlow<FloatArray> = _histogram.asStateFlow()

    private val _facts = MutableStateFlow(CameraFacts())
    val facts: StateFlow<CameraFacts> = _facts.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var settings = CameraSettings()
    private var lastApplied: Pair<ExposurePlan, CameraSettings>? = null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val autoExposure = result.get(CaptureResult.CONTROL_AE_MODE) != CaptureResult.CONTROL_AE_MODE_OFF
            val prev = _live.value
            _live.value = LiveReadout(
                iso = iso,
                exposureNs = exposure,
                meter = if (autoExposure) Meter(iso, exposure) else prev.meter,
            )
        }
    }

    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            val ratio = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val previewBuilder = Preview.Builder().setResolutionSelector(ratio)
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
            val preview = previewBuilder.build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
                .setResolutionSelector(ratio)
                .build()
            imageCapture = capture

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                _histogram.value = Histogram.luma(image.planes[0].buffer, image.width, image.height, image.planes[0].rowStride)
                image.close()
            }

            p.unbindAll()
            val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
            camera = cam
            _facts.value = readFacts(cam)
            lastApplied = null
            apply(settings)
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        provider?.unbindAll()
        camera = null
        imageCapture = null
    }

    private fun readFacts(cam: Camera): CameraFacts {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
        val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val step = cam.cameraInfo.exposureState.exposureCompensationStep
        val baseFocal = if (focal != null && size != null) {
            val diagonal = Math.hypot(size.width.toDouble(), size.height.toDouble())
            (focal * 43.27 / diagonal).roundToInt()
        } else 26
        return CameraFacts(
            isoRange = iso?.let { it.lower..it.upper } ?: (100..3200),
            minFocusDiopters = minFocus,
            evStepThirds = if (step.denominator != 0) step.toFloat() * 3f else 1f,
            maxZoomRatio = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f,
            baseFocalMm = baseFocal,
        )
    }

    /** Push [s] to the sensor. Cheap to call often: unchanged settings are skipped. */
    fun apply(s: CameraSettings) {
        settings = s
        val cam = camera ?: return
        val facts = _facts.value
        val plan = s.exposurePlan(_live.value.meter, facts.isoRange)
        if (lastApplied == plan to s) return
        lastApplied = plan to s

        val options = CaptureRequestOptions.Builder()
        when (plan) {
            is ExposurePlan.Fixed -> {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
                options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureNs)
                options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(plan.exposureNs, FRAME_NS_30FPS))
            }
            is ExposurePlan.Auto -> {
                if (plan.locked) options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            }
        }
        options.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            when (s.whiteBalance) {
                WhiteBalance.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                WhiteBalance.SUN -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                WhiteBalance.CLOUD -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                WhiteBalance.WARM -> CaptureRequest.CONTROL_AWB_MODE_SHADE
            },
        )
        val metres = Steps.focusMetres[s.focusIndex]
        if (metres != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            val diopters = if (metres.isInfinite()) 0f else (1f / metres).coerceAtMost(facts.minFocusDiopters)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options.build())

        val evIndex = if (plan is ExposurePlan.Auto) (plan.evThirds / facts.evStepThirds).roundToInt() else 0
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        if (cam.cameraInfo.exposureState.isExposureCompensationSupported) {
            cam.cameraControl.setExposureCompensationIndex(evIndex.coerceIn(range.lower, range.upper))
        }
    }

    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(1f, _facts.value.maxZoomRatio))
    }

    /** Focus and meter at a point in [view] coordinates; [hold] keeps it until [releaseFocus]. */
    fun focusAt(view: PreviewView, x: Float, y: Float, hold: Boolean) {
        val cam = camera ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .apply { if (hold) disableAutoCancel() else setAutoCancelDuration(3, TimeUnit.SECONDS) }
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun releaseFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    /** Take a full-resolution JPEG and save it to Pictures/Proview. Returns its MediaStore URI. */
    @SuppressLint("InlinedApi")
    suspend fun capture(): Uri = suspendCancellableCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.resumeWithException(IllegalStateException("Camera not ready"))
            return@suspendCancellableCoroutine
        }
        val name = "PRV_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM_PATH)
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
        capture.takePicture(output, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val uri = result.savedUri
                if (uri != null) cont.resume(uri) else cont.resumeWithException(IllegalStateException("No URI returned"))
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    companion object {
        const val HISTOGRAM_BINS = 16
        const val ALBUM_PATH = "Pictures/Proview"
        private const val FRAME_NS_30FPS = 33_333_333L
    }
}
