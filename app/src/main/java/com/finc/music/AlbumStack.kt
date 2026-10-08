package com.finc.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** Flip this sign if the covers lean the wrong way on your device (top toward you is negative). */
private const val TILT = -24f

/**
 * Full-screen vertical stack of album covers in perspective.
 * Drag up/down to flip through; covers smear with a motion-blur trail and every step ticks.
 */
@Composable
fun AlbumStack(albums: List<List<Track>>, onPick: (List<Track>) -> Unit, onClose: () -> Unit) {
    val h = rememberHaptics()
    val scope = rememberCoroutineScope()
    val dens = LocalDensity.current
    var pos by remember { mutableFloatStateOf(0f) }
    var motion by remember { mutableFloatStateOf(0f) }
    var dir by remember { mutableFloatStateOf(1f) }
    var lastIdx by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val maxIdx = (albums.size - 1).coerceAtLeast(0)

    BackHandler { onClose() }
    // the smear fades out when the stack is not moving
    LaunchedEffect(Unit) { while (true) { delay(32); motion *= 0.82f } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wPx = with(dens) { maxWidth.toPx() }
        val hPx = with(dens) { maxHeight.toPx() }
        val stepPx = hPx * 0.16f

        fun setPos(v: Float) {
            pos = v.coerceIn(-0.3f, maxIdx + 0.3f)
            val idx = pos.roundToInt().coerceIn(0, maxIdx)
            if (idx != lastIdx) { lastIdx = idx; h.tick() }
        }

        val base by remember { derivedStateOf { floor(pos).toInt() } }
        val cur by remember { derivedStateOf { pos.roundToInt().coerceIn(0, maxIdx) } }

        Box(Modifier.fillMaxSize()
            .pointerInput(albums.size) {
                detectTapGestures { o ->
                    val top = hPx * 0.511f
                    if (o.y > top && o.y < top + wPx * 0.78f) { h.confirm(); onPick(albums[pos.roundToInt().coerceIn(0, maxIdx)]) }
                }
            }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { dy ->
                    setPos(pos + dy / stepPx)
                    motion = (abs(dy) / 26f).coerceIn(0f, 1f)
                    dir = if (dy >= 0f) 1f else -1f
                },
                onDragStarted = { job?.cancel() },
                onDragStopped = { v ->
                    val target = (pos + v / stepPx * 0.18f).roundToInt().coerceIn(0, maxIdx).toFloat()
                    job = scope.launch {
                        animate(pos, target, initialVelocity = v / stepPx, animationSpec = spring(dampingRatio = 0.75f, stiffness = 180f)) { value, vel ->
                            setPos(value)
                            motion = (abs(vel) * 0.45f).coerceIn(0f, 1f)
                            if (abs(vel) > 0.05f) dir = if (vel >= 0f) 1f else -1f
                        }
                        h.click()
                    }
                }
            )) {
            val lo = (base - 1).coerceAtLeast(0)
            val hi = (base + 8).coerceAtMost(maxIdx)
            for (i in hi downTo lo) {
                key(i) { StackCover(i, albums[i][0], wPx, hPx, { pos }, { motion }, { dir }) }
            }
        }

        // album name under the front cover (dot-matrix, quiet grey)
        DotText(albums.getOrNull(cur)?.firstOrNull()?.album?.take(26) ?: "", Modifier.align(Alignment.TopCenter)
            .offset(y = with(dens) { (hPx * 0.835f).toDp() }), pitch = 1.3.dp, color = Color(0xFF8E8E93))

        // close button
        Box(Modifier.align(Alignment.TopCenter).offset(y = with(dens) { (hPx * 0.876f).toDp() - 22.dp })
            .size(44.dp).pressable(h, true) { onClose() }
            .background(Color(0xFF1C1C1E), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Close", Modifier.size(20.dp), tint = Color.White)
        }
    }
}

@Composable
private fun StackCover(
    i: Int, track: Track, wPx: Float, hPx: Float,
    pos: () -> Float, motion: () -> Float, dir: () -> Float
) {
    val dens = LocalDensity.current
    val baseW = wPx * 0.78f
    val sizeDp = with(dens) { baseW.toDp() }
    val trail by remember { derivedStateOf { motion() > 0.06f } }
    Box(Modifier.offset { IntOffset(((wPx - baseW) / 2f).roundToInt(), 0) }.size(sizeDp)
        .graphicsLayer {
            val d = i - pos()
            val front = hPx * 0.511f
            if (d >= 0f) {
                translationY = front - hPx * 0.533f * (1f - 0.7f.pow(d))
                val s = 0.92f.pow(d)
                scaleX = s; scaleY = s
                alpha = (1f - (d - 4.5f) / 2.5f).coerceIn(0f, 1f)
            } else {
                val t = -d
                translationY = front + t * hPx * 0.45f
                val s = 1f + 0.08f * t
                scaleX = s; scaleY = s
                alpha = (1f - t * 1.2f).coerceIn(0f, 1f)
            }
            rotationX = TILT
            cameraDistance = 3f * baseW
            transformOrigin = TransformOrigin(0.5f, 0f)
        }) {
        // motion-blur trail: faint copies lagging behind the direction of travel
        if (trail) {
            for (k in 3 downTo 1) {
                Cover(track, Modifier.fillMaxSize().graphicsLayer {
                    translationY = -dir() * k * motion() * 22.dp.toPx()
                    alpha = 0.32f * motion() / k
                }, RectangleShape)
            }
        }
        Cover(track, Modifier.fillMaxSize(), RectangleShape)
        // farther covers sit in shadow
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = (0.1f * (i - pos())).coerceIn(0f, 0.6f) }.background(Color.Black))
    }
}
