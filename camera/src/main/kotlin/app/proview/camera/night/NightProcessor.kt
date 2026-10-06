package app.proview.camera.night

import app.proview.camera.develop.PhotoDeveloper
import app.proview.pipeline.look.FilmRender
import app.proview.pipeline.look.Look
import app.proview.pipeline.TileAligner
import app.proview.pipeline.finish.ColorCalibration
import app.proview.pipeline.finish.FinishParams
import app.proview.pipeline.finish.Finisher
import app.proview.pipeline.finish.M3
import app.proview.pipeline.finish.RenderStyle
import app.proview.pipeline.finish.SensorColor
import app.proview.pipeline.finish.ShadingMap
import app.proview.pipeline.merge.BurstMerger
import app.proview.pipeline.raw.Cfa
import app.proview.pipeline.raw.NoiseModel
import app.proview.pipeline.raw.RawFrame
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln

/** What happened while developing, for the photo record and debugging. */
data class DevelopReport(
    val framesMerged: Int,
    val meanWeight: Float,
    val exposureGain: Float,
    val millis: Long,
) {
    val gainCapped: Boolean get() = exposureGain >= NightProcessor.MAX_GAIN - 1e-3f
}

/**
 * Develops a captured night burst into the final photo (docs/NIGHT_MODE.md, N2):
 * read the base RAW frames -> merge (align + robust blend) -> finish with the sensor's own
 * colour calibration ("true colour") and the low-key night tone -> JPEG.
 */
object NightProcessor {
    /**
     * Brightness the merged scene is rendered at: log-average luminance target. Daylight
     * photography aims for ~0.18; night keeps it low so the night stays night.
     */
    const val NIGHT_KEY = 0.12f
    const val MIN_GAIN = 1.0f

    /** 15 merged frames cut noise ~4x, which allows a lot more digital gain than one frame. */
    const val MAX_GAIN = 24f

    /**
     * Merges the burst in [dir] and finishes it. With [style] (day photos) tone and colour come
     * from the Rendition; without it, the night path's own key, gain and shoulder are used.
     */
    fun develop(dir: File, look: Look = Look.DEFAULT, style: RenderStyle? = null, onProgress: (Float) -> Unit = {}): Pair<ByteArray, DevelopReport> {
        val start = System.currentTimeMillis()
        val cal = JSONObject(File(dir, "calibration.json").readText())
        val burst = JSONObject(File(dir, "burst.json").readText())
        val frames = burst.getJSONArray("frames").objects()
            .filter { it.optString("role") == FrameRole.BASE.name }
            .sortedBy { it.getInt("index") }
        require(frames.isNotEmpty()) { "No base frames in burst" }

        val cfa = Cfa.entries[cal.optInt("cfa", 0).coerceIn(0, 3)]
        val white = cal.optInt("whiteLevel", 1023).toFloat()

        fun load(meta: JSONObject): RawFrame {
            val w = meta.getInt("width")
            val h = meta.getInt("height")
            val bytes = File(dir, meta.getString("file")).readBytes()
            val shorts = ShortArray(w * h)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            val black = blackLevels(meta, cal, cfa)
            val noise = noiseModel(meta)
            return RawFrame.fromU16(w, h, shorts, cfa, black, meta.optDouble("dynamicWhiteLevel", white.toDouble()).toFloat(), noise)
        }

        val ref = frames.first()
        val merger = BurstMerger(load(ref), TileAligner())
        frames.drop(1).forEachIndexed { k, meta ->
            merger.add(load(meta))
            onProgress((k + 1f) / frames.size * 0.75f)
        }
        val merged = merger.result()
        onProgress(0.8f)

        val gains = ref.optJSONArray("awbGains")?.floats() ?: floatArrayOf(2f, 1f, 1f, 1.8f)
        val wb = FloatArray(4) { gains[it] / ((gains[1] + gains[2]) / 2f) }
        val color = sensorColor(cal)
        val camToSrgb = if (color != null) {
            ColorCalibration.cameraToSrgb(color, doubleArrayOf(wb[0].toDouble(), ((wb[1] + wb[2]) / 2f).toDouble(), wb[3].toDouble()))
        } else {
            M3.IDENTITY
        }
        val gain = if (style == null || !style.autoExposure) exposureGain(merged, wb) else 1f
        val params = FinishParams(
            wbGains = wb,
            cameraToSrgb = camToSrgb,
            exposureGain = gain,
            shading = shadingMap(ref),
            orientation = cal.optInt("sensorOrientation", 90),
            style = style,
        )
        val out = Finisher.finish(merged, params)
        onProgress(0.9f)
        FilmRender.apply(out.argb, out.width, out.height, look)
        val jpeg = PhotoDeveloper.encode(out.argb, out.width, out.height)
        onProgress(1f)
        val report = DevelopReport(merger.framesMerged, merger.frameWeights.average().toFloat().takeIf { !it.isNaN() } ?: 1f, gain, System.currentTimeMillis() - start)
        return jpeg to report
    }

