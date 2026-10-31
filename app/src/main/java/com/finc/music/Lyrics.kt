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
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.UUID
import kotlin.math.abs

data class LyricLine(val ms: Long, val text: String)

class Lyrics(
    val status: Int, val lines: List<LyricLine>, val plain: List<String>, val synced: Boolean,
    val reason: String = ""
) {
    companion object {
        const val FOUND = 0
        const val NOT_FOUND = 1
        const val ERROR = 2
    }
}

/**
 * Looks lyrics up on LRCLIB (free, open lyrics database), falling back to lyrics.ovh,
 * and keeps what it finds on the phone so each song is only looked up once.
 */
object LyricsRepo {
    private val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val noise = Regex("""\s*[(\[][^)\]]*(feat|ft\.|remaster|live|version|edit|mix|from|bonus|deluxe)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val tail = Regex("""\s+-\s+(remaster|live|single|radio|bonus|deluxe|mono|stereo).*""", RegexOption.IGNORE_CASE)

    private class Resp(val code: Int, val body: String)

    private fun key(t: Track) =
        UUID.nameUUIDFromBytes("${t.title}|${t.artist}|${t.album}|${t.duration / 1000}".toByteArray()).toString()

    private fun clean(s: String) =
        if (s.isBlank() || s.equals("Unknown", true) || s.startsWith("<")) "" else s.trim()

    private fun baseTitle(s: String) = s.replace(noise, "").replace(tail, "").trim().ifEmpty { s.trim() }

    /** JSON null must read as empty, not as the text "null". */
    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")

    private fun hasLyrics(o: JSONObject) =
        str(o, "syncedLyrics").isNotBlank() || str(o, "plainLyrics").isNotBlank() || o.optBoolean("instrumental")

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
        val synced = str(o, "synced")
        if (synced.isNotBlank()) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics(Lyrics.FOUND, lines, emptyList(), true)
        }
        val plain = str(o, "plain")
        if (plain.isNotBlank()) return Lyrics(Lyrics.FOUND, emptyList(), plain.lines(), false)
        if (o.optBoolean("instrumental")) return Lyrics(Lyrics.FOUND, emptyList(), listOf("INSTRUMENTAL"), false)
        return Lyrics(Lyrics.NOT_FOUND, emptyList(), emptyList(), false)
    }

    private fun http(url: String): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", "F.INC Android music player (v2)")
        c.setRequestProperty("Accept", "application/json")
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            Resp(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }

    /**
     * Body of a successful reply, or null for "no match here" (404, 400 ...) so the next lookup is tried.
     * Only real trouble (no connection, server down, rate limit) throws.
     */
    private fun get(url: String): String? {
        val r = http(url)
        return when {
            r.code in 200..299 -> r.body
            r.code == 429 || r.code >= 500 -> throw IOException("SERVER " + r.code)
            else -> null
        }
    }

    /** Returns a record with syncedLyrics / plainLyrics / instrumental, or null when nothing matches. */
    private fun fetch(t: Track): JSONObject? {
        fun q(s: String) = URLEncoder.encode(s, "UTF-8")
        val artist = clean(t.artist)
        val album = clean(t.album)
        val title = baseTitle(t.title)
        val sec = (t.duration / 1000).toInt()

        // 1. exact lookup (LRCLIB needs all four details)
        if (artist.isNotEmpty() && album.isNotEmpty()) {
            val body = get("https://lrclib.net/api/get?artist_name=${q(artist)}&track_name=${q(t.title.trim())}&album_name=${q(album)}&duration=$sec")
            val o = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (o != null && hasLyrics(o)) return o
        }

        // 2. searches, most precise first
        val searches = buildList {
            if (artist.isNotEmpty()) add("track_name=${q(title)}&artist_name=${q(artist)}")
            add("q=${q((artist + " " + title).trim())}")
            add("q=${q(title)}")
        }
        for (s in searches) {
            val arr = get("https://lrclib.net/api/search?$s")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: continue
            var best: JSONObject? = null
            var bestD = Int.MAX_VALUE
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!hasLyrics(o)) continue
                val d = abs(o.optDouble("duration", 0.0).toInt() - sec)
                if (d < bestD) { bestD = d; best = o }
            }
            if (best != null) return best
        }

        // 3. last resort: plain lyrics from lyrics.ovh
        if (artist.isNotEmpty()) {
            val body = get("https://api.lyrics.ovh/v1/${q(artist).replace("+", "%20")}/${q(title).replace("+", "%20")}")
            val text = body?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { str(it, "lyrics") }.orEmpty()
            if (text.isNotBlank()) return JSONObject().put("plainLyrics", text.replace("\r\n", "\n"))
        }
        return null
    }

    private fun reasonOf(e: Exception): String = when (e) {
        is UnknownHostException -> "NO INTERNET"
        is SocketTimeoutException -> "TIMED OUT"
        is SSLException -> "SECURE LINK FAILED"
        is SecurityException -> "NO INTERNET PERMISSION"
        is IOException -> (e.message ?: "NETWORK ERROR").take(24).uppercase()
        else -> e.javaClass.simpleName.take(24).uppercase()
    }

    suspend fun load(ctx: Context, t: Track, force: Boolean = false): Lyrics = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "lyrics").apply { mkdirs() }
        val f = File(dir, key(t) + ".json")
        if (!force && f.exists()) {
            val cached = runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
            if (cached != null) return@withContext cached
        }
        var last: Exception? = null
        for (attempt in 1..2) {
            try {
                val rec = fetch(t)
                val stored = JSONObject()
                if (rec == null) stored.put("none", true)
                else {
                    stored.put("synced", str(rec, "syncedLyrics"))
                    stored.put("plain", str(rec, "plainLyrics"))
                    stored.put("instrumental", rec.optBoolean("instrumental"))
                }
                runCatching { f.writeText(stored.toString()) }
                return@withContext fromJson(stored)
            } catch (e: Exception) {
                last = e
            }
        }
        Lyrics(Lyrics.ERROR, emptyList(), emptyList(), false, last?.let { reasonOf(it) } ?: "ERROR")
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
        l.status == Lyrics.ERROR -> "ERROR"
        l.status == Lyrics.NOT_FOUND -> "NOT FOUND"
        l.synced -> "SYNCED"
        else -> "PLAIN"
    }
    Box(Modifier.fillMaxSize().padding(14.dp)) {
        DotText("F.INC\nLYRICS\n$tag", Modifier.align(Alignment.TopStart), pitch = 1.2.dp, color = Drop.dim)
        when {
            t == null -> DotText("PLAY A SONG\nFIRST", Modifier.align(Alignment.Center), pitch = 1.8.dp, color = Drop.dim, centered = true)
            l == null -> DotText("LOADING...", Modifier.align(Alignment.Center), pitch = 1.8.dp, color = Drop.dim)
            l.status == Lyrics.ERROR -> DotText("CAN'T LOAD LYRICS\n${l.reason}\nTAP RELOAD", Modifier.align(Alignment.Center), pitch = 1.8.dp, color = Drop.dim, centered = true)
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
