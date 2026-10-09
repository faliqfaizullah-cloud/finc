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
                 val albumId: Long, val uri: Uri, val duration: Long, val genre: String = "")

fun cleanGenre(raw: String?): String {
    val g = raw?.substringBefore(';')?.trim().orEmpty()
    return if (g.isEmpty() || g.startsWith("(") || g.all { it.isDigit() }) "" else g
}

fun loadTracks(ctx: Context): List<Track> {
    val out = mutableListOf<Track>()
    val A = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val cols = mutableListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION)
    if (Build.VERSION.SDK_INT >= 30) cols += MediaStore.Audio.Media.GENRE
    ctx.contentResolver.query(A, cols.toTypedArray(),
        "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.DURATION}>30000", null,
        "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            out += Track(id, c.getString(1) ?: "Unknown", c.getString(2) ?: "Unknown",
                c.getString(3) ?: "Unknown", c.getLong(4), ContentUris.withAppendedId(A, id), c.getLong(5),
                if (cols.size > 6) cleanGenre(c.getString(6)) else "")
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
            cleanGenre(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)))
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

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; PlayerWidget.refresh(app) }
            override fun onMediaItemTransition(m: MediaItem?, reason: Int) { index = exo.currentMediaItemIndex; publish() }
        })
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
    }
    fun toggle() { if (exo.isPlaying) exo.pause() else exo.play() }
    fun next() { if (exo.hasNextMediaItem()) exo.seekToNextMediaItem() }
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
    val onImport: () -> Unit = { importer.launch(arrayOf("audio/*")) }
    val onRefresh: () -> Unit = {
        if (granted) scope.launch { media = withContext(Dispatchers.IO) { loadTracks(ctx) } } else launcher.launch(permsToAsk())
    }
    val tracks = remember(media, imported) { media + imported }
    val playlists = remember { Playlists(prefs) }
    var addTarget by remember { mutableStateOf<Track?>(null) }

    var screen by rememberSaveable { mutableIntStateOf(0) }          // 0 splash, 1 genre list, 2 device
    var showAlbums by rememberSaveable { mutableStateOf(false) }
    var catIndex by rememberSaveable { mutableIntStateOf(prefs.getInt("cat", 0)) }
    var loadedCat by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(c.playing) { while (c.playing) { c.tick(); delay(500) } }

    // genre dial entries: all songs, every genre found, then playlists
    val cats = remember(tracks, playlists.list) {
        val byUri = tracks.associateBy { it.uri.toString() }
        val genres = tracks.filter { it.genre.isNotBlank() }.groupBy { it.genre.uppercase() }
            .entries.sortedByDescending { it.value.size }.take(12).map { Cat(it.key, it.value) }
        listOf(Cat("ALL SONGS", tracks)) + genres + playlists.list.map { p -> Cat(p.name.uppercase(), p.uris.mapNotNull { byUri[it] }) }
    }
    val ci = catIndex.coerceIn(0, cats.lastIndex)
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
                catIndex = i
                prefs.edit().putBoolean("genre_set", true).putInt("cat", i).apply()
                screen = 2
            }
            else -> DropShell(
                c = c, cats = cats, catIndex = ci,
                onCatStep = { d ->
                    val n = cats.size
                    catIndex = (((catIndex + d) % n) + n) % n
                    prefs.edit().putInt("cat", catIndex).apply()
                },
                loadedCat = loadedCat, onLoaded = { loadedCat = it },
                onImport = onImport, onRefresh = onRefresh,
                onWidget = { ctx.startActivity(Intent(ctx, WidgetConfigActivity::class.java)) },
                onAlbums = { if (albums.isNotEmpty()) showAlbums = true },
                onIntro = { screen = 1 },
                onSave = { addTarget = it },
                onOutput = {
                    runCatching { ctx.startActivity(Intent(Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
        }
        if (showAlbums) AlbumStack(albums,
            onPick = { list -> showAlbums = false; c.play(list, 0); loadedCat = ci },
            onClose = { showAlbums = false })
        addTarget?.let { AddToPlaylistDialog(it, playlists) { addTarget = null } }
    }
}
