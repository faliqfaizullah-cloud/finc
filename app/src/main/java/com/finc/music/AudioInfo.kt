package com.finc.music

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/** What a file really is: codec, whether it is lossless, and its sample rate / bit depth. */
class AudioInfo(val codec: String, val lossless: Boolean, val sampleRate: Int, val bits: Int, val channels: Int, val kbps: Int) {
    val hiRes: Boolean get() = lossless && (bits > 16 || sampleRate > 48000)

    private fun khz(): String {
        val k = sampleRate / 1000f
        return if (k % 1f == 0f) k.toInt().toString() else String.format(Locale.US, "%.1f", k)
    }

    fun label(): String = when {
        lossless && bits > 0 && sampleRate > 0 -> "$codec $bits/${khz()}"
        lossless -> codec
        kbps > 0 -> "$codec ${kbps}K"
        else -> codec
    }
}

fun fullName(ctx: Context, uri: Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull() ?: ""

fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "flac" -> "audio/flac"
    "wav", "wave" -> "audio/x-wav"
    "aif", "aiff", "aifc" -> "audio/x-aiff"
    "alac" -> "audio/alac"
    "m4a", "mp4" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "aac" -> "audio/aac"
    else -> ""
}

fun importedMime(ctx: Context, uri: Uri): String {
    val t = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
    return if (t.startsWith("audio/") && t != "audio/*") t else mimeFromName(fullName(ctx, uri))
}

object AudioProbe {
    private val cache = ConcurrentHashMap<Long, AudioInfo>()
    private val maybe = listOf("flac", "wav", "wave", "aif", "alac", "mp4", "m4a")

    fun maybeLossless(t: Track): Boolean = t.mime.lowercase().let { m -> maybe.any { it in m } }

    fun probe(ctx: Context, t: Track): AudioInfo {
        cache[t.id]?.let { return it }
        val info = runCatching { doProbe(ctx, t) }.getOrNull()
            ?: AudioInfo(t.mime.substringAfter('/').uppercase().ifEmpty { "AUDIO" }, false, 0, 0, 0, 0)
        cache[t.id] = info
        return info
    }

    /** Every lossless file in the library (FLAC, WAV, AIFF, ALAC), with its details. */
    fun scanLossless(ctx: Context, tracks: List<Track>): Map<Long, AudioInfo> {
        val out = HashMap<Long, AudioInfo>()
        for (t in tracks) if (maybeLossless(t)) {
            val i = probe(ctx, t)
            if (i.lossless) out[t.id] = i
        }
        return out
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun doProbe(ctx: Context, t: Track): AudioInfo? {
        val head = ByteArray(65536)
        var n = 0
        ctx.contentResolver.openInputStream(t.uri)?.use { ins ->
            while (n < head.size) {
                val r = ins.read(head, n, head.size - n)
                if (r <= 0) break
                n += r
            }
        }
        val len = runCatching { ctx.contentResolver.openAssetFileDescriptor(t.uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        val kbps = if (len > 0 && t.duration > 0) (len * 8 / t.duration).toInt() else 0

        if (n >= 64) {
            var off = 0
            if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                off = 10 + ((u(head, 6) shl 21) or (u(head, 7) shl 14) or (u(head, 8) shl 7) or u(head, 9))
            }
            if (off + 4 < n && String(head, off, 4, Charsets.ISO_8859_1) == "fLaC") parseFlac(head, off, n, kbps)?.let { return it }
            if (String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE") {
                parseWav(head, n, kbps)?.let { return it }
            }
            if (String(head, 0, 4, Charsets.ISO_8859_1) == "FORM") parseAiff(head, n, kbps)?.let { return it }
        }

        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, t.uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val sr = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                val (name, lossless) = when {
                    mime.contains("flac") -> "FLAC" to true
                    mime.contains("alac") -> "ALAC" to true
                    mime == "audio/raw" -> "PCM" to true
                    mime == "audio/mpeg" -> "MP3" to false
                    mime.contains("mp4a") || mime.contains("aac") -> "AAC" to false
                    mime.contains("vorbis") -> "OGG" to false
                    mime.contains("opus") -> "OPUS" to false
                    else -> mime.removePrefix("audio/").uppercase() to false
                }
                return AudioInfo(name, lossless, sr, 0, ch, kbps)
            }
        } finally { ex.release() }
        return null
    }

    private fun parseFlac(h: ByteArray, off: Int, n: Int, kbps: Int): AudioInfo? {
        val s = off + 8          // after "fLaC" and the STREAMINFO block header
        if (n < s + 18) return null
        val sr = (u(h, s + 10) shl 12) or (u(h, s + 11) shl 4) or (u(h, s + 12) ushr 4)
        val ch = ((u(h, s + 12) ushr 1) and 7) + 1
        val bps = (((u(h, s + 12) and 1) shl 4) or (u(h, s + 13) ushr 4)) + 1
        return AudioInfo("FLAC", true, sr, bps, ch, kbps)
    }

    private fun parseWav(h: ByteArray, n: Int, kbps: Int): AudioInfo? {
        var p = 12
        while (p + 8 <= n) {
            val id = String(h, p, 4, Charsets.ISO_8859_1)
            val size = u(h, p + 4) or (u(h, p + 5) shl 8) or (u(h, p + 6) shl 16) or (u(h, p + 7) shl 24)
            if (id == "fmt " && p + 24 <= n) {
                val ch = u(h, p + 10) or (u(h, p + 11) shl 8)
                val sr = u(h, p + 12) or (u(h, p + 13) shl 8) or (u(h, p + 14) shl 16) or (u(h, p + 15) shl 24)
                val bits = u(h, p + 22) or (u(h, p + 23) shl 8)
                return AudioInfo("WAV", true, sr, bits, ch, kbps)
            }
            if (size < 0) return null
            p += 8 + size + (size and 1)
        }
        return null
    }

    private fun parseAiff(h: ByteArray, n: Int, kbps: Int): AudioInfo? {
        var p = 12
        while (p + 8 <= n) {
            val id = String(h, p, 4, Charsets.ISO_8859_1)
            val size = (u(h, p + 4) shl 24) or (u(h, p + 5) shl 16) or (u(h, p + 6) shl 8) or u(h, p + 7)
            if (id == "COMM" && p + 26 <= n) {
                val ch = (u(h, p + 8) shl 8) or u(h, p + 9)
                val bits = (u(h, p + 14) shl 8) or u(h, p + 15)
                val exp = ((u(h, p + 16) and 0x7F) shl 8) or u(h, p + 17)
                var m = 0.0
                for (i in 0 until 8) m = m * 256 + u(h, p + 18 + i)
                val sr = (m * 2.0.pow(exp - 16383 - 63)).toInt()
                return AudioInfo("AIFF", true, sr, bits, ch, kbps)
            }
            if (size < 0) return null
            p += 8 + size + (size and 1)
        }
        return null
    }
}
