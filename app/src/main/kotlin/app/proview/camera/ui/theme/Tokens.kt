package app.proview.camera.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.proview.camera.R

/** Colour tokens from docs/UI_SPEC.md §1. */
object Palette {
    val Bg = Color(0xFF0A0A0B)
    val Text1 = Color(0xFFF5F5F7)
    val Text2 = Color(0x9EF5F5F7) // 62%
    val Text3 = Color(0x61F5F5F7) // 38%
    val Line = Color(0x29F5F5F7) // 16%
    val Accent = Color(0xFFF0832A)
    val OnAccent = Color(0xFF17120A)
    val GlassFill = Color(0x6B1C1C1E) // rgba(28,28,30,.42)
    val GlassBorder = Color(0x2EFFFFFF) // rgba(255,255,255,.18)
}

/** Jost carries numerals and instrument labels; Inter carries UI text. Both are variable fonts. */
val Jost = FontFamily(
    Font(R.font.jost, FontWeight.Normal),
    Font(R.font.jost, FontWeight.Medium),
    Font(R.font.jost, FontWeight.SemiBold),
)

val Inter = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
)

/** The eight type roles from docs/UI_SPEC.md §1, at design size. */
object Type {
    val Disp = TextStyle(fontFamily = Jost, fontWeight = FontWeight.SemiBold, fontSize = 64.sp, lineHeight = 57.6.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum")
    val Pre = TextStyle(fontFamily = Jost, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 34.sp)
    val Val = TextStyle(fontFamily = Jost, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
    val Leg = TextStyle(fontFamily = Jost, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 14.sp, letterSpacing = 0.08.em)
    val Ttl = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 15.sp)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 15.6.sp)
    val Cap = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 11.sp, letterSpacing = 0.02.em, fontFeatureSettings = "tnum")
    val Over = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 10.sp, letterSpacing = 0.1.em)

    /** The Library header: Inter 600 34 px, −0.02em. */
    val Header = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 37.4.sp, letterSpacing = (-0.02).em)
    val Button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
}

/**
 * Glass surface. The backdrop blur from the design is added with RenderEffect in M1;
 * until then this is the translucent fallback described in UI_SPEC §7.
 */
fun Modifier.glass(shape: Shape): Modifier =
    clip(shape)
        .background(Palette.GlassFill, shape)
        .border(0.5.dp, Palette.GlassBorder, shape)
