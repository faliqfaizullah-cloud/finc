@file:OptIn(ExperimentalFoundationApi::class)

package com.finc.music

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.*

// ---------- 1. Splash ----------
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val h = rememberHaptics()
    var finished by remember { mutableStateOf(false) }
    val finish = { if (!finished) { finished = true; onDone() } }
    LaunchedEffect(Unit) { h.click(); delay(1800); finish() }
    Box(Modifier.fillMaxSize().background(Drop.dark).clickable(interactionSource = null, indication = null) { finish() }
        .statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
        Text("f.inc", color = Color(0xFFEDEDED), fontSize = 17.sp, modifier = Modifier.align(Alignment.TopStart))
        DotText("A MINIMALIST\nMUSIC PLAYER\nFOR YOUR LIBRARY\nMODEL: 001.1\nSTUDIO SOUND",
            Modifier.align(Alignment.TopStart).padding(top = 130.dp), pitch = 1.3.dp, color = Color(0xFF9A9CA3))
        Canvas(Modifier.align(Alignment.Center).size(84.dp, 100.dp)) {
            drawCircle(Color(0xFFE4E4E4), 36.dp.toPx(), Offset(36.dp.toPx(), 56.dp.toPx()))
            drawRect(Color(0xFFE4E4E4), Offset(76.dp.toPx(), 4.dp.toPx()), Size(6.dp.toPx(), 92.dp.toPx()))
        }
        DotText("MODEL: 001.1\nINPUT/OUTPUT", Modifier.align(Alignment.BottomStart), pitch = 1.3.dp, color = Color(0xFF9A9CA3))
    }
}

// ---------- 2. Genre list over a soft photo ----------
@Composable
fun IntroScreen(cats: List<Cat>, selected: Int, art: Track?, onPick: (Int) -> Unit) {
    val h = rememberHaptics()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF4F4F4), Color(0xFFC8C8C8))))) {
        ArtBackdrop(art)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
        Text("f.inc", color = Color.White, fontSize = 17.sp,
            modifier = Modifier.statusBarsPadding().padding(24.dp).align(Alignment.TopStart))
        Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 24.dp, bottom = 70.dp)
            .heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            cats.forEachIndexed { i, c ->
                Row(Modifier.pressable(h, false) { onPick(i) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    DotText(c.label.take(20), pitch = 2.2.dp, color = if (i == selected) Color.White else Color(0xCCFFFFFF))
                    if (i == selected) Box(Modifier.padding(start = 12.dp).size(7.dp).background(Drop.orange, CircleShape))
                }
            }
        }
        DotText("SELECT GENRE", Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 24.dp, bottom = 28.dp),
            pitch = 1.3.dp, color = Color(0xAAFFFFFF))
    }
}

