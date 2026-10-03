@file:OptIn(ExperimentalFoundationApi::class)

package com.finc.music

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.abs

private val stripColors = listOf(Color(0xFFC9CBD6), Color(0xFFD3D5DE), Color(0xFFDCDEE6), Color(0xFFE4E6EC))

@Composable
fun HomeScreen(c: Controller, tracks: List<Track>, onAdd: (Track) -> Unit) {
    val h = rememberHaptics()
    val albums = remember(tracks) { tracks.groupBy { it.albumId }.values.toList() }
    val pager = rememberPagerState { albums.size }
    var flow by rememberSaveable { mutableStateOf(false) }
    var lastPage by remember { mutableIntStateOf(0) }
    LaunchedEffect(pager.currentPage) { if (pager.currentPage != lastPage) { h.tick(); lastPage = pager.currentPage } }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 110.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 30.dp, end = 24.dp, top = 16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Home", style = Display)
            GlassIconButton(if (flow) Icons.Rounded.Layers else Icons.Rounded.ViewCarousel, "Switch style") { flow = !flow }
        }
        Spacer(Modifier.height(18.dp))
        if (flow) CoverFlowPager(pager, albums, c, h) else StackPager(pager, albums, c, h)
        Spacer(Modifier.height(14.dp))
        val cur = albums.getOrNull(pager.currentPage)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cur?.firstOrNull()?.album ?: "", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${albums.size} Albums  \u00B7  ${cur?.size ?: 0} songs", color = Palette.grey, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        PlayerPanel(c, onAdd, Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun StackPager(pager: PagerState, albums: List<List<Track>>, c: Controller, h: Haptics) {
    HorizontalPager(pager, Modifier.fillMaxWidth().height(270.dp), contentPadding = PaddingValues(horizontal = 80.dp)) { p ->
        val off = abs((pager.currentPage - p) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize()
            .graphicsLayer { val s = 1f - 0.22f * off; scaleX = s; scaleY = s; alpha = 1f - 0.5f * off }
            .pressable(h) { c.play(albums[p], 0) }) {
            for (k in 3 downTo 0) {
                Box(Modifier.align(Alignment.TopCenter).offset(y = (k * 8).dp).fillMaxWidth(0.92f - k * 0.06f).height(26.dp)
                    .clip(RoundedCornerShape(5.dp)).background(stripColors[k]))
            }
            Box(Modifier.align(Alignment.BottomStart).offset(x = (-18).dp, y = (-45).dp).size(110.dp).background(Color(0xFF1B1B1B), CircleShape))
            Cover(albums[p][0],
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().aspectRatio(1f)
                    .shadow(18.dp, RoundedCornerShape(10.dp), ambientColor = Palette.glowAmbient, spotColor = Palette.glowSpot),
                RoundedCornerShape(10.dp))
        }
    }
}

@Composable
private fun CoverFlowPager(pager: PagerState, albums: List<List<Track>>, c: Controller, h: Haptics) {
    HorizontalPager(pager, Modifier.fillMaxWidth().height(270.dp), contentPadding = PaddingValues(horizontal = 90.dp)) { p ->
        val off = (pager.currentPage - p) + pager.currentPageOffsetFraction
        val a = abs(off).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().zIndex(-abs(off)), contentAlignment = Alignment.Center) {
            Cover(albums[p][0],
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .graphicsLayer {
                        rotationY = -off.coerceIn(-1f, 1f) * 50f
                        cameraDistance = 12f * density
                        translationX = off * size.width * 0.3f
                        val s = 1f - 0.2f * a; scaleX = s; scaleY = s; alpha = 1f - 0.3f * a
                    }
                    .shadow(16.dp, RoundedCornerShape(28.dp), ambientColor = Palette.glowAmbient, spotColor = Palette.glowSpot)
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(28.dp))
                    .pressable(h) { c.play(albums[p], 0) },
                RoundedCornerShape(28.dp))
        }
    }
}

@Composable
private fun Ctl(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, h: Haptics, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).pressable(h, false, onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(26.dp), tint = Palette.ink)
    }
}

@Composable
fun PlayerPanel(c: Controller, onAdd: (Track) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val h = rememberHaptics()
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var showVol by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    val t = c.current
    val dur = (t?.duration ?: 1L).coerceAtLeast(1L)
    val sliderColors = SliderDefaults.colors(thumbColor = Palette.ink, activeTrackColor = Palette.ink, inactiveTrackColor = Color(0x22000000))

    Column(modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp), 18.dp, 0.6f).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(t, Modifier.size(56.dp), RoundedCornerShape(16.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(t?.title ?: "Nothing playing", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Palette.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t?.artist ?: "Pick an album to start", color = Palette.grey, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t != null) GlassIconButton(Icons.Rounded.PlaylistAdd, "Add to playlist", size = 40.dp) { onAdd(t) }
        }
        Slider(value = (c.pos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { c.exo.seekTo((it * dur).toLong()); c.pos = (it * dur).toLong() },
            onValueChangeFinished = { h.click() }, colors = sliderColors)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), Arrangement.SpaceBetween) {
            Text(fmt(c.pos), fontSize = 12.sp, color = Palette.grey)
            Text(fmt(dur), fontSize = 12.sp, color = Palette.grey)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Ctl(Icons.Outlined.ChatBubbleOutline, "Lyrics", h) { showLyrics = true }
            Ctl(Icons.Rounded.SkipPrevious, "Previous", h) { c.prev() }
            Box(Modifier.size(64.dp).pressable(h, true) { c.toggle() }.glass(CircleShape, 12.dp, 0.85f), contentAlignment = Alignment.Center) {
                Icon(if (c.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", Modifier.size(32.dp), tint = Palette.ink)
            }
            Ctl(Icons.Rounded.SkipNext, "Next", h) { c.next() }
            Ctl(Icons.Rounded.QueueMusic, "Queue", h) { showQueue = true }
            Ctl(Icons.Rounded.VolumeUp, "Volume", h) { showVol = !showVol }
        }
        if (showVol) Slider(value = vol, valueRange = 0f..am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat(),
            onValueChange = { vol = it; am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) },
            onValueChangeFinished = { h.tick() }, colors = sliderColors)
    }

    if (showLyrics) AlertDialog(onDismissRequest = { showLyrics = false },
        containerColor = Color(0xFFF4F6FC), shape = RoundedCornerShape(28.dp),
        confirmButton = { TextButton({ showLyrics = false }) { Text("OK") } },
        title = { Text(t?.title ?: "Lyrics") }, text = { Text("No lyrics available for this track yet.") })
    if (showQueue) AlertDialog(onDismissRequest = { showQueue = false },
        containerColor = Color(0xFFF4F6FC), shape = RoundedCornerShape(28.dp),
        confirmButton = { TextButton({ showQueue = false }) { Text("Close") } },
        title = { Text("Up next") }, text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(c.queue) { i, q ->
                    Text(q.title, Modifier.fillMaxWidth().clickable { h.click(); c.exo.seekTo(i, 0); c.exo.play(); showQueue = false }.padding(vertical = 10.dp),
                        fontWeight = if (i == c.index) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        })
}
