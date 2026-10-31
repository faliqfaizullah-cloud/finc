@file:OptIn(ExperimentalFoundationApi::class)

package com.finc.music

import android.Manifest
import android.app.Activity
import android.provider.Settings
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

// ---------- Data ----------
data class Track(val id: Long, val title: String, val artist: String, val album: String,
                 val albumId: Long, val uri: Uri, val duration: Long, val genre: String = "", val mime: String = "")

fun cleanGenre(raw: String?): String {
    val g = raw?.substringBefore(';')?.trim().orEmpty()
    return if (g.isEmpty() || g.startsWith("(") || g.all { it.isDigit() }) "" else g
}

fun loadTracks(ctx: Context): List<Track> {
    val out = mutableListOf<Track>()
    val A = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val cols = mutableListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE)
    if (Build.VERSION.SDK_INT >= 30) cols += MediaStore.Audio.Media.GENRE
    ctx.contentResolver.query(A, cols.toTypedArray(),
        "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.DURATION}>30000", null,
        "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            out += Track(id, c.getString(1) ?: "Unknown", c.getString(2) ?: "Unknown",
                c.getString(3) ?: "Unknown", c.getLong(4), ContentUris.withAppendedId(A, id), c.getLong(5),
                if (cols.size > 7) cleanGenre(c.getString(7)) else "", c.getString(6) ?: "")
        }
    }
    return out
}

fun displayName(ctx: Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull() ?: "Unknown"

/** Reads tags (title/artist/album/duration) from a file picked with the system file picker. */
fun readImported(ctx: Context, uri: Uri): Track? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: displayName(ctx, uri)
        val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: "Unknown"
        val album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown"
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        Track(-(abs(uri.toString().hashCode()).toLong()) - 1, title, artist, album,
            -abs(album.hashCode().toLong()) - 1_000_000_000_000L, uri, dur,
            cleanGenre(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)), importedMime(ctx, uri))
    } finally { r.release() }
}.getOrNull()

fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

// ---------- Player ----------
class Controller private constructor(private val app: Context) {
    companion object {
        @Volatile private var inst: Controller? = null
        fun get(ctx: Context): Controller = inst ?: synchronized(this) {
            inst ?: Controller(ctx.applicationContext).also { inst = it }
        }
    }

    val exo: ExoPlayer = PlayerHolder.get(app)
    var queue by mutableStateOf(listOf<Track>())
    var index by mutableIntStateOf(0)
    var playing by mutableStateOf(exo.isPlaying)
    var pos by mutableLongStateOf(0L)
    val current: Track? get() = queue.getOrNull(index)

    /** 0 = off (the queue ends, then the library keeps playing), 1 = repeat the queue, 2 = repeat this song. */
    var repeat by mutableIntStateOf(0)
    /** Every song on the device; used to keep playing once the current queue has finished. */
    var pool: List<Track> = emptyList()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sp = app.getSharedPreferences("finc", Context.MODE_PRIVATE)

    // ---- rhythm haptics: light pulses on the beat of the song that is playing ----
    var rhythm by mutableStateOf(sp.getBoolean("rhythm", false))
    private var beatMap: BeatMap? = null
    private var beatJob: Job? = null
    private var lastBeatPos = 0L
    private val beatScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val vibrator: Vibrator? = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val beatTick = object : Runnable {
        override fun run() {
            val m = beatMap
            if (!rhythm || m == null || !exo.isPlaying) return
            val now = exo.currentPosition + 40L   // fire slightly early to cover the motor's start-up time
            if (now < lastBeatPos || now - lastBeatPos > 400L) lastBeatPos = now - 16L
            val s = m.strongestIn(lastBeatPos, now)
            if (s >= 0f) pulse(s)
            lastBeatPos = now
            mainHandler.postDelayed(this, 16L)
        }
    }