// ---------- 3. The device shell ----------
@Composable
private fun BottomPanel(height: Dp = 96.dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(height).dropPanel(), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun DropShell(
    c: Controller, cats: List<Cat>, catIndex: Int, onCatStep: (Int) -> Unit,
    loadedCat: Int, onLoaded: (Int) -> Unit,
    onImport: () -> Unit, onRefresh: () -> Unit, onWidget: () -> Unit,
    onAlbums: () -> Unit, onIntro: () -> Unit, onSave: (Track) -> Unit, onOutput: () -> Unit
) {
    val h = rememberHaptics()
    val pager = rememberPagerState { 4 }
    var lastPage by remember { mutableIntStateOf(0) }
    LaunchedEffect(pager.currentPage) { if (pager.currentPage != lastPage) { h.tick(); lastPage = pager.currentPage } }
    var menu by remember { mutableStateOf(false) }
    val ci = catIndex.coerceIn(0, cats.lastIndex)
    val cat = cats[ci]

    val onToggle: (Boolean) -> Unit = { want ->
        if (want) {
            if (cat.tracks.isNotEmpty()) {
                if (loadedCat != ci || c.queue.isEmpty()) { c.play(cat.tracks, 0); onLoaded(ci) } else c.exo.play()
            }
        } else c.exo.pause()
    }

    Column(Modifier.fillMaxSize().background(Drop.bg).statusBarsPadding().navigationBarsPadding().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // top bar: logo (albums) / name (genre list) / menu
        Row(Modifier.fillMaxWidth().height(44.dp).dropPanel(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(64.dp).fillMaxHeight().pressable(h, false, onAlbums), contentAlignment = Alignment.Center) { DotLogo() }
            Text("f.inc", Modifier.weight(1f).pressable(h, false, onIntro), textAlign = TextAlign.Center, fontSize = 17.sp, color = Drop.ink)
            Box(Modifier.width(64.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.pressable(h, false) { menu = true }.padding(8.dp)) { DotText("MENU", pitch = 1.2.dp, color = Drop.dim) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Albums") }, onClick = { menu = false; onAlbums() })
                    DropdownMenuItem(text = { Text("Genre list") }, onClick = { menu = false; onIntro() })
                    DropdownMenuItem(text = { Text("Import music") }, onClick = { menu = false; onImport() })
                    DropdownMenuItem(text = { Text("Refresh library") }, onClick = { menu = false; onRefresh() })
                    DropdownMenuItem(text = { Text("Widget style") }, onClick = { menu = false; onWidget() })
                }
            }
        }

        // main panel: four pages, motion-blurred while swiping
        Box(Modifier.weight(1f).fillMaxWidth().dropPanel()) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val off = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    val a = abs(off).coerceIn(0f, 1f)
                    alpha = 1f - 0.6f * a
                    renderEffect = if (Build.VERSION.SDK_INT >= 31 && a > 0.02f) BlurEffect(a * 42f, 0.01f, TileMode.Decal) else null
                }) {
                    when (page) {
                        0 -> GenrePage(cats, ci, onCatStep, h)
                        1 -> TempoPage(c.speed, { c.setTempo(it) }, h)
                        2 -> DropPage(c, cat, onToggle, h)
                        else -> OutputPage(c, h)
                    }
                }
            }
            DotText("0${pager.currentPage + 1}/04", Modifier.align(Alignment.TopEnd).padding(14.dp), pitch = 1.2.dp, color = Drop.dim)
        }

        // bottom control panel(s)
        Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (pager.currentPage) {
                0 -> BottomPanel { DropPill("INPUT", onClick = onImport) }
                1 -> BottomPanel { DropPill("RESET", strong = true) { c.setTempo(1f) } }
                2 -> BottomPanel { DropPill("NEXT") { c.next() } }
                else -> {
                    BottomPanel(92.dp) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            DropPill("REMIX\nSHUFFLE", strong = true) {
                                if (cat.tracks.isNotEmpty()) { c.play(cat.tracks.shuffled(), 0); onLoaded(ci) }
                            }
                            DropPill("SAVE\nPLAYLIST") { c.current?.let(onSave) }
                        }
                    }
                    BottomPanel(92.dp) { DropPill("OUTPUT\nVOLUME", onClick = onOutput) }
                }
            }
        }
    }
}

// ---------- page 1: genre dial ----------
@Composable
private fun GenrePage(cats: List<Cat>, index: Int, onStep: (Int) -> Unit, h: Haptics) {
    val cat = cats[index]
    val scope = rememberCoroutineScope()
    val spin = remember { Animatable(0f) }
    val step: (Int) -> Unit = { d ->
        onStep(d)
        scope.launch { spin.snapTo(1f); spin.animateTo(0f, tween(380)) }
    }
    Box(Modifier.fillMaxSize().padding(14.dp)) {
        DotText("F.INC\nGENRE/\nSELECT", Modifier.align(Alignment.TopStart), pitch = 1.2.dp, color = Drop.dim)
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.graphicsLayer {
                renderEffect = if (Build.VERSION.SDK_INT >= 31 && spin.value > 0.05f) BlurEffect(spin.value * 16f, 0.01f, TileMode.Decal) else null
                alpha = 1f - 0.35f * spin.value
            }) { DotTextFit(cat.label.take(16), maxPitch = 2.6.dp) }
            Spacer(Modifier.height(34.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.pressable(h, false) { step(-1) }.padding(14.dp)) { DotText("<", pitch = 2.dp, color = Drop.dim) }
                Dial(step, h)
                Box(Modifier.pressable(h, false) { step(1) }.padding(14.dp)) { DotText(">", pitch = 2.dp, color = Drop.dim) }
            }
            Spacer(Modifier.height(18.dp))
            DotText(if (cat.tracks.isEmpty()) "NO MUSIC" else "${cat.tracks.size} SONGS", pitch = 1.3.dp, color = Drop.dim)
        }
    }
}

