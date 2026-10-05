package app.proview.camera.night

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the camera was doing just before the burst: carried over so the burst matches the preview. */
data class LockedState(
    val focusDiopters: Float?,
    val awbGains: RggbChannelVector?,
    val colorTransform: ColorSpaceTransform?,
)

/** A finished burst on disk: one DNG per RAW frame, the photo JPEG, and burst.json. */
data class BurstResult(
    val dir: File,
    val photoJpeg: ByteArray?,
    val rawFrames: Int,
)

/**
 * Captures a [NightPlan] as one Camera2 burst (docs/NIGHT_MODE.md, step 2).
 *
 * The camera must be free (CameraX unbound) before [capture] is called. Every RAW frame is
 * written as a DNG with its own capture result, so the merge (N2) and the test set get the
 * per-frame black level, noise profile and lens shading. The PHOTO frame is also captured as a
 * JPEG by the camera's own pipeline; that JPEG is the N1 photo until the merge exists.
 */
class RawBurstCapture(private val context: Context, private val cameraId: String = "0") {

    @SuppressLint("MissingPermission")
    suspend fun capture(
        plan: NightPlan,
        locked: LockedState,
        outDir: File,
        jpegOrientation: Int,
        /** Also write DNGs (for exporting the burst); the merge only needs the .raw16 frames. */
        writeDng: Boolean,
        onFrame: (done: Int, total: Int) -> Unit,
    ): BurstResult {
        val manager = context.getSystemService(CameraManager::class.java)
        val chars = manager.getCameraCharacteristics(cameraId)
        val thread = HandlerThread("raw-burst").apply { start() }
        val handler = Handler(thread.looper)
        val writer = Executors.newSingleThreadExecutor()
        outDir.mkdirs()
        File(outDir, "calibration.json").writeText(calibrationJson(chars).toString(2))

        val rawSize = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width.toLong() * it.height }
        val jpegSize = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.JPEG).maxBy { it.width.toLong() * it.height }

        val rawCount = plan.frames.count { it.role != FrameRole.PHOTO }
        val rawReader = ImageReader.newInstance(
            rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, (rawCount + 1).coerceAtMost(MAX_RAW_BUFFERS),
        )
        val jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            device = openWithRetry(manager, handler)
            session = createSession(device, listOf(rawReader, jpegReader), handler, writer)

            val rawSpecs = plan.frames.filter { it.role != FrameRole.PHOTO }
            val total = plan.frames.size
            val done = AtomicInteger(0)
            val results = ConcurrentHashMap<Long, TotalCaptureResult>()
            val roles = ConcurrentHashMap<Long, FrameRole>()
            val rawImages = ConcurrentHashMap<Long, Image>()
            val frameMeta = JSONArray()
            val written = CompletableDeferred<Unit>()
            val photo = CompletableDeferred<ByteArray?>()
            val rawWritten = AtomicInteger(0)
            val failures = AtomicInteger(0)
            var drainRaw: () -> Unit = {}

            fun tryWrite(ts: Long) {
                val result = results[ts] ?: return
                val image = rawImages.remove(ts) ?: return
                results.remove(ts)
                val role = roles.remove(ts) ?: FrameRole.BASE
                writer.execute {
                    val index = rawWritten.getAndIncrement()
                    try {
                        val rawFile = File(outDir, "frame_%02d.raw16".format(index))
                        writeRaw16(image, rawFile)
                        if (writeDng) {
                            DngCreator(chars, result).use { dng ->
                                dng.setOrientation(exifOrientation(jpegOrientation))
                                FileOutputStream(File(outDir, "frame_%02d.dng".format(index))).use { dng.writeImage(it, image) }
                            }
                        }
                        val meta = frameJson(index, rawFile.name, result)
                            .put("role", role.name)
                            .put("width", image.width)
                            .put("height", image.height)
                        synchronized(frameMeta) { frameMeta.put(meta) }
                    } catch (t: Throwable) {
                        failures.incrementAndGet()
                    } finally {
                        image.close()
                        if (rawWritten.get() >= rawSpecs.size) written.complete(Unit)
                        drainRaw()
                    }
                }
            }

            // Throws if every buffer is still waiting to be written; whatever stays queued is
            // drained again after each write finishes.
            fun drain() {
                while (true) {
                    val img = runCatching { rawReader.acquireNextImage() }.getOrNull() ?: return
                    rawImages[img.timestamp] = img
                    tryWrite(img.timestamp)
                }
            }
            drainRaw = { handler.post { drain() } }
            rawReader.setOnImageAvailableListener({ drain() }, handler)
            jpegReader.setOnImageAvailableListener({ r ->
                r.acquireNextImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    photo.complete(ByteArray(buf.remaining()).also { buf.get(it) })
                }
            }, handler)

            val requests = plan.frames.map { spec ->
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(if (spec.role == FrameRole.PHOTO) jpegReader.surface else rawReader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, spec.iso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, spec.exposureNs)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, spec.exposureNs)
                    locked.focusDiopters?.let {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
                    }
                    if (locked.awbGains != null && locked.colorTransform != null) {
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                        set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                        set(CaptureRequest.COLOR_CORRECTION_GAINS, locked.awbGains)
                        set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, locked.colorTransform)
                    }
                    if (supportsOis(chars)) {
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    }
                    // The merge needs the lens shading map; RAW pixels are untouched by these.
                    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                    if (spec.role == FrameRole.PHOTO) {
                        set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                        set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    }
                    setTag(spec)
                }.build()
            }

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    onFrame(done.incrementAndGet(), total)
                    val spec = request.tag as? FrameSpec
                    if (spec != null && spec.role != FrameRole.PHOTO) {
                        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        roles[ts] = spec.role
                        results[ts] = result
                        tryWrite(ts)
                    }
                }

                override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    onFrame(done.incrementAndGet(), total)
                    val spec = request.tag as? FrameSpec
                    if (spec?.role == FrameRole.PHOTO) photo.complete(null)
                    else if (rawWritten.incrementAndGet() >= rawSpecs.size) written.complete(Unit)
                }
            }, handler)

            val timeoutMs = plan.totalNs / 1_000_000 + 15_000
            val jpeg = withTimeout(timeoutMs) { photo.await() }
            withTimeout(timeoutMs) { written.await() }

            val sorted = JSONArray().also { out ->
                val list = (0 until frameMeta.length()).map { frameMeta.getJSONObject(it) }.sortedBy { it.getInt("index") }
                list.forEach { out.put(it) }
            }
            File(outDir, "burst.json").writeText(
                JSONObject()
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("cameraId", cameraId)
                    .put("steadiness", plan.steadiness.name)
                    .put("subjectMotion", plan.subjectMotion)
                    .put("plan", JSONArray().also { a ->
                        plan.frames.forEach { a.put(JSONObject().put("role", it.role.name).put("iso", it.iso).put("exposureNs", it.exposureNs)) }
                    })
                    .put("frames", sorted)
                    .put("writeFailures", failures.get())
                    .toString(2),
            )
            return BurstResult(outDir, jpeg, sorted.length())
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            rawReader.close()
            jpegReader.close()
            writer.shutdown()
            thread.quitSafely()
        }
    }

    /** CameraX releases the camera asynchronously, so the first open can fail with "in use". */
    @SuppressLint("MissingPermission")
    private suspend fun openWithRetry(manager: CameraManager, handler: Handler): CameraDevice {
        var last: Throwable? = null
        repeat(OPEN_ATTEMPTS) { attempt ->
            try {
                return openOnce(manager, handler)
            } catch (t: Throwable) {
                last = t
                kotlinx.coroutines.delay(150L * (attempt + 1))
            }
        }
        throw IllegalStateException("Couldn't open camera $cameraId", last)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openOnce(manager: CameraManager, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
                }
            }, handler)
        }

    private suspend fun createSession(
        device: CameraDevice,
        readers: List<ImageReader>,
        handler: Handler,
        executor: Executor,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) cont.resume(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Burst session configuration failed"))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputs = readers.map { OutputConfiguration(it.surface) }
            device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback))
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(readers.map { it.surface }, callback, handler)
        }
    }

    private fun supportsOis(chars: CameraCharacteristics): Boolean =
        chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

    private fun frameJson(index: Int, file: String, r: TotalCaptureResult): JSONObject {
        val o = JSONObject()
            .put("index", index)
            .put("file", file)
            .put("timestampNs", r.get(CaptureResult.SENSOR_TIMESTAMP))
            .put("iso", r.get(CaptureResult.SENSOR_SENSITIVITY))
            .put("exposureNs", r.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            .put("focusDiopters", r.get(CaptureResult.LENS_FOCUS_DISTANCE)?.toDouble())
            .put("ois", r.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE))
        r.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { profile ->
            o.put("noiseProfile", JSONArray().also { a -> profile.forEach { p -> a.put(JSONArray().put(p.first).put(p.second)) } })
        }
        r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { bl -> o.put("dynamicBlackLevel", JSONArray().also { a -> bl.forEach { a.put(it.toDouble()) } }) }
        r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.let { o.put("dynamicWhiteLevel", it) }
        r.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { g ->
            o.put("awbGains", JSONArray().put(g.red.toDouble()).put(g.greenEven.toDouble()).put(g.greenOdd.toDouble()).put(g.blue.toDouble()))
        }
        r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { map ->
            val gains = JSONArray()
            for (row in 0 until map.rowCount) for (col in 0 until map.columnCount) for (ch in 0 until 4) {
                gains.put(map.getGainFactor(ch, col, row).toDouble())
            }
            o.put("shadingMap", JSONObject().put("columns", map.columnCount).put("rows", map.rowCount).put("gains", gains))
        }
        return o
    }

    /** Copies a RAW_SENSOR image (16-bit, little-endian) to a plain file, row by row. */
    private fun writeRaw16(image: Image, file: File) {
        val plane = image.planes[0]
        // Work on a duplicate so the image's own buffer position is untouched for DngCreator.
        val buf = plane.buffer.duplicate()
        val rowBytes = image.width * 2
        val row = ByteArray(rowBytes)
        java.io.BufferedOutputStream(FileOutputStream(file), 1 shl 20).use { out ->
            for (y in 0 until image.height) {
                buf.position(y * plane.rowStride)
                buf.get(row, 0, rowBytes)
                out.write(row)
            }
        }
    }

    /** The sensor's static calibration: CFA, levels and the DNG colour matrices. */
    private fun calibrationJson(c: CameraCharacteristics): JSONObject {
        fun matrix(t: ColorSpaceTransform?): JSONArray? = t?.let {
            JSONArray().also { a -> for (row in 0..2) for (col in 0..2) a.put(it.getElement(col, row).toDouble()) }
        }
        val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val o = JSONObject()
            .put("cfa", cfa)
            .put("whiteLevel", c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023)
            .put("illuminant1", c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21)
            .put("illuminant2", c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 17)
            .put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
        c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
            // By 2x2 position, row-major: (0,0), (1,0), (0,1), (1,1).
            o.put("blackLevelByPosition", JSONArray().put(p.getOffsetForIndex(0, 0)).put(p.getOffsetForIndex(1, 0)).put(p.getOffsetForIndex(0, 1)).put(p.getOffsetForIndex(1, 1)))
        }
        matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))?.let { o.put("colorMatrix1", it) }
        matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))?.let { o.put("colorMatrix2", it) }
        matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))?.let { o.put("forwardMatrix1", it) }
        matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))?.let { o.put("forwardMatrix2", it) }
        matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))?.let { o.put("calibration1", it) }
        matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))?.let { o.put("calibration2", it) }
        return o
    }

    companion object {
        /** RAW buffers in flight: about 24 MB each at 12 MP. Writing keeps up with ~1/8 s frames. */
        private const val MAX_RAW_BUFFERS = 10
        private const val OPEN_ATTEMPTS = 8

        fun exifOrientation(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }
}
