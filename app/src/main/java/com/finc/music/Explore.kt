@file:OptIn(ExperimentalFoundationApi::class)

package com.finc.music

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun ExploreScreen(c: Controller, tracks: List<Track>, onImport: () -> Unit, onRefresh: () -> Unit, onWidget: () -> Unit, onAdd: (Track) -> Unit) {
    val h = rememberHaptics()
    val st = rememberLazyListState()
    val focus by remember {
        derivedStateOf {
            val li = st.layoutInfo
            val mid = (li.viewportStartOffset + li.viewportEndOffset) / 2
            li.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: 0
        }
    }
    var last by remember { mutableIntStateOf(0) }
    LaunchedEffect(focus) { if (focus != last) { h.tick(); last = focus } }
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Explore", style = Display)
            Box {
                Icon(Icons.Rounded.MoreHoriz, "Menu", Modifier.size(32.dp).pressable(h) { menu = true }, tint = Palette.ink)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Import music") }, onClick = { menu = false; onImport() })
                    DropdownMenuItem(text = { Text("Refresh library") }, onClick = { menu = false; onRefresh() })
                    DropdownMenuItem(text = { Text("Widget style") }, onClick = { menu = false; onWidget() })
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val pad = ((maxHeight - 100.dp) / 2).coerceAtLeast(0.dp)
            LazyColumn(state = st, flingBehavior = rememberSnapFlingBehavior(st),
                contentPadding = PaddingValues(top = pad, bottom = pad + 60.dp)) {
                itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                    ExploreRow(c, tracks, i, t, i == focus, st, h, onAdd)
                }
            }
        }
    }
}

@Composable
private fun ExploreRow(c: Controller, tracks: List<Track>, i: Int, t: Track, sel: Boolean, st: androidx.compose.foundation.lazy.LazyListState, h: Haptics, onAdd: (Track) -> Unit) {
    val isCur = c.current?.id == t.id
    val titleStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp)
    Row(
        Modifier.fillMaxWidth().height(100.dp)
            .graphicsLayer {
                val info = st.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == i }
                if (item != null) {
                    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                    val d = (((item.offset + item.size / 2f) - mid) / (info.viewportSize.height / 2f)).coerceIn(-1.5f, 1.5f)
                    transformOrigin = TransformOrigin(0.1f, 0.5f)
                    translationX = d * d * 80.dp.toPx()
                    rotationZ = -d * 16f
                    alpha = (1f - abs(d) * 0.65f).coerceAtLeast(0.12f)
                }
            }
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .then(if (sel) Modifier.glass(CircleShape, 10.dp, 0.55f) else Modifier)
            .combinedClickable(onClick = { h.click(); c.play(tracks, i) }, onLongClick = { h.heavy(); onAdd(t) })
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(76.dp).shadow(8.dp, CircleShape, ambientColor = Palette.glowAmbient, spotColor = Palette.glowSpot)
            .border(2.dp, Color.White, CircleShape).padding(2.dp)) {
            Cover(t, Modifier.fillMaxSize(), CircleShape)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(t.title, style = titleStyle, color = if (sel) Palette.ink else Color(0xFF3A3C48), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = Palette.grey, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (sel) PillButton(if (isCur && c.playing) "Pause" else "Play", strong = true) {
            if (isCur) c.toggle() else c.play(tracks, i)
        }
    }
}
