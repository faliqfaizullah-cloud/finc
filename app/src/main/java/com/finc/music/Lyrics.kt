package com.finc.music

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.abs

data class LyricLine(val ms: Long, val text: String)

class Lyrics(val status: Int, val lines: List<LyricLine>, val plain: List<String>, val synced: Boolean) {
    companion object {
        const val FOUND = 0
        const val NOT_FOUND = 1
        const val ERROR = 2
    }
}

/**
 * Looks lyrics up on LRCLIB (free, open lyrics database) by title / artist / album / length,
 * and keeps what it finds on the phone so each song is only looked up once.
 */
object LyricsRepo {
    private val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    private fun key(t: Track) =
        UUID.nameUUIDFromBytes("${t.title}|${t.artist}|${t.album}|${t.duration / 1000}".toByteArray()).toString()

    private fun clean(s: String) = if (s.equals("Unknown", true) || s.startsWith("<")) "" else s

    fun parseLrc(text: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in text.lines()) {
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val body = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
                out += LyricLine((min * 60 + sec) * 1000 + ms, body)
            }
        }
        return out.sortedBy { it.ms }
    }

    private fun fromJson(o: JSONObject): Lyrics {
        if (o.optBoolean("none")) return Lyrics(Lyrics.NOT_FOUND, emptyList(), emptyList(), false)
        val synced = o.optString("synced", "")
        if (synced.isNotBlank()) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics(Lyrics.FOUND, lines, emptyList(), true)
        }
        val plain = o.optString("plain", "")
        if (plain.isNotBlank()) return Lyrics(Lyrics.FOUND, emptyList(), plain.lines(), false)
        if (o.optBoolean("instrumental")) return Lyrics(Lyrics.FOUND, emptyList(), listOf("INSTRUMENTAL"), false)
        return Lyrics(Lyrics.NOT_FOUND, emptyList(), emptyList(), false)
    }

    private fun http(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "F.INC Android music player")
        return try {
            when (c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("HTTP ${c.responseCode}")
            }
        } finally { c.disconnect() }
    }

    /** Returns the matching LRCLIB record, or null when there is none. Throws on network trouble. */
    private fun fetch(t: Track): JSONObject? {
        fun q(s: String) = URLEncoder.encode(s, "UTF-8")
        val artist = clean(t.artist)
        val sec = (t.duration / 1000).toInt()
        if (artist.isNotEmpty()) {
            http("https://lrclib.net/api/get?artist_name=${q(artist)}&track_name=${q(t.title)}&album_name=${q(clean(t.album))}&duration=$sec")
                ?.let { return JSONObject(it) }
        }
        val url = "https://lrclib.net/api/search?track_name=${q(t.title)}" + if (artist.isNotEmpty()) "&artist_name=${q(artist)}" else ""
        val arr = http(url)?.let { JSONArray(it) } ?: return null
        var best: JSONObject? = null
        var bestD = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = abs(o.optDouble("duration", 0.0).toInt() - sec)
            if (d < bestD) { bestD = d; best = o }
        }
        return best
    }

    suspend fun load(ctx: Context, t: Track, force: Boolean = false): Lyrics = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "lyrics").apply { mkdirs() }
        val f = File(dir, key(t) + ".json")
        if (!force && f.exists()) {
            val cached = runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
            if (cached != null) return@withContext cached
        }
        try {
            val rec = fetch(t)
            val stored = JSONObject()
            if (rec == null) stored.put("none", true)
            else {
                stored.put("synced", rec.optString("syncedLyrics", ""))
                stored.put("plain", rec.optString("plainLyrics", ""))
                stored.put("instrumental", rec.optBoolean("instrumental"))
            }
            runCatching { f.writeText(stored.toString()) }
            fromJson(stored)
        } catch (e: Exception) {
            Lyrics(Lyrics.ERROR, emptyList(), emptyList(), false)
        }
    }
}

/** Page 5: time-synced lyrics that follow the song (tap a line to jump there), or plain lyrics. */
@Composable
fun LyricsPage(c: Controller, reload: Int, h: Haptics) {
    val ctx = LocalContext.current
    val t = c.current
    val handled = remember { mutableIntStateOf(reload) }
    val lyr by produceState<Lyrics?>(null, t?.uri, reload) {
        val force = reload != handled.intValue
        handled.intValue = reload
        value = null
        value = if (t == null) null else LyricsRepo.load(ctx, t, force)
    }
    val pos by produceState(0L) { while (true) { value = c.exo.currentPosition; delay(120) } }
    val l = lyr
    val st = rememberLazyListState()
    val cur by remember(l) { derivedStateOf { if (l != null && l.synced) l.lines.indexOfLast { it.ms <= pos + 250 } else -1 } }
    LaunchedEffect(cur) { if (cur >= 0) st.animateScrollToItem((cur - 2).coerceAtLeast(0)) }

    val tag = when {
        t == null -> "NO TRACK"
        l == null -> "LOADING..."
        l.status == Lyrics.ERROR -> "OFFLINE"
        l.status == Lyrics.NOT_FOUND -> "NOT FOUND"
        l.synced -> "SYNCED"
        else -> "PLAIN"
    }
    Box(Modifier.fillMaxSize().padding(14.dp)) {
        DotText("F.INC\nLYRICS\n$tag", Modifier.align(Alignment.TopStart), pitch = 1.2.dp, color = Drop.dim)
        when {
            t == null -> DotText("PLAY A SONG\nFIRST", Modifier.align(Alignment.Center), pitch = 1.8.dp, color = Drop.dim, centered = true)
            l == null -> DotText("LOADING...", Modifier.align(Alignment.Center), pitch = 1.8.dp, color = Drop.dim)
            l.status == Lyrics.ERROR -> DotText("CAN'T REACH\nLYRICS SERVER", Modifier.align(Alignment.Center), pitch = 1.8.dp, color = Drop.dim, centered = true)
            l.status == Lyrics.NOT_FOUND -> DotText("NO LYRICS\nFOR THIS SONG", Modifier.align(Alignment.Center), pitch = 1.8.dp, color = Drop.dim, centered = true)
            else -> LazyColumn(Modifier.fillMaxSize().padding(top = 56.dp), state = st,
                contentPadding = PaddingValues(bottom = 120.dp)) {
                if (l.synced) {
                    itemsIndexed(l.lines) { i, line ->
                        Text(line.text.ifBlank { "\u266A" }, Modifier.fillMaxWidth()
                            .clickable { h.click(); c.exo.seekTo(line.ms) }.padding(vertical = 7.dp),
                            textAlign = TextAlign.Center, fontSize = if (i == cur) 19.sp else 16.sp, lineHeight = 24.sp,
                            fontWeight = if (i == cur) FontWeight.ExtraBold else FontWeight.Normal,
                            color = if (i == cur) Drop.ink else Color(0xFFA5A5A5))
                    }
                } else {
                    itemsIndexed(l.plain) { _, line ->
                        Text(line.ifBlank { " " }, Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            textAlign = TextAlign.Center, fontSize = 16.sp, lineHeight = 22.sp, color = Drop.ink)
                    }
                }
            }
        }
    }
}
