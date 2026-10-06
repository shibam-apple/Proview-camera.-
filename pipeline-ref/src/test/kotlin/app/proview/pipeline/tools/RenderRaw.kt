package app.proview.pipeline.tools

import app.proview.pipeline.finish.ColorCalibration
import app.proview.pipeline.finish.RenderStyle
import app.proview.pipeline.finish.Rendition
import app.proview.pipeline.finish.SensorColor
import app.proview.pipeline.raw.Cfa
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * Desktop harness for calibrating the rendering on real RAW files:
 * `./gradlew renderRaw -Pin=<dir of .u16 + .json> -Pout=<dir> [-Pstyle=key=0.16,contrast=1.1,...]`.
 * Uses a 2x2 superpixel demosaic (half resolution) so tuning runs are fast.
 */
fun main(args: Array<String>) {
    val inDir = File(args[0])
    val outDir = File(args[1]).apply { mkdirs() }
    val style = parseStyle(args.getOrNull(2))
    val only = args.getOrNull(3)?.takeIf { it != "-" }
    println("style $style")
    for (meta in inDir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
        if (only != null && !meta.name.startsWith(only)) continue
        val j = Json.parse(meta.readText())
        val w = (j["width"] as Number).toInt()
        val h = (j["height"] as Number).toInt()
        val cfa = Cfa.valueOf(j["cfa"] as String)
        val black = (j["black"] as List<*>).map { (it as Number).toFloat() }
        val white = (j["white"] as Number).toFloat()
        val wb = (j["wb"] as List<*>).map { (it as Number).toFloat() }
        fun mat(k: String) = (j[k] as List<*>?)?.map { (it as Number).toDouble() }?.toDoubleArray()
        val color = SensorColor(
            ColorCalibration.illuminantCct((j["illuminant1"] as Number).toInt()),
            ColorCalibration.illuminantCct((j["illuminant2"] as Number).toInt()),
            mat("colorMatrix1")!!, mat("colorMatrix2")!!, mat("forwardMatrix1"), mat("forwardMatrix2"),
        )
        val m = ColorCalibration.cameraToSrgb(color, doubleArrayOf(wb[0].toDouble(), ((wb[1] + wb[2]) / 2).toDouble(), wb[3].toDouble()))
        val raw = ShortArray(w * h)
        ByteBuffer.wrap(File(meta.parentFile, meta.nameWithoutExtension + ".u16").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(raw)

        // Superpixel demosaic + white balance + neutral clip (as Finisher.balance does).
        val hw = w / 2
        val hh = h / 2
        val r = FloatArray(hw * hh)
        val g = FloatArray(hw * hh)
        val b = FloatArray(hw * hh)
        val clip = wb.min()
        for (y in 0 until hh) for (x in 0 until hw) {
            val acc = FloatArray(4)
            for (dy in 0..1) for (dx in 0..1) {
                val px = 2 * x + dx
                val py = 2 * y + dy
                val ch = cfa.channelAt(px, py)
                val v = ((raw[py * w + px].toInt() and 0xFFFF) - black[ch]) / (white - black[ch])
                acc[ch] = (v.coerceAtMost(1f) * wb[ch]).coerceAtMost(clip)
            }
            val cr = acc[0]
            val cg = 0.5f * (acc[1] + acc[2])
            val cb = acc[3]
            val i = y * hw + x
            r[i] = (m[0] * cr + m[1] * cg + m[2] * cb).toFloat()
            g[i] = (m[3] * cr + m[4] * cg + m[5] * cb).toFloat()
            b[i] = (m[6] * cr + m[7] * cg + m[8] * cb).toFloat()
        }
        val t0 = System.currentTimeMillis()
        val gain = Rendition.render(r, g, b, hw, hh, style)
        val ms = System.currentTimeMillis() - t0
        val img = BufferedImage(hw, hh, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until hw * hh) {
            fun q(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
            img.setRGB(i % hw, i / hw, (q(r[i]) shl 16) or (q(g[i]) shl 8) or q(b[i]))
        }
        val rotated = rotate(img, (j["orientation"] as Number).toInt())
        ImageIO.write(rotated, "png", File(outDir, meta.nameWithoutExtension + ".png"))
        println("%-28s gain %.2f (%+.1f EV)  %d ms".format(meta.nameWithoutExtension, gain, Math.log(gain.toDouble()) / Math.log(2.0), ms))
    }
}

private fun parseStyle(s: String?): RenderStyle {
    var st = RenderStyle.DAY
    if (s == null || s == "-") return st
    for (kv in s.split(",")) {
        val (k, v) = kv.split("=")
        val f = v.toFloat()
        st = when (k) {
            "key" -> st.copy(key = f)
            "contrast" -> st.copy(contrast = f)
            "toe" -> st.copy(toe = f)
            "hc" -> st.copy(highContrast = f)
            "flare" -> st.copy(flare = f)
            "shoulder" -> st.copy(shoulderStart = f)
            "lift" -> st.copy(liftStrength = f)
            "maxLift" -> st.copy(maxLiftStops = f)
            "sat" -> st.copy(saturation = f)
            "hdesat" -> st.copy(highlightDesat = f)
            "sharpen" -> st.copy(sharpen = f)
            else -> error("unknown style key $k")
        }
    }
    return st
}

private fun rotate(src: BufferedImage, deg: Int): BufferedImage {
    if (deg % 360 == 0) return src
    val w = src.width
    val h = src.height
    val out = if (deg == 180) BufferedImage(w, h, src.type) else BufferedImage(h, w, src.type)
    for (y in 0 until h) for (x in 0 until w) {
        val c = src.getRGB(x, y)
        when (deg) {
            90 -> out.setRGB(h - 1 - y, x, c)
            180 -> out.setRGB(w - 1 - x, h - 1 - y, c)
            270 -> out.setRGB(y, w - 1 - x, c)
        }
    }
    return out
}

/** Minimal JSON reader for the harness's flat metadata files. */
private object Json {
    fun parse(s: String): Map<String, Any?> = P(s).value() as Map<String, Any?>

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); i++ // ':'
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                i++; return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                i++; return l
            }
        }
        fun str(): String {
            i++
            val st = i
            while (s[i] != '"') i++
            return s.substring(st, i++)
        }
        fun num(): Number {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
    }
}
