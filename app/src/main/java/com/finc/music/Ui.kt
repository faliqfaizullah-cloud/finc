@file:OptIn(ExperimentalFoundationApi::class)

package com.finc.music

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val ink = Color(0xFF111111)
    val grey = Color(0xFF9A9CA8)
    val greyLight = Color(0xFFB7BAC6)
    val bgBottom = Color(0xFFF1F4FB)
    val glowAmbient = Color(0x33707AC8)
    val glowSpot = Color(0x4D707AC8)
}

val Display = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp, color = Palette.ink)

// ---------- Haptics ----------
class Haptics(private val view: View) {
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun click() { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
    fun heavy() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    fun confirm() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val v = LocalView.current
    return remember(v) { Haptics(v) }
}

/** Tap target with a soft press-scale animation and haptic feedback. */
@Composable
fun Modifier.pressable(h: Haptics, strong: Boolean = false, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = src, indication = null) {
            if (strong) h.confirm() else h.click()
            onClick()
        }
}

// ---------- Glassmorphism ----------
fun Modifier.glass(shape: Shape, elevation: Dp = 14.dp, strength: Float = 0.6f): Modifier = this
    .shadow(elevation, shape, ambientColor = Palette.glowAmbient, spotColor = Palette.glowSpot)
    .clip(shape)
    .background(Brush.verticalGradient(listOf(
        Color.White.copy(alpha = (strength + 0.25f).coerceAtMost(1f)),
        Color.White.copy(alpha = (strength - 0.1f).coerceAtLeast(0f)))))
    .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.2f))), shape)

/** Soft lavender background with colour glows so the glass panels have something to frost. */
@Composable
fun Aurora(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(Color(0xFFDDE0F2), Color(0xFFE7E6F5), Color(0xFFDDE3F3))))
        .drawBehind {
            fun blob(c: Color, x: Float, y: Float, r: Float) {
                drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(x, y), radius = r), r, Offset(x, y))
            }
            blob(Color(0x80C8B6F6), size.width * 0.1f, size.height * 0.18f, size.width * 0.8f)
            blob(Color(0x66FBC9E0), size.width * 0.95f, size.height * 0.55f, size.width * 0.7f)
            blob(Color(0x66B9E8E0), size.width * 0.2f, size.height * 0.92f, size.width * 0.8f)
        })
}

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, strong: Boolean = false, onClick: () -> Unit) {
    val h = rememberHaptics()
    Box(modifier.pressable(h, strong, onClick).glass(CircleShape, 10.dp, 0.7f).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Palette.ink, maxLines = 1)
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, desc: String?, modifier: Modifier = Modifier, size: Dp = 44.dp, onClick: () -> Unit) {
    val h = rememberHaptics()
    Box(modifier.size(size).pressable(h, false, onClick).glass(CircleShape, 8.dp, 0.6f), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(size * 0.5f), tint = Palette.ink)
    }
}

// ---------- Bottom navigation (home / planet / bookmark) ----------
@Composable
fun PlanetIcon(selected: Boolean, tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension * 0.28f
        val sw = size.minDimension * 0.075f
        if (selected) drawCircle(tint, r, c) else drawCircle(tint, r, c, style = Stroke(sw))
        rotateDraw(-25f, c) {
            drawOval(tint, topLeft = Offset(c.x - r * 1.7f, c.y - r * 0.45f), size = Size(r * 3.4f, r * 0.9f), style = Stroke(sw))
        }
    }
}

@Composable
private fun NavItem(h: Haptics, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).pressable(h, false, onClick), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun NavBar(tab: Int, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val h = rememberHaptics()
    val on = Palette.ink
    val off = Palette.greyLight
    Row(modifier.fillMaxWidth()
        .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.bgBottom.copy(alpha = 0.95f))))
        .navigationBarsPadding().padding(start = 52.dp, end = 52.dp, top = 30.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        NavItem(h, { onTab(0) }) {
            Icon(if (tab == 0) Icons.Rounded.Home else Icons.Outlined.Home, "Home", Modifier.size(28.dp), tint = if (tab == 0) on else off)
        }
        NavItem(h, { onTab(1) }) { PlanetIcon(tab == 1, if (tab == 1) on else off) }
        NavItem(h, { onTab(2) }) {
            Icon(if (tab == 2) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder, "Playlists", Modifier.size(28.dp), tint = if (tab == 2) on else off)
        }
    }
}

// ---------- Mini player (glass pill with spinning vinyl) ----------
@Composable
fun MiniPill(c: Controller, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val t = c.current ?: return
    val h = rememberHaptics()
    val spin = rememberInfiniteTransition(label = "spin")
    val ang by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "ang")
    Row(modifier.fillMaxWidth(0.88f).glass(CircleShape, 14.dp, 0.65f)
        .clickable { h.click(); onOpen() }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Vinyl(t, Modifier.size(52.dp).rotate(if (c.playing) ang else 0f))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(t.title, fontWeight = FontWeight.ExtraBold, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = Palette.grey, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(44.dp).pressable(h, true) { c.toggle() }, contentAlignment = Alignment.Center) {
            Icon(if (c.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(28.dp), tint = Palette.ink)
        }
    }
}
