package app.proview.pipeline.tools

import app.proview.pipeline.enhance.EnhanceParams
import app.proview.pipeline.enhance.SingleImageEnhancer
import app.proview.pipeline.look.Look
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop harness for tuning the Lab: `./gradlew enhanceSamples -Pin=<dir> -Pout=<dir> [-Plook=NATURAL]`.
 * Writes <name>_after.png for every image in the input folder and prints the metrics.
 */
fun main(args: Array<String>) {
    val inDir = File(args[0])
    val outDir = File(args[1]).apply { mkdirs() }
    val look = args.getOrNull(2)?.let { Look.valueOf(it) } ?: Look.NATURAL
    val strengths = args.getOrNull(3)?.takeIf { it != "-" }?.split(",")?.map { it.toFloat() }
    val params = if (strengths != null) EnhanceParams(strengths[0], strengths[1], strengths[2], look) else EnhanceParams(look = look)
    val only = args.getOrNull(4)
    val files = inDir.listFiles { f -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") && (only == null || f.name.startsWith(only)) }!!.sortedBy { it.name }
    for (f in files) {
        val img = ImageIO.read(f) ?: continue
        val w = img.width
        val h = img.height
        val argb = img.getRGB(0, 0, w, h, null, 0, w)
        val (out, m) = SingleImageEnhancer.enhance(argb, w, h, params)
        val o = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        o.setRGB(0, 0, w, h, out, 0, w)
        ImageIO.write(o, "png", File(outDir, f.nameWithoutExtension + "_after.png"))
        println("%-26s %4dx%-4d noise %.4f->%.4f (%+.0f%%) shadows %.3f->%.3f clipped %.3f->%.3f contrast %.4f->%.4f %d ms".format(
            f.name, w, h, m.noiseBefore, m.noiseAfter, -m.noiseReduction * 100, m.shadowsBefore, m.shadowsAfter,
            m.clippedBefore, m.clippedAfter, m.localContrastBefore, m.localContrastAfter, m.millis))
    }
}
