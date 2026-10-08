package com.finc.music

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Design tokens for the minimalist hardware-style UI. */
object Drop {
    val bg = Color(0xFFE3E3E3)
    val panel = Color(0xFFEBEBEB)
    val edgeLo = Color(0xFFD2D2D2)
    val ink = Color(0xFF2A2A2A)
    val dim = Color(0xFF8C8C8C)
    val knob = Color(0xFF2E2E2E)
    val orange = Color(0xFFFF5A1F)
    val green = Color(0xFF6BD36B)
    val dark = Color(0xFF111317)
}

/** One selectable group of songs on the genre dial: all songs, a genre, or a playlist. */
data class Cat(val label: String, val tracks: List<Track>)

fun Modifier.dropPanel(shape: Shape = RoundedCornerShape(6.dp)): Modifier = this
    .shadow(2.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
    .background(Drop.panel, shape)
    .border(1.dp, Brush.verticalGradient(listOf(Color.White, Drop.edgeLo)), shape)

// ---------- 5x7 dot-matrix font ----------
private val GLYPHS: Map<Char, List<String>> = buildMap {
    fun g(c: Char, vararg rows: String) { put(c, rows.toList()) }
    g('A', ".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#")
    g('B', "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####.")
    g('C', ".###.", "#...#", "#....", "#....", "#....", "#...#", ".###.")
    g('D', "####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####.")
    g('E', "#####", "#....", "#....", "####.", "#....", "#....", "#####")
    g('F', "#####", "#....", "#....", "####.", "#....", "#....", "#....")
    g('G', ".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####")
    g('H', "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#")
    g('I', ".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###.")
    g('J', "..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##..")
    g('K', "#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#")
    g('L', "#....", "#....", "#....", "#....", "#....", "#....", "#####")
    g('M', "#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#")
    g('N', "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#")
    g('O', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.")
    g('P', "####.", "#...#", "#...#", "####.", "#....", "#....", "#....")
    g('Q', ".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#")
    g('R', "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#")
    g('S', ".####", "#....", "#....", ".###.", "....#", "....#", "####.")
    g('T', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..")
    g('U', "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.")
    g('V', "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#..")
    g('W', "#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#")
    g('X', "#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#")
    g('Y', "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#..")
    g('Z', "#####", "....#", "...#.", "..#..", ".#...", "#....", "#####")
    g('0', ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.")
    g('1', "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.")
    g('2', ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####")
    g('3', "####.", "....#", "....#", ".###.", "....#", "....#", "####.")
    g('4', "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.")
    g('5', "#####", "#....", "####.", "....#", "....#", "#...#", ".###.")
    g('6', ".###.", "#....", "#....", "####.", "#...#", "#...#", ".###.")
    g('7', "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...")
    g('8', ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.")
    g('9', ".###.", "#...#", "#...#", ".####", "....#", "....#", ".###.")
    g('-', ".....", ".....", ".....", "#####", ".....", ".....", ".....")
    g('.', ".....", ".....", ".....", ".....", ".....", ".##..", ".##..")
    g(',', ".....", ".....", ".....", ".....", ".##..", "..#..", ".#...")
    g('/', "....#", "....#", "...#.", "..#..", ".#...", "#....", "#....")
    g(':', ".....", ".##..", ".##..", ".....", ".##..", ".##..", ".....")
    g('%', "##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##")
    g('+', ".....", "..#..", "..#..", "#####", "..#..", "..#..", ".....")
    g('\'', "..#..", "..#..", ".#...", ".....", ".....", ".....", ".....")
    g('!', "..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#..")
    g('?', ".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#..")
    g('&', ".##..", "#..#.", "#.#..", ".#...", "#.#.#", "#..#.", ".##.#")
    g('<', "...#.", "..#..", ".#...", "#....", ".#...", "..#..", "...#.")
    g('>', ".#...", "..#..", "...#.", "....#", "...#.", "..#..", ".#...")
    g('(', "...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#.")
    g(')', ".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#...")
    g('_', ".....", ".....", ".....", ".....", ".....", ".....", "#####")
}

@Composable
fun DotText(
    text: String, modifier: Modifier = Modifier, pitch: Dp = 2.dp,
    color: Color = Drop.ink, centered: Boolean = false
) {
    val lines = remember(text) { text.uppercase().split("\n") }
    val cols = lines.maxOf { it.length }.coerceAtLeast(1)
    Canvas(modifier.size(pitch * (cols * 6 - 1), pitch * (lines.size * 8 - 1))) {
        val p = pitch.toPx()
        val r = p * 0.4f
        lines.forEachIndexed { li, line ->
            val shift = if (centered) (cols - line.length) * 3f else 0f
            line.forEachIndexed { ci, ch ->
                val g = GLYPHS[ch] ?: return@forEachIndexed
                for (row in 0 until 7) for (col in 0 until 5) {
                    if (g[row][col] == '#') {
                        drawCircle(color, r, Offset((shift + ci * 6 + col + 0.5f) * p, (li * 8 + row + 0.5f) * p))
                    }
                }
            }
        }
    }
}

/** Single-line dot text that shrinks to fit the width available. */
@Composable
fun DotTextFit(text: String, modifier: Modifier = Modifier, maxPitch: Dp = 3.dp, color: Color = Drop.ink) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val cols = text.length.coerceAtLeast(1) * 6 - 1
        val pitch = minOf(maxPitch.value, maxWidth.value / cols).dp
        DotText(text, pitch = pitch, color = color)
    }
}

@Composable
fun DotButton(text: String, modifier: Modifier = Modifier, pitch: Dp = 1.5.dp, color: Color = Drop.ink, onClick: () -> Unit) {
    val h = rememberHaptics()
    Box(modifier.pressable(h, false, onClick).padding(10.dp)) { DotText(text, pitch = pitch, color = color) }
}

/** The small logo mark: a disc and a bar. */
@Composable
fun DotLogo(modifier: Modifier = Modifier, color: Color = Drop.ink) {
    Canvas(modifier.size(18.dp, 20.dp)) {
        val u = size.width / 18f
        drawCircle(color, 6f * u, Offset(6f * u, 12f * u))
        drawRect(color, Offset(14f * u, 2f * u), Size(2.2f * u, 17f * u))
    }
}

/** Small raised pill with a dot-matrix caption underneath. */
@Composable
fun DropPill(label: String, modifier: Modifier = Modifier, strong: Boolean = false, onClick: () -> Unit) {
    val h = rememberHaptics()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp, 22.dp).pressable(h, strong, onClick)
            .shadow(5.dp, CircleShape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
            .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE2E2E2))), CircleShape)
            .border(1.dp, Color.White, CircleShape))
        Spacer(Modifier.height(9.dp))
        DotText(label, pitch = 1.3.dp, color = Drop.dim, centered = true)
    }
}
