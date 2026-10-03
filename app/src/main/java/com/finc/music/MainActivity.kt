@file:OptIn(ExperimentalFoundationApi::class)

package com.finc.music

import android.Manifest
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
                 val albumId: Long, val uri: Uri, val duration: Long)

fun loadTracks(ctx: Context): List<Track> {
    val out = mutableListOf<Track>()
    val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION)
    ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
        "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.DURATION}>30000", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            out += Track(id, c.getString(1) ?: "Unknown", c.getString(2) ?: "Unknown",
                c.getString(3) ?: "Unknown", c.getLong(4),
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), c.getLong(5))
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
            -abs(album.hashCode().toLong()) - 1_000_000_000_000L, uri, dur)
    } finally { r.release() }
}.getOrNull()

fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

// ---------- Player ----------
private fun artBytes(ctx: Context, t: Track): ByteArray? = runCatching {
    loadArt(ctx, t)?.asAndroidBitmap()?.let { bmp ->
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}.getOrNull()

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

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val artDone = HashSet<Int>()
    private var gen = 0

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(m: MediaItem?, reason: Int) {
                index = exo.currentMediaItemIndex
                prefetchArt(index)
            }
        })
    }

    private fun item(t: Track, art: ByteArray? = null) = MediaItem.Builder()
        .setUri(t.uri).setMediaId(t.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
            .apply { if (art != null) setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }.build())
        .build()

    /** Loads cover art for upcoming/previous tracks so the lock screen shows the original album art. */
    private fun prefetchArt(center: Int) {
        val g = gen
        for (k in (center - 1)..(center + 3)) {
            val t = queue.getOrNull(k) ?: continue
            if (k == exo.currentMediaItemIndex || k in artDone) continue
            artDone.add(k)
            io.execute {
                val b = artBytes(app, t)
                if (b != null) main.post {
                    if (g == gen && queue.getOrNull(k) === t && k != exo.currentMediaItemIndex && k < exo.mediaItemCount)
                        exo.replaceMediaItem(k, item(t, b))
                }
            }
        }
    }

    fun play(list: List<Track>, i: Int) {
        queue = list; index = i
        val g = ++gen
        io.execute {
            val b = artBytes(app, list[i])
            main.post {
                if (g != gen) return@post
                artDone.clear(); artDone.add(i)
                exo.setMediaItems(list.mapIndexed { k, t -> if (k == i) item(t, b) else item(t) }, i, 0)
                exo.prepare(); exo.play()
                app.startService(Intent(app, PlaybackService::class.java))
                prefetchArt(i)
            }
        }
    }
    fun toggle() { if (exo.isPlaying) exo.pause() else exo.play() }
    fun next() { if (exo.hasNextMediaItem()) exo.seekToNextMediaItem() }
    fun prev() { if (exo.currentPosition > 3000 || !exo.hasPreviousMediaItem()) exo.seekTo(0) else exo.seekToPreviousMediaItem() }
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

private fun decodeArt(bytes: ByteArray): ImageBitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var sample = 1
    while (o.outWidth / sample > 800) sample *= 2
    val d = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d)?.asImageBitmap()
}

private fun loadArt(ctx: Context, t: Track): ImageBitmap? {
    runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, t.uri)
            r.embeddedPicture?.let { return decodeArt(it) }
        } finally { r.release() }
    }
    if (t.id > 0) return runCatching {
        ctx.contentResolver.loadThumbnail(t.uri, Size(600, 600), null).asImageBitmap()
    }.getOrNull()
    return null
}

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
    val onImport: () -> Unit = { importer.launch(arrayOf("audio/*")) }
    val onRefresh: () -> Unit = {
        if (granted) scope.launch { media = withContext(Dispatchers.IO) { loadTracks(ctx) } } else launcher.launch(permsToAsk())
    }
    val tracks = remember(media, imported) { media + imported }
    val playlists = remember { Playlists(prefs) }
    var addTarget by remember { mutableStateOf<Track?>(null) }
    var started by remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
    var tab by rememberSaveable { mutableIntStateOf(1) }
    LaunchedEffect(c.playing) { while (c.playing) { c.tick(); delay(500) } }

    Box(Modifier.fillMaxSize()) {
        Aurora()
        when {
            !started -> Onboarding {
                prefs.edit().putBoolean("onboarded", true).apply()
                started = true
                launcher.launch(permsToAsk())
            }
            tracks.isEmpty() -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
                Arrangement.Center, Alignment.CenterHorizontally) {
                Text("No music yet", style = Display)
                Spacer(Modifier.height(8.dp))
                Text("Allow access to your library or import files.", color = Palette.grey)
                Spacer(Modifier.height(24.dp))
                PillButton("Allow access / Refresh", strong = true, onClick = onRefresh)
                Spacer(Modifier.height(12.dp))
                PillButton("Import music from files", onClick = onImport)
            }
            else -> {
                Crossfade(targetState = tab, label = "tab") { t ->
                    when (t) {
                        0 -> HomeScreen(c, tracks) { addTarget = it }
                        1 -> ExploreScreen(c, tracks, onImport, onRefresh) { addTarget = it }
                        else -> PlaylistsScreen(c, tracks, playlists)
                    }
                }
                if (tab != 0) MiniPill(c, { tab = 0 }, Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp))
                NavBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
                addTarget?.let { AddToPlaylistDialog(it, playlists) { addTarget = null } }
            }
        }
    }
}
