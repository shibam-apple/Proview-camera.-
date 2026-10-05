package app.proview.camera.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import app.proview.camera.capture.Steps
import app.proview.camera.photos.PhotoRecord
import app.proview.camera.ui.design.DesignFrame
import app.proview.camera.ui.design.Haptics
import app.proview.camera.ui.design.IconPaths
import app.proview.camera.ui.design.LocalDesign
import app.proview.camera.ui.design.Palette
import app.proview.camera.ui.design.SvgIcon
import app.proview.camera.ui.design.Type
import app.proview.camera.ui.design.glass
import coil.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FILTERS = listOf("All", "RAW", "Faves")

/** Library.dc.html (UI_SPEC §3). */
@Composable
fun LibraryScreen(
    photos: List<PhotoRecord>,
    onOpen: (PhotoRecord) -> Unit,
    onFavourite: (PhotoRecord) -> Unit,
    onCamera: () -> Unit,
    onDeviceCheck: () -> Unit,
) {
    var filter by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    val shown = when (filter) {
        1 -> photos.filter { false } // RAW capture arrives with the burst pipeline (M2).
        2 -> photos.filter { it.favourite }
        else -> photos
    }

    DesignFrame {
        val ds = LocalDesign.current
        Box(Modifier.fillMaxSize()) {
            // Header. Long-press the title for the device check.
            Row(
                Modifier
                    .then(ds.at(0f, 54f, 390f, 56f))
                    .padding(horizontal = ds.d(20)),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "Library",
                    style = ds.text(Type.Header),
                    color = Palette.Text1,
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { onDeviceCheck() }) },
                )
                Text(
                    SimpleDateFormat("d MMM", Locale.US).format(Date()).uppercase(),
                    style = ds.text(Type.Over, 12f).copy(fontWeight = FontWeight.Medium),
                    color = Palette.Text2,
                    modifier = Modifier.padding(bottom = ds.d(6)),
                )
            }

            Box(
                Modifier
                    .then(ds.at(0f, 110f, 390f, 734f))
                    .pointerInput(Unit) {
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onHorizontalDrag = { _, d -> dx += d },
                            onDragEnd = {
                                if (kotlin.math.abs(dx) > ds.d(60).toPx()) {
                                    filter = (filter + if (dx < 0) 1 else -1).coerceIn(0, 2)
                                    Haptics.tick(view)
                                }
                            },
                        )
                    },
            ) {
                if (shown.isEmpty()) {
                    Text(
                        when (filter) {
                            1 -> "No RAW photos yet. RAW arrives with the burst pipeline."
                            2 -> "No favourites yet."
                            else -> "No photos yet. Take one!"
                        },
                        style = ds.text(Type.Body),
                        color = Palette.Text2,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = ds.d(40), start = ds.d(32), end = ds.d(32)),
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = ds.d(12), end = ds.d(12), top = ds.d(16), bottom = ds.d(150)),
                    horizontalArrangement = Arrangement.spacedBy(ds.d(12)),
                    verticalArrangement = Arrangement.spacedBy(ds.d(12)),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(shown, key = { _, p -> p.uri.toString() }) { i, p ->
                        Tile(p, i, onOpen = { onOpen(p) }, onFavourite = { onFavourite(p) })
                    }
                }
            }

            // Bottom bar: segmented filter + camera button.
            Row(
                Modifier
                    .then(ds.at(0f, 754f, 390f, 60f)),
                horizontalArrangement = Arrangement.spacedBy(ds.d(10), Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Segmented(filter, onPick = { Haptics.tick(view); filter = it })
                Box(
                    Modifier
                        .size(ds.d(60))
                        .glass(CircleShape)
                        .pointerInput(Unit) { detectTapGestures(onTap = { Haptics.tap(view); onCamera() }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(ds.d(38)).border(ds.d(3), Color(0xCCF2EFE9), CircleShape))
                    Box(Modifier.size(ds.d(20)).background(Palette.Accent, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun Segmented(selected: Int, onPick: (Int) -> Unit) {
    val ds = LocalDesign.current
    val left by animateFloatAsState(4f + selected * 84f, spring(dampingRatio = 0.55f, stiffness = 420f), label = "seg")
    Box(
        Modifier
            .size(ds.d(260), ds.d(52))
            .glass(RoundedCornerShape(ds.d(30))),
    ) {
        Box(
            Modifier
                .offset(x = ds.d(left), y = ds.d(4))
                .size(ds.d(84), ds.d(44))
                .background(Palette.Accent, RoundedCornerShape(ds.d(26))),
        )
        Row(Modifier.offset(x = ds.d(4), y = ds.d(4))) {
            FILTERS.forEachIndexed { k, label ->
                Box(
                    Modifier
                        .size(ds.d(84), ds.d(44))
                        .clip(RoundedCornerShape(ds.d(26)))
                        .pointerInput(k) { detectTapGestures(onTap = { onPick(k) }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = ds.text(Type.Ttl), color = if (k == selected) Palette.OnAccent else Palette.Text1)
                }
            }
        }
    }
}

@Composable
private fun Tile(p: PhotoRecord, index: Int, onOpen: () -> Unit, onFavourite: () -> Unit) {
    val ds = LocalDesign.current
    val shape = RoundedCornerShape(ds.d(34))
    val pop = remember { Animatable(0.88f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(if (index % 2 == 1) 60 else 0)
        coroutineScope {
            launch { fade.animateTo(1f, tween(300)) }
            pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f))
        }
    }
    val shadow = Shadow(Color(0x66000000), blurRadius = 8f)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(5f / 6f)
            .scale(pop.value)
            .alpha(fade.value)
            .clip(shape)
            .background(Color(0xFF1A1820))
            .border(ds.d(1), Color(0x1AFFFFFF), shape)
            .pointerInput(p.uri) { detectTapGestures(onTap = { onOpen() }) },
    ) {
        AsyncImage(model = p.uri, contentDescription = "Photo ${p.frame}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x47000000), 0.3f to Color(0x00000000),
                        0.52f to Color(0x00000000), 1f to Color(0x99000000),
                    ),
                ),
        )
        Box(
            Modifier
                .padding(ds.d(12))
                .size(ds.d(36))
                .glass(CircleShape)
                .pointerInput(p.uri) { detectTapGestures(onTap = { onFavourite() }) },
            contentAlignment = Alignment.Center,
        ) {
            SvgIcon(IconPaths.HEART, ds.d(17), Palette.Text1, strokeWidth = 2.2f, filled = false)
            if (p.favourite) SvgIcon(IconPaths.HEART, ds.d(17), Palette.Text1, filled = true)
        }
        Text(
            SimpleDateFormat("HH:mm", Locale.US).format(Date(p.takenAt)),
            style = ds.text(Type.Ttl).copy(shadow = shadow),
            color = Palette.Text1,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = ds.d(19), end = ds.d(16)),
        )
        Text(
            p.frame.toString().padStart(4, '0'),
            style = ds.text(TextStyle(fontFamily = Type.Ttl.fontFamily, fontWeight = FontWeight.SemiBold, fontSize = Type.Ttl.fontSize, letterSpacing = (-0.01).em, fontFeatureSettings = "tnum"), 28f)
                .copy(shadow = shadow),
            color = Palette.Text1,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = ds.d(16), bottom = ds.d(34)),
        )
        Text(
            "f/${p.aperture} · 1/${Steps.reciprocal(p.exposureNs.coerceAtLeast(1))}",
            style = ds.text(Type.Over).copy(fontWeight = FontWeight.Medium, letterSpacing = 0.08.em),
            color = Color(0xD9FFFFFF),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = ds.d(16), bottom = ds.d(15)),
        )
    }
}