    /**
     * Gain that brings the merged scene's log-average green to [NIGHT_KEY], within limits:
     * never darker than captured, never more than [MAX_GAIN] (noise would take over).
     */
    fun exposureGain(frame: RawFrame, wb: FloatArray): Float {
        var sum = 0.0
        var n = 0
        val step = 8
        for (y in 0 until frame.height - 1 step step) {
            for (x in 0 until frame.width - 1 step step) {
                // Mean of a 2x2 cell is a luminance-like value; WB green is ~1.
                val v = 0.25f * (frame[x, y] * wb[frame.cfa.channelAt(x, y)] +
                    frame[x + 1, y] * wb[frame.cfa.channelAt(x + 1, y)] +
                    frame[x, y + 1] * wb[frame.cfa.channelAt(x, y + 1)] +
                    frame[x + 1, y + 1] * wb[frame.cfa.channelAt(x + 1, y + 1)])
                sum += ln(maxOf(v, 1e-4f).toDouble())
                n++
            }
        }
        val key = exp(sum / n).toFloat()
        return (NIGHT_KEY / key).coerceIn(MIN_GAIN, MAX_GAIN)
    }

    private fun blackLevels(meta: JSONObject, cal: JSONObject, cfa: Cfa): FloatArray {
        // Both are listed by 2x2 position; convert to channel order R, G1, G2, B.
        val byPosition = meta.optJSONArray("dynamicBlackLevel")?.floats()
            ?: cal.optJSONArray("blackLevelByPosition")?.floats()
            ?: floatArrayOf(64f, 64f, 64f, 64f)
        val out = FloatArray(4)
        for (pos in 0 until 4) out[cfa.channelAt(pos % 2, pos / 2)] = byPosition[pos]
        return out
    }

    private fun noiseModel(meta: JSONObject): NoiseModel {
        val profile = meta.optJSONArray("noiseProfile")
        if (profile != null && profile.length() >= 4) {
            val s = FloatArray(4) { profile.getJSONArray(it).getDouble(0).toFloat() }
            val o = FloatArray(4) { profile.getJSONArray(it).getDouble(1).toFloat() }
            return NoiseModel(s, o)
        }
        return NoiseModel.generic(meta.optInt("iso", 1600))
    }

    private fun shadingMap(meta: JSONObject): ShadingMap? {
        val m = meta.optJSONObject("shadingMap") ?: return null
        return ShadingMap(m.getInt("columns"), m.getInt("rows"), m.getJSONArray("gains").floats())
    }

    private fun sensorColor(cal: JSONObject): SensorColor? {
        val cm1 = cal.optJSONArray("colorMatrix1")?.doubles() ?: return null
        val cm2 = cal.optJSONArray("colorMatrix2")?.doubles() ?: cm1
        return SensorColor(
            illuminant1Cct = ColorCalibration.illuminantCct(cal.optInt("illuminant1", 21)),
            illuminant2Cct = ColorCalibration.illuminantCct(cal.optInt("illuminant2", 17)),
            colorMatrix1 = cm1,
            colorMatrix2 = cm2,
            forwardMatrix1 = cal.optJSONArray("forwardMatrix1")?.doubles(),
            forwardMatrix2 = cal.optJSONArray("forwardMatrix2")?.doubles() ?: cal.optJSONArray("forwardMatrix1")?.doubles(),
            calibration1 = cal.optJSONArray("calibration1")?.doubles() ?: M3.IDENTITY,
            calibration2 = cal.optJSONArray("calibration2")?.doubles() ?: M3.IDENTITY,
        )
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }
    private fun JSONArray.doubles() = DoubleArray(length()) { getDouble(it) }
}
