package com.finc.music

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Onboarding(onStart: () -> Unit) {
    val big = TextStyle(fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp)
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)
        .glass(RoundedCornerShape(36.dp), 24.dp, 0.55f)) {
        // two vinyl records + iridescent CD
        Canvas(Modifier.fillMaxWidth().height(210.dp).padding(horizontal = 22.dp, vertical = 22.dp).align(Alignment.TopStart)) {
            val r = minOf(size.height / 2f, size.width / 3.5f)
            val step = r * 0.72f
            fun vinyl(cx: Float) {
                drawCircle(Brush.radialGradient(listOf(Color(0xFFF2F1EE), Color(0xFFD8D7D3)), center = Offset(cx, r), radius = r), r, Offset(cx, r))
                for (k in 0..3) drawCircle(Color(0x14000000), r * (0.42f + 0.13f * k), Offset(cx, r), style = Stroke(1.2f))
                drawCircle(Color.White, r * 0.26f, Offset(cx, r))
                drawCircle(Color(0xFFB9B9B9), r * 0.03f, Offset(cx, r))
            }
            vinyl(r)
            vinyl(r + step)
            val cx = r + 2 * step
            drawCircle(Color(0x26000000), r, Offset(cx, r + 8f))
            drawCircle(Brush.sweepGradient(listOf(Color(0xFFBFC4D6), Color(0xFFF3D9E8), Color(0xFFCFE7DD), Color(0xFF8B8F9C), Color(0xFFD9D2F0), Color(0xFFBFC4D6)), Offset(cx, r)), r, Offset(cx, r))
            for (k in 1..6) drawCircle(Color.White.copy(alpha = 0.22f), r * (0.3f + 0.1f * k), Offset(cx, r), style = Stroke(r * 0.04f))
            drawCircle(Color(0xFFEDEEEE), r * 0.3f, Offset(cx, r))
            drawCircle(Color(0xFFBFC3C8), r * 0.12f, Offset(cx, r))
        }
        // translucent cassette fading to the right
        Cassette(Modifier.align(Alignment.CenterEnd).offset(x = 30.dp, y = 10.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(28.dp)) {
            Text("New", style = big, color = Palette.ink)
            Text("Age Of", style = big, color = Color(0xFF9A9CA8))
            Text("Music", style = big, color = Palette.ink)
            Spacer(Modifier.height(30.dp))
            PillButton("Get Started", Modifier.fillMaxWidth().height(58.dp), strong = true, onClick = onStart)
        }
    }
}

@Composable
fun Cassette(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.size(210.dp, 130.dp)
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.horizontalGradient(0.55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
        .clip(shape).background(Color.White.copy(alpha = 0.45f)).border(1.dp, Color.White.copy(alpha = 0.8f), shape)) {
        Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).fillMaxWidth(0.84f).height(30.dp)
            .background(Color.White, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("B", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Palette.ink)
            Text("TYPE I / IEC I NORMAL", fontSize = 7.sp, color = Palette.grey, maxLines = 1)
        }
        Row(Modifier.align(Alignment.Center).offset(y = 8.dp), horizontalArrangement = Arrangement.spacedBy(34.dp)) {
            repeat(2) {
                Canvas(Modifier.size(42.dp)) {
                    val r = size.minDimension / 2f
                    drawCircle(Color(0xFFE6E8EE), r)
                    drawCircle(Color.White, r * 0.62f)
                    for (k in 0 until 8) {
                        val a = k * PI / 4
                        drawCircle(Color(0xFFD0D3DB), 2.4f, Offset(center.x + cos(a).toFloat() * r * 0.8f, center.y + sin(a).toFloat() * r * 0.8f))
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(8.dp).size(26.dp).background(Color.White, CircleShape)
            .border(1.dp, Color(0xFFD9DBE3), CircleShape), contentAlignment = Alignment.Center) {
            Text("90", fontSize = 11.sp, color = Palette.grey)
        }
    }
}
