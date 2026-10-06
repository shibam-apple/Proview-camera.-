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
import app.proview.camera.night.BurstResult
import app.proview.camera.night.LockedState
import app.proview.camera.night.NightPlan
import app.proview.camera.night.RawBurstCapture
import app.proview.camera.night.SceneMotion
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCaseGroup
import app.proview.camera.finder.FinderProcessor
import app.proview.pipeline.look.Look
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
    val exposureRangeNs: LongRange = 100_000L..1_000_000_000L,
    /** Degrees the sensor image is rotated from the phone held upright (JPEG/DNG orientation). */
    val sensorOrientation: Int = 90,
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

    /** Live viewfinder shader: look LUT + waist-level-finder optics. */
    val finder = FinderProcessor()
    private val finderEffect = finder.Effect()

    private val _sceneColor = MutableStateFlow(0xFF7F7468.toInt())

    /** Average colour of the scene (ARGB), for UI that reacts to what the camera sees. */
    val sceneColor: StateFlow<Int> = _sceneColor.asStateFlow()
    private var colorFrame = 0

    private val _sceneMotion = MutableStateFlow(0f)

    /** Frame-to-frame scene change, mean-removed (see SceneMotion); high when subjects move. */
    val sceneMotion: StateFlow<Float> = _sceneMotion.asStateFlow()
    private var previousThumb: FloatArray? = null

    // Last focus and colour state from the preview, carried into a night burst.
    @Volatile private var lastFocusDiopters: Float? = null
    @Volatile private var lastAwbGains: RggbChannelVector? = null
    @Volatile private var lastColorTransform: ColorSpaceTransform? = null

    private var boundOwner: LifecycleOwner? = null
    private var boundView: PreviewView? = null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val autoExposure = result.get(CaptureResult.CONTROL_AE_MODE) != CaptureResult.CONTROL_AE_MODE_OFF
            lastFocusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            lastAwbGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            lastColorTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            val prev = _live.value
            _live.value = LiveReadout(
                iso = iso,
                exposureNs = exposure,
                meter = if (autoExposure) Meter(iso, exposure) else prev.meter,
            )
        }
    }

    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        boundOwner = owner
        boundView = previewView
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
                val y = image.planes[0]
                _histogram.value = Histogram.luma(y.buffer, image.width, image.height, y.rowStride)
                val thumb = Histogram.thumbnail(y.buffer, image.width, image.height, y.rowStride)
                previousThumb?.let { _sceneMotion.value = SceneMotion.difference(it, thumb) }
                if (colorFrame++ % 3 == 0) _sceneColor.value = Histogram.averageColor(image)
                previousThumb = thumb
                image.close()
            }

            p.unbindAll()
            val group = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(capture)
                .addUseCase(analysis)
                .addEffect(finderEffect)
                .build()
            val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
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
        val exposure = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val orientation = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
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
            exposureRangeNs = exposure?.let { it.lower..it.upper } ?: (100_000L..1_000_000_000L),
            sensorOrientation = orientation,
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

    /** Switches the look in the viewfinder (the photo pipeline applies it at develop time). */
    suspend fun setLook(look: Look) = withContext(Dispatchers.Default) { finder.setLook(look) }

    /**
     * Full-resolution JPEG from the camera's own pipeline, in memory, with the clockwise rotation
     * needed to display it upright. The look is applied before it is saved.
     */
    suspend fun captureJpeg(): Pair<ByteArray, Int> = suspendCancellableCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.resumeWithException(IllegalStateException("Camera not ready"))
            return@suspendCancellableCoroutine
        }
        capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    cont.resume(bytes to image.imageInfo.rotationDegrees)
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
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

    /**
     * Night capture (docs/NIGHT_MODE.md): frees the camera from CameraX, runs [plan] as one
     * Camera2 RAW burst into [outDir], then rebinds the preview. Returns the burst on disk and
     * the PHOTO frame's JPEG.
     */
    suspend fun captureNight(plan: NightPlan, outDir: File, writeDng: Boolean, onFrame: (Int, Int) -> Unit): BurstResult {
        val locked = LockedState(lastFocusDiopters, lastAwbGains, lastColorTransform)
        val owner = boundOwner
        val view = boundView
        unbind()
        try {
            return RawBurstCapture(context).capture(plan, locked, outDir, _facts.value.sensorOrientation, writeDng, onFrame)
        } finally {
            if (owner != null && view != null) withContext(Dispatchers.Main) { bind(owner, view) }
        }
    }

    /** Saves camera-encoded JPEG bytes to Pictures/Proview, like [capture]. */
    @SuppressLint("InlinedApi")
    suspend fun saveJpeg(bytes: ByteArray, suffix: String = ""): Uri = withContext(Dispatchers.IO) {
        val name = "PRV_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + suffix
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM_PATH)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Couldn't open $uri")
        uri
    }

    companion object {
        const val HISTOGRAM_BINS = 16
        const val ALBUM_PATH = "Pictures/Proview"
        private const val FRAME_NS_30FPS = 33_333_333L
    }
}