    private fun pulse(strength: Float) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val amp = if (v.hasAmplitudeControl()) (50 + 110 * strength).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        v.vibrate(VibrationEffect.createOneShot(18L, amp))
    }

    private fun startBeatTick() {
        if (rhythm && beatMap != null) {
            lastBeatPos = exo.currentPosition
            mainHandler.removeCallbacks(beatTick)
            mainHandler.post(beatTick)
        }
    }

    private fun restartRhythm() {
        beatJob?.cancel()
        mainHandler.removeCallbacks(beatTick)
        beatMap = null
        val t = current ?: return
        if (!rhythm) return
        val map = BeatMap()
        beatMap = map
        beatJob = beatScope.launch { runCatching { BeatAnalyzer.analyze(app, t.uri, map) } }
        startBeatTick()
    }

    fun toggleRhythm() {
        rhythm = !rhythm
        sp.edit().putBoolean("rhythm", rhythm).apply()
        restartRhythm()
    }

    init {
        repeat = sp.getInt("repeat", 0).coerceIn(0, 2)
        applyRepeat()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; PlayerWidget.refresh(app); if (isPlaying) startBeatTick() }
            override fun onMediaItemTransition(m: MediaItem?, reason: Int) { index = exo.currentMediaItemIndex; publish(); restartRhythm() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                lastBeatPos = exo.currentPosition
            }
            override fun onPlaybackStateChanged(state: Int) {
                // the last song of the queue finished: go on to the next songs automatically
                if (state == Player.STATE_ENDED && repeat == 0) mainHandler.post { autoContinue() }
            }
        })
    }

    private fun applyRepeat() {
        exo.repeatMode = when (repeat) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun cycleRepeat() {
        repeat = (repeat + 1) % 3
        sp.edit().putInt("repeat", repeat).apply()
        applyRepeat()
    }

    /** Continue with the songs that follow the last one played (wrapping round the library). */
    private fun autoContinue() {
        val last = queue.lastOrNull() ?: return
        if (pool.isEmpty()) return
        val at = pool.indexOfFirst { it.id == last.id }
        val rest = if (at >= 0) pool.subList(at + 1, pool.size) + pool.subList(0, at + 1) else pool
        if (rest.isNotEmpty()) play(rest, 0)
    }

    /** Every item carries an artwork URI, so the system always shows that song's own cover. */
    private fun item(t: Track) = MediaItem.Builder()
        .setUri(t.uri).setMediaId(t.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
            .setDisplayTitle(t.title).setArtworkUri(ArtProvider.uriFor(t.uri)).build())
        .build()

    private fun publish() {
        PlayerWidget.saveState(app, current)
        PlayerWidget.refresh(app)
    }

    fun play(list: List<Track>, i: Int) {
        queue = list; index = i
        exo.setMediaItems(list.map { item(it) }, i, 0)
        exo.prepare(); exo.play()
        app.startService(Intent(app, PlaybackService::class.java))
        publish()
        restartRhythm()
    }
    fun toggle() { if (exo.isPlaying) exo.pause() else exo.play() }
    fun next() { if (exo.hasNextMediaItem()) exo.seekToNextMediaItem() else if (repeat == 0) autoContinue() }
    fun prev() { if (exo.currentPosition > 3000 || !exo.hasPreviousMediaItem()) exo.seekTo(0) else exo.seekToPreviousMediaItem() }
    var speed by mutableFloatStateOf(1f)
    fun setTempo(s: Float) { speed = s; exo.setPlaybackSpeed(s) }
    fun tick() { pos = exo.currentPosition }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = Controller.get(applicationContext)
        setContent { MaterialTheme { App(controller) } }
    }
}

// ---------- Album art (original embedded art, fallback to MediaStore) ----------
private val artCache = LruCache<Long, ImageBitmap>(60)
private val noArt = HashSet<Long>()

private fun loadArt(ctx: Context, t: Track): ImageBitmap? = loadArtBitmap(ctx, t.uri)?.asImageBitmap()

@Composable
fun rememberArt(t: Track?): ImageBitmap? {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(t?.let { artCache.get(it.id) }, t?.id) {
        if (t == null) { value = null; return@produceState }
        artCache.get(t.id)?.let { value = it; return@produceState }
        if (t.id in noArt) { value = null; return@produceState }
        val loaded = withContext(Dispatchers.IO) { loadArt(ctx, t) }
        if (loaded != null) artCache.put(t.id, loaded) else noArt.add(t.id)
        value = loaded
    }
    return bmp
}

@Composable
fun Cover(t: Track?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp)) {
    val art = rememberArt(t)
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFFFF6FB5)))),
        contentAlignment = Alignment.Center) {
        if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = .85f))
    }
}

@Composable
fun Vinyl(t: Track?, modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
        Cover(t, Modifier.fillMaxSize(0.55f), CircleShape)
        Box(Modifier.size(6.dp).background(Color.White, CircleShape))
    }
}

// ---------- App ----------
fun audioPerm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

fun permsToAsk() = if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPerm(), Manifest.permission.POST_NOTIFICATIONS) else arrayOf(audioPerm())

