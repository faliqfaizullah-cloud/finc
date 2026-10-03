package com.finc.music

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        setContent {
            MaterialTheme {
                WidgetConfigScreen(
                    onApply = { style ->
                        style.save(this)
                        PlayerWidget.refresh(this)
                        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        }
                        finish()
                    },
                    onClose = { finish() })
            }
        }
    }
}

private val swatches = listOf(0xFFFFFFFF, 0xFF111111, 0xFF7C5CFF, 0xFFFF6FB5, 0xFF4F8CFF, 0xFF2CC5B0, 0xFF5CD16B, 0xFFFF9A3D, 0xFFFF4D5E)
    .map { it.toInt() }

@Composable
private fun Label(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Palette.grey, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, h: Haptics, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.pressable(h, false, onClick)
        .background(if (selected) Palette.ink else Color.White.copy(alpha = 0.7f), CircleShape)
        .border(1.dp, Color.White, CircleShape).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (selected) Color.White else Palette.ink)
    }
}

@Composable
private fun Swatch(color: Int?, selected: Boolean, h: Haptics, onClick: () -> Unit) {
    val base = Modifier.size(40.dp).pressable(h, false, onClick)
    Box((if (color == null)
            base.background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), CircleShape)
        else base.background(Color(color), CircleShape))
        .border(if (selected) 3.dp else 1.dp, if (selected) Palette.ink else Color.White, CircleShape),
        contentAlignment = Alignment.Center) {
        if (color == null) Icon(Icons.Rounded.AutoAwesome, "Auto from album art", Modifier.size(20.dp), tint = Color.White)
    }
}

@Composable
fun WidgetConfigScreen(onApply: (WidgetStyle) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val h = rememberHaptics()
    var style by remember { mutableStateOf(WidgetStyle.load(ctx)) }
    var hue by remember { mutableFloatStateOf(260f) }
    val prefs = remember { ctx.getSharedPreferences("finc_widget", Context.MODE_PRIVATE) }
    val title = prefs.getString("title", null) ?: "Today's Hits"
    val artist = prefs.getString("artist", null) ?: "F.INC"
    val art by produceState<Bitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            prefs.getString("uri", null)?.let { runCatching { loadArtBitmap(ctx, Uri.parse(it)) }.getOrNull() } ?: placeholderBitmap()
        }
    }
    val look by produceState<WidgetRenderer.Look?>(null, style, art) {
        val a = art ?: return@produceState
        value = withContext(Dispatchers.Default) { WidgetRenderer.render(a, style, 480, 480, 0.11f * 480f) }
    }
    val sliderColors = SliderDefaults.colors(thumbColor = Palette.ink, activeTrackColor = Palette.ink, inactiveTrackColor = Color(0x22000000))

    Box(Modifier.fillMaxSize()) {
        Aurora()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Widget style", style = Display)
                GlassIconButton(Icons.Rounded.Close, "Close") { onClose() }
            }
            Spacer(Modifier.height(18.dp))

            // live preview, drawn by the same code that draws the real widget
            Box(Modifier.align(Alignment.CenterHorizontally).size(200.dp)) {
                look?.let { l ->
                    val textC = Color(l.text)
                    Image(l.bg.asImageBitmap(), null, Modifier.fillMaxSize())
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            art?.let { Image(it.asImageBitmap(), null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
                            Icon(Icons.Rounded.MusicNote, null, Modifier.size(24.dp), tint = textC)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(title, color = textC, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
                        Text(artist, color = Color(l.sub), fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.clip(CircleShape)
                            .background(if (l.lightText) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.12f))
                            .padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp), tint = textC)
                            Text("Play", color = textC, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), 14.dp, 0.6f).padding(18.dp)) {
                Text("Theme", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Palette.grey, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Original", style.theme == WidgetTheme.ORIGINAL, h, Modifier.weight(1f)) { style = style.copy(theme = WidgetTheme.ORIGINAL) }
                    Chip("iOS 26 Glass", style.theme == WidgetTheme.IOS26, h, Modifier.weight(1f)) { style = style.copy(theme = WidgetTheme.IOS26) }
                    Chip("Gradient Blur", style.theme == WidgetTheme.GRADIENT, h, Modifier.weight(1f)) { style = style.copy(theme = WidgetTheme.GRADIENT) }
                }

                if (style.theme == WidgetTheme.ORIGINAL) {
                    Label("Solid colour")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Swatch(0xFFFFFFFF.toInt(), style.solidWhite, h) { style = style.copy(solidWhite = true) }
                        Swatch(0xFF111111.toInt(), !style.solidWhite, h) { style = style.copy(solidWhite = false) }
                    }
                } else {
                    Label("Colour")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Swatch(null, style.tint == 0, h) { style = style.copy(tint = 0) }
                        swatches.forEach { c -> Swatch(c, style.tint == c, h) { style = style.copy(tint = c) } }
                    }
                    Label("Custom hue")
                    Slider(value = hue, valueRange = 0f..360f, colors = sliderColors,
                        onValueChange = {
                            hue = it
                            style = style.copy(tint = android.graphics.Color.HSVToColor(floatArrayOf(it, 0.55f, 0.95f)))
                        },
                        onValueChangeFinished = { h.click() })
                    Label("Blur opacity  ${(style.opacity * 100).toInt()}%")
                    Slider(value = style.opacity, valueRange = 0.2f..1f, colors = sliderColors,
                        onValueChange = { style = style.copy(opacity = it) },
                        onValueChangeFinished = { h.click() })
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Reset", Modifier.weight(1f)) { style = WidgetStyle() }
                    PillButton("Apply", Modifier.weight(1f), strong = true) { onApply(style) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