@Composable
private fun Dial(onStepIn: (Int) -> Unit, h: Haptics) {
    val onStep by rememberUpdatedState(onStepIn)
    val scope = rememberCoroutineScope()
    var kx by remember { mutableFloatStateOf(0f) }
    var ky by remember { mutableFloatStateOf(0f) }
    var lastAngle by remember { mutableFloatStateOf(Float.NaN) }
    var acc by remember { mutableFloatStateOf(0f) }
    val springBack: () -> Unit = {
        val sx = kx; val sy = ky
        scope.launch {
            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f)) { f, _ -> kx = sx * (1f - f); ky = sy * (1f - f) }
        }
    }
    Box(Modifier.size(150.dp).pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { lastAngle = Float.NaN; acc = 0f },
            onDragEnd = { springBack() },
            onDragCancel = { springBack() },
            onDrag = { change, _ ->
                val center = Offset(size.width / 2f, size.height / 2f)
                val v = change.position - center
                val rad = size.width / 2f
                val dist = v.getDistance()
                val lim = rad * 0.62f
                val s = if (dist > lim) lim / dist else 1f
                kx = v.x * s; ky = v.y * s
                if (dist > rad * 0.2f) {
                    val ang = Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()
                    if (!lastAngle.isNaN()) {
                        var dA = ang - lastAngle
                        while (dA > 180f) dA -= 360f
                        while (dA < -180f) dA += 360f
                        acc += dA
                        while (acc >= 30f) { acc -= 30f; onStep(1); h.tick() }
                        while (acc <= -30f) { acc += 30f; onStep(-1); h.tick() }
                    }
                    lastAngle = ang
                }
            })
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f - 8.dp.toPx()
            drawCircle(Color(0xFFE0E0E0), r, center, style = Stroke(10.dp.toPx()))
            drawCircle(Color(0xFFD3D3D3), r - 5.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White, r + 5.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        }
        Box(Modifier.offset { IntOffset(kx.roundToInt(), ky.roundToInt()) }.size(22.dp)
            .shadow(6.dp, CircleShape, ambientColor = Color(0x55000000), spotColor = Color(0x55000000))
            .background(Drop.knob, CircleShape))
    }
}

// ---------- page 2: tempo ----------
@Composable
private fun TempoPage(speed: Float, onSpeed: (Float) -> Unit, h: Haptics) {
    Box(Modifier.fillMaxSize().padding(14.dp)) {
        DotText("F.INC\nTEMPO/\nSELECT", Modifier.align(Alignment.TopStart), pitch = 1.2.dp, color = Drop.dim)
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            DotText(String.format(Locale.US, "%.2fX", speed), pitch = 2.6.dp)
            Spacer(Modifier.height(34.dp))
            VSlider(speed, onSpeed, h)
        }
    }
}

@Composable
private fun VSlider(speed: Float, onSpeed: (Float) -> Unit, h: Haptics) {
    val update by rememberUpdatedState(onSpeed)
    var last by remember { mutableFloatStateOf(speed) }
    val apply: (Float, Float, Float) -> Unit = { y, hPx, pad ->
        val f = ((y - pad) / (hPx - 2 * pad)).coerceIn(0f, 1f)
        val s = (((1.5f - f) * 20f).roundToInt() / 20f).coerceIn(0.5f, 1.5f)
        if (s != last) { last = s; h.tick(); update(s) }
    }
    Box(Modifier.width(52.dp).height(190.dp)
        .pointerInput(Unit) { detectTapGestures { apply(it.y, size.height.toFloat(), 14.dp.toPx()) } }
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { apply(it.y, size.height.toFloat(), 14.dp.toPx()) }) { change, _ ->
                apply(change.position.y, size.height.toFloat(), 14.dp.toPx())
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 14.dp.toPx()
            val tw = 8.dp.toPx()
            drawRoundRect(Color(0xFFDEDEDE), Offset(center.x - tw / 2, pad), Size(tw, size.height - 2 * pad), CornerRadius(tw / 2, tw / 2))
            val f = (1.5f - speed).coerceIn(0f, 1f)
            val y = pad + f * (size.height - 2 * pad)
            drawCircle(Color(0x44000000), 12.dp.toPx(), Offset(center.x, y + 2.dp.toPx()))
            drawCircle(Drop.knob, 11.dp.toPx(), Offset(center.x, y))
        }
    }
}

// ---------- page 3: drop switch ----------
@Composable
private fun DropPage(c: Controller, cat: Cat, onToggle: (Boolean) -> Unit, h: Haptics) {
    Box(Modifier.fillMaxSize().padding(14.dp)) {
        DotText("F.INC\n${cat.label.take(14)}\n${String.format(Locale.US, "%.2fX", c.speed)}",
            Modifier.align(Alignment.TopStart), pitch = 1.2.dp, color = Drop.dim)
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            DotText(if (c.playing) "DROPPING" else "DROP A SONG", pitch = 2.2.dp)
            Spacer(Modifier.height(40.dp))
            DropSwitch(c.playing, cat.tracks.isNotEmpty(), onToggle, h)
        }
    }
}