@Composable
fun App(c: Controller) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("finc", Context.MODE_PRIVATE) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPerm()) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = ContextCompat.checkSelfPermission(ctx, audioPerm()) == PackageManager.PERMISSION_GRANTED
    }
    var media by remember { mutableStateOf(listOf<Track>()) }
    LaunchedEffect(granted) { if (granted) media = withContext(Dispatchers.IO) { loadTracks(ctx) } }
    var imported by remember { mutableStateOf(listOf<Track>()) }
    LaunchedEffect(Unit) {
        imported = withContext(Dispatchers.IO) {
            (prefs.getStringSet("imported", emptySet()) ?: emptySet()).mapNotNull { readImported(ctx, Uri.parse(it)) }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    readImported(ctx, u)
                }
            }
            imported = (imported + added).distinctBy { it.uri }
            prefs.edit().putStringSet("imported", imported.map { it.uri.toString() }.toSet()).apply()
        }
    }
    val onImport: () -> Unit = { importer.launch(arrayOf("audio/*", "application/flac", "application/x-flac", "application/octet-stream")) }
    val onRefresh: () -> Unit = {
        if (granted) scope.launch { media = withContext(Dispatchers.IO) { loadTracks(ctx) } } else launcher.launch(permsToAsk())
    }
    val tracks = remember(media, imported) { media + imported }
    val playlists = remember { Playlists(prefs) }
    var addTarget by remember { mutableStateOf<Track?>(null) }

    var screen by rememberSaveable { mutableIntStateOf(0) }          // 0 splash, 1 genre list, 2 device
    var showAlbums by rememberSaveable { mutableStateOf(false) }
    var catLabel by rememberSaveable { mutableStateOf(prefs.getString("cat_label", "ALL SONGS") ?: "ALL SONGS") }
    var loadedLabel by rememberSaveable { mutableStateOf("") }
    var hiRes by remember { mutableStateOf(prefs.getBoolean("hires", false)) }
    // lossless files (FLAC, WAV, AIFF, ALAC) found in the library, with their sample rate and bit depth
    val audioInfo by produceState(emptyMap<Long, AudioInfo>(), tracks) {
        value = withContext(Dispatchers.Default) { AudioProbe.scanLossless(ctx, tracks) }
    }
    LaunchedEffect(c.playing) { while (c.playing) { c.tick(); delay(500) } }
    LaunchedEffect(tracks) { c.pool = tracks }

    // genre dial entries: all songs, every genre found, then playlists
    val cats = remember(tracks, playlists.list, audioInfo) {
        val byUri = tracks.associateBy { it.uri.toString() }
        val genres = tracks.filter { it.genre.isNotBlank() }.groupBy { it.genre.uppercase() }
            .entries.sortedByDescending { it.value.size }.take(12).map { Cat(it.key, it.value) }
        val lossless = tracks.filter { audioInfo.containsKey(it.id) }
        val hires = lossless.filter { audioInfo[it.id]?.hiRes == true }
        listOf(Cat("ALL SONGS", tracks)) +
            (if (lossless.isNotEmpty()) listOf(Cat("LOSSLESS", lossless)) else emptyList()) +
            (if (hires.isNotEmpty()) listOf(Cat("HI-RES", hires)) else emptyList()) +
            genres + playlists.list.map { p -> Cat(p.name.uppercase(), p.uris.mapNotNull { byUri[it] }) }
    }
    val ci = cats.indexOfFirst { it.label == catLabel }.coerceAtLeast(0)
    val albums = remember(tracks) { tracks.groupBy { it.albumId }.values.toList() }

    // status-bar icon colour follows the screen
    val view = LocalView.current
    val darkScreen = screen == 0 || showAlbums
    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !darkScreen
                isAppearanceLightNavigationBars = !darkScreen
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Drop.bg)) {
        when (screen) {
            0 -> SplashScreen {
                // ask for anything still missing: music access and the notification that shows the lock-screen player
                val missing = permsToAsk().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
                screen = if (prefs.getBoolean("genre_set", false)) 2 else 1
            }
            1 -> IntroScreen(cats, ci, tracks.firstOrNull()) { i ->
                catLabel = cats[i].label
                prefs.edit().putBoolean("genre_set", true).putString("cat_label", catLabel).apply()
                screen = 2
            }
            else -> DropShell(
                c = c, cats = cats, catIndex = ci,
                onCatStep = { d ->
                    val n = cats.size
                    val now = cats.indexOfFirst { it.label == catLabel }.coerceAtLeast(0)
                    catLabel = cats[(((now + d) % n) + n) % n].label
                    prefs.edit().putString("cat_label", catLabel).apply()
                },
                loadedCat = cats.indexOfFirst { it.label == loadedLabel },
                onLoaded = { loadedLabel = cats[it.coerceIn(0, cats.lastIndex)].label },
                hiResOn = hiRes,
                onHiRes = {
                    hiRes = !hiRes
                    prefs.edit().putBoolean("hires", hiRes).apply()
                    Toast.makeText(ctx, "Hi-res output " + (if (hiRes) "on" else "off") + ": restart F.INC to apply", Toast.LENGTH_LONG).show()
                },
                onImport = onImport, onRefresh = onRefresh,
                onWidget = { ctx.startActivity(Intent(ctx, WidgetConfigActivity::class.java)) },
                onAlbums = { if (albums.isNotEmpty()) showAlbums = true },
                onIntro = { screen = 1 },
                onSave = { addTarget = it },
                onOutput = {
                    // the volume panel needs Android 10; older versions open the sound settings
                    val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
                    runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
        }
        if (showAlbums) AlbumStack(albums,
            onPick = { list -> showAlbums = false; c.play(list, 0); loadedLabel = cats[ci].label },
            onClose = { showAlbums = false })
        addTarget?.let { AddToPlaylistDialog(it, playlists) { addTarget = null } }
    }
}
