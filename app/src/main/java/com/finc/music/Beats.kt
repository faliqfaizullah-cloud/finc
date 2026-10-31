package com.finc.music

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.isActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.sqrt

/** Beat times (ms) and how hard each beat hits (0..1) for one song. Filled in while the song is analysed. */
class BeatMap {
    private val times = ArrayList<Long>()
    private val strengths = ArrayList<Float>()

    @Synchronized fun add(ms: Long, s: Float) { times.add(ms); strengths.add(s) }
    @Synchronized fun size(): Int = times.size
    @Synchronized fun snapshot(): String = times.indices.joinToString("\n") { "${times[it]},${strengths[it]}" }

    /** Strongest beat in (from, to], or -1 when there is none. */
    @Synchronized fun strongestIn(from: Long, to: Long): Float {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= from) lo = mid + 1 else hi = mid
        }
        var best = -1f
        var i = lo
        while (i < times.size && times[i] <= to) {
            if (strengths[i] > best) best = strengths[i]
            i++
        }
        return best
    }
}

/** Looks at the bass of the decoded audio and reports an onset whenever the low end suddenly hits. */
class OnsetDetector(
    private val sr: Int, private val ch: Int, private val enc: Int,
    private val startMs: Long, private val emit: (Long, Float) -> Unit
) {
    private val hop = (sr / 100).coerceAtLeast(64)               // ~10 ms per step
    private val a = 1f - exp(-2f * Math.PI.toFloat() * 150f / sr) // one-pole low-pass at ~150 Hz
    private val bytes = when (enc) { 4 -> 4; 21 -> 3; 22 -> 4; 3 -> 1; else -> 2 }
    private var low = 0f
    private var acc = 0.0
    private var cnt = 0
    private var frames = 0L
    private val hist = FloatArray(100)
    private var hi = 0
    private var hn = 0
    private var sum = 0f
    private var e1 = 0f
    private var e2 = 0f
    private var lastBeat = -10_000L

    fun elapsedMs(): Long = startMs + frames * 1000L / sr

    private fun sample(b: ByteBuffer): Float = when (enc) {
        4 -> b.float
        21 -> {
            val x = (b.get().toInt() and 0xFF) or ((b.get().toInt() and 0xFF) shl 8) or (b.get().toInt() shl 16)
            x / 8388608f
        }
        22 -> b.int / 2147483648f
        3 -> ((b.get().toInt() and 0xFF) - 128) / 128f
        else -> b.short / 32768f
    }

    fun push(buf: ByteBuffer, off: Int, size: Int) {
        val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        b.limit(off + size)
        b.position(off)
        var n = size / (bytes * ch)
        while (n-- > 0) {
            var x = 0f
            for (c in 0 until ch) x += sample(b)
            x /= ch
            low += a * (x - low)
            acc += (low * low).toDouble()
            cnt++
            frames++
            if (cnt >= hop) { endHop(); acc = 0.0; cnt = 0 }
        }
    }

    private fun endHop() {
        val e = sqrt(acc / cnt).toFloat()
        val avg = if (hn > 0) sum / hn else 0f
        // e1 is the previous step: a local peak that clearly rises above the last second's average
        if (hn >= 20 && e1 > e2 && e1 >= e && e1 > 0.015f && e1 > avg * 1.35f) {
            val t = startMs + (frames - hop - hop / 2) * 1000L / sr
            if (t - lastBeat >= 230) {
                emit(t, ((e1 - avg) / (e1 + 1e-6f)).coerceIn(0f, 1f))
                lastBeat = t
            }
        }
        sum += e - hist[hi]
        hist[hi] = e
        hi = (hi + 1) % hist.size
        if (hn < hist.size) hn++
        e2 = e1
        e1 = e
    }
}

object BeatAnalyzer {
    private fun cacheFile(ctx: Context, uri: Uri) =
        File(File(ctx.filesDir, "beats").apply { mkdirs() }, UUID.nameUUIDFromBytes(uri.toString().toByteArray()).toString() + ".txt")

    private fun loadCached(ctx: Context, uri: Uri, map: BeatMap): Boolean {
        val f = cacheFile(ctx, uri)
        if (!f.exists()) return false
        return runCatching {
            f.forEachLine { l ->
                val p = l.split(',')
                if (p.size == 2) map.add(p[0].toLong(), p[1].toFloat())
            }
            true
        }.getOrDefault(false)
    }

    private fun encodingOf(f: MediaFormat) =
        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT

    /** Decodes the song faster than it plays and fills [map]. Works for any format the phone can decode. */
    suspend fun analyze(ctx: Context, uri: Uri, map: BeatMap) {
        if (loadCached(ctx, uri, map)) return
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return
            var det = OnsetDetector(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                encodingOf(fmt), 0L) { t, s -> map.add(t, s) }

            if (mime == "audio/raw") {
                val buf = ByteBuffer.allocate(65536)
                while (coroutineContext.isActive) {
                    buf.clear()
                    val n = ex.readSampleData(buf, 0)
                    if (n <= 0) break
                    det.push(buf, 0, n)
                    ex.advance()
                }
            } else {
                val c = MediaCodec.createDecoderByType(mime)
                codec = c
                c.configure(fmt, null, null, 0)
                c.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone && coroutineContext.isActive) {
                    if (!inputDone) {
                        val ii = c.dequeueInputBuffer(5000)
                        if (ii >= 0) {
                            val ib = c.getInputBuffer(ii) ?: break
                            val n = ex.readSampleData(ib, 0)
                            if (n < 0) {
                                c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val oi = c.dequeueOutputBuffer(info, 5000)
                    if (oi >= 0) {
                        val ob = c.getOutputBuffer(oi)
                        if (ob != null && info.size > 0) det.push(ob, info.offset, info.size)
                        c.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val nf = c.outputFormat
                        det = OnsetDetector(nf.getInteger(MediaFormat.KEY_SAMPLE_RATE), nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            encodingOf(nf), det.elapsedMs()) { t, s -> map.add(t, s) }
                    }
                }
            }
            if (coroutineContext.isActive) runCatching { cacheFile(ctx, uri).writeText(map.snapshot()) }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