@Composable
private fun DropSwitch(on: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit, h: Haptics) {
    val target = if (on) 1f else 0f
    val pos by animateFloatAsState(target, spring(dampingRatio = 0.65f, stiffness = 260f), label = "switch")
    val moving = abs(target - pos).coerceIn(0f, 1f)
    val dir = if (on) 1f else -1f
    val travel = 52.dp
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.width(44.dp).height(96.dp)
        .pressable(h, true) { if (enabled) onToggle(!on) }
        .shadow(6.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
        .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE9E9E9))), shape)
        .pointerInput(on, enabled) {
            var acc = 0f
            detectVerticalDragGestures(onDragStart = { acc = 0f }) { _, dy ->
                acc += dy
                if (enabled && !on && acc > 18f) { h.confirm(); onToggle(true); acc = 0f }
                if (enabled && on && acc < -18f) { h.confirm(); onToggle(false); acc = 0f }
            }
        }) {
        // motion-blur trail behind the knob
        for (k in 3 downTo 1) {
            Box(Modifier.align(Alignment.TopCenter).offset(y = 6.dp + travel * (pos - dir * 0.08f * k))
                .size(32.dp).graphicsLayer { alpha = 0.28f * moving / k }.background(Drop.orange, CircleShape))
        }
        Box(Modifier.align(Alignment.TopCenter).offset(y = 6.dp + travel * pos).size(32.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }.background(Drop.orange, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(if (on) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, Modifier.size(16.dp), tint = Color.White)
        }
    }
}

// ---------- page 4: output ----------
@Composable
private fun OutputPage(c: Controller, h: Haptics) {
    val t = c.current
    val dur = (t?.duration ?: 1L).coerceAtLeast(1L)
    BoxWithConstraints(Modifier.fillMaxSize().padding(14.dp)) {
        DotText("F.INC\n${(t?.title ?: "NO TRACK").take(20)}\n${(t?.artist ?: "").take(20)}",
            Modifier.align(Alignment.TopStart), pitch = 1.2.dp, color = Drop.dim)
        Box(Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 2.dp).size(8.dp)
            .background(if (c.playing) Drop.green else Color(0xFFBDBDBD), CircleShape))
        val sphere = minOf(maxWidth * 0.78f, (maxHeight - 150.dp).coerceAtLeast(80.dp))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            DotSphere(c.playing, Modifier.size(sphere))
            Spacer(Modifier.height(14.dp))
            DotProgress((c.pos.toFloat() / dur).coerceIn(0f, 1f), { c.exo.seekTo((it * dur).toLong()); c.pos = (it * dur).toLong() }, h,
                Modifier.fillMaxWidth(0.86f))
            Spacer(Modifier.height(6.dp))
            DotText("${fmt(c.pos)} / ${fmt(dur)}", pitch = 1.2.dp, color = Drop.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DotButton("PREV") { c.prev() }
                DotButton(if (c.playing) "PAUSE" else "PLAY") { c.toggle() }
                DotButton("NEXT") { c.next() }
            }
        }
    }
}

@Composable
private fun DotSphere(playing: Boolean, modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "sphere").animateFloat(0f, 6.2831855f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "t")
    val amp by animateFloatAsState(if (playing) 1f else 0f, tween(600), label = "amp")
    Canvas(modifier) {
        val big = size.minDimension / 2f
        val n = 150
        val base = big * 1.77f / sqrt(n.toFloat()) * 0.33f
        for (i in 0 until n) {
            val rr = (big - base) * sqrt((i + 0.5f) / n)
            val a = i * 2.3999632f
            val x = center.x + rr * cos(a)
            val y = center.y + rr * sin(a)
            val wave = 0.5f + 0.5f * sin(t * 2f - (rr / big) * 7f + a * 0.35f)
            val beat = 0.5f + 0.5f * sin(t * 3f)
            drawCircle(Drop.ink, base * (0.7f + amp * 0.55f * wave * (0.6f + 0.4f * beat)), Offset(x, y))
        }
    }
}

@Composable
private fun DotProgress(frac: Float, onSeekIn: (Float) -> Unit, h: Haptics, modifier: Modifier) {
    val onSeek by rememberUpdatedState(onSeekIn)
    var lastLit by remember { mutableIntStateOf(-1) }
    val seek: (Float) -> Unit = { f ->
        val ff = f.coerceIn(0f, 1f)
        val lit = (ff * 40).toInt()
        if (lit != lastLit) { lastLit = lit; h.tick() }
        onSeek(ff)
    }
    Canvas(modifier.height(20.dp)
        .pointerInput(Unit) { detectTapGestures { seek(it.x / size.width) } }
        .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> seek(change.position.x / size.width) } }) {
        val n = 40
        val gap = size.width / n
        val lit = (frac * n).toInt()
        for (i in 0 until n) {
            drawCircle(if (i < lit) Drop.ink else Color(0xFFCDCDCD), 2.dp.toPx(), Offset(gap * (i + 0.5f), size.height / 2f))
        }
    }
}
