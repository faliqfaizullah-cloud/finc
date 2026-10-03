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
fun permsToAsk() = if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPerm(), Manifest.permission.POST_NOTIFICATIONS) else arrayOf(audioPerm())

fun audioPerm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun App(c: Controller) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPerm()) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = ContextCompat.checkSelfPermission(ctx, audioPerm()) == PackageManager.PERMISSION_GRANTED
    }
    var media by remember { mutableStateOf(listOf<Track>()) }
    LaunchedEffect(granted) { if (granted) media = withContext(Dispatchers.IO) { loadTracks(ctx) } }
    val prefs = remember { ctx.getSharedPreferences("finc", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
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
    val tracks = remember(media, imported) { media + imported }
    val playlists = remember { Playlists(prefs) }
    var addTarget by remember { mutableStateOf<Track?>(null) }
    var started by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(c.playing) { while (c.playing) { c.tick(); delay(500) } }

    Box(Modifier.fillMaxSize()) {
        when {
            !started -> Onboarding { started = true; launcher.launch(permsToAsk()) }
            tracks.isEmpty() -> Column(Modifier.fillMaxSize().background(Color(0xFFF1F4FB)), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("No music found on this device", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { if (granted) media = loadTracks(ctx) else launcher.launch(permsToAsk()) }) { Text("Allow access / Refresh") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onImport) { Text("Import music from files") }
            }
            else -> {
                when (tab) {
                    0 -> HomeScreen(c, tracks, onImport)
                    1 -> ExploreScreen(c, tracks, onImport) { addTarget = it }
                    2 -> BrowseScreen(c, tracks, { addTarget = it }, { tab = 3 })
                    else -> PlaylistsScreen(c, tracks, playlists)
                }
                if (tab != 2) MiniPill(c, Modifier.align(Alignment.BottomCenter).padding(bottom = 112.dp))
                NavBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
                addTarget?.let { AddToPlaylistDialog(it, playlists) { addTarget = null } }
            }
        }
    }
}

@Composable
fun NavBar(tab: Int, onTab: (Int) -> Unit, modifier: Modifier) {
    Row(modifier.navigationBarsPadding().padding(bottom = 14.dp).shadow(12.dp, CircleShape)
        .background(Color.White, CircleShape).padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        listOf(Icons.Rounded.Home, Icons.Rounded.Public, Icons.Rounded.LibraryMusic, Icons.Rounded.PlaylistPlay).forEachIndexed { i, ic ->
            Icon(ic, null, tint = if (i == tab) Color.Black else Color(0xFFB0B3BD),
                modifier = Modifier.size(28.dp).clickable { onTab(i) })
        }
    }
}

// ---------- Onboarding (image 2, left) ----------
@Composable
fun Onboarding(onStart: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFDDE0F2), Color(0xFFF4F6FC))))) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)
            .shadow(20.dp, RoundedCornerShape(36.dp)).clip(RoundedCornerShape(36.dp)).background(Color(0xFFF3F6FD))) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(200.dp).padding(24.dp).align(Alignment.TopStart)) {
                val r = size.height / 2
                for (i in 0..1) {
                    drawCircle(Color(0xFFDAD9D6), r, Offset(r + i * r * 0.7f, r))
                    drawCircle(Color.White, r * 0.18f, Offset(r + i * r * 0.7f, r))
                }
                val cx = r + 1.4f * r + r * 0.2f
                drawCircle(Brush.sweepGradient(listOf(Color(0xFFBFC4D6), Color(0xFFF3D9E8), Color(0xFFCFE7DD), Color(0xFF8B8F9C), Color(0xFFBFC4D6)), Offset(cx, r)), r, Offset(cx, r))
                drawCircle(Color.White, r * 0.22f, Offset(cx, r))
            }
            Box(Modifier.align(Alignment.CenterEnd).offset(x = 40.dp).size(220.dp, 130.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .55f))
                .border(1.dp, Color.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    repeat(2) { Box(Modifier.size(46.dp).border(6.dp, Color(0xFFDADCE6), CircleShape)) }
                }
                Text("90", Modifier.align(Alignment.BottomStart).padding(8.dp), color = Color.Gray, fontSize = 13.sp)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(28.dp)) {
                Text("New", fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF111111))
                Text("Age Of", fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF9A9CA8))
                Text("Music", fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF111111))
                Spacer(Modifier.height(32.dp))
                Box(Modifier.fillMaxWidth().height(60.dp).shadow(10.dp, CircleShape).background(Color(0xFFF7F8FD), CircleShape)
                    .clickable { onStart() }, contentAlignment = Alignment.Center) {
                    Text("Get Started", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                }
            }
        }
    }
}

// ---------- Home: stacked "Favorite Albums" (image 1) ----------
@Composable
fun HomeScreen(c: Controller, tracks: List<Track>, onImport: () -> Unit) {
    val albums = remember(tracks) { tracks.groupBy { it.albumId }.values.toList() }
    val pager = rememberPagerState { albums.size }
    Box(Modifier.fillMaxSize().background(Color(0xFF6C63FF)).statusBarsPadding()
        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 100.dp)) {
        Column(Modifier.fillMaxSize().shadow(24.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
            .background(Color(0xFFE9E9EC)), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onImport() }.background(Color.White), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, "Import music", Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            HorizontalPager(pager, Modifier.fillMaxWidth().height(270.dp), contentPadding = PaddingValues(horizontal = 60.dp)) { p ->
                val off = (abs(pager.currentPage - p) + abs(pager.currentPageOffsetFraction)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxSize().graphicsLayer { val s = 1f - .25f * off; scaleX = s; scaleY = s; alpha = 1f - .5f * off }
                    .clickable { c.play(albums[p], 0) }, contentAlignment = Alignment.BottomCenter) {
                    for (k in 3 downTo 0) Box(Modifier.align(Alignment.TopCenter).offset(y = (k * 7).dp)
                        .width((180 - k * 10).dp).height(24.dp).clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1A1A1A + (k * 0x151515))))
                    Box(Modifier.align(Alignment.BottomStart).offset(x = (-6).dp, y = (-40).dp).size(110.dp).background(Color(0xFF111111), CircleShape))
                    Cover(albums[p][0], Modifier.size(200.dp).shadow(14.dp, RoundedCornerShape(6.dp)), RoundedCornerShape(6.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Favorite Albums", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text("${albums.size} Albums", color = Color.Gray, fontWeight = FontWeight.SemiBold)
            Text(albums.getOrNull(pager.currentPage)?.firstOrNull()?.album ?: "", color = Color(0xFF6C63FF), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Spacer(Modifier.weight(1.4f))
        }
    }
}

// ---------- Mini player pill with spinning vinyl ----------
@Composable
fun MiniPill(c: Controller, modifier: Modifier) {
    val t = c.current ?: return
    val spin = rememberInfiniteTransition(label = "spin")
    val ang by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "ang")
    Row(modifier.fillMaxWidth(0.82f).shadow(16.dp, CircleShape).background(Color.White, CircleShape)
        .clickable { c.toggle() }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Vinyl(t, Modifier.size(56.dp).rotate(if (c.playing) ang else 0f))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(t.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(if (c.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.padding(end = 12.dp))
    }
}

// ---------- Explore: curved fading list (image 2, right) ----------
@Composable
fun ExploreScreen(c: Controller, tracks: List<Track>, onImport: () -> Unit, onAdd: (Track) -> Unit) {
    val st = rememberLazyListState()
    val focus by remember {
        derivedStateOf {
            val li = st.layoutInfo
            val mid = (li.viewportStartOffset + li.viewportEndOffset) / 2
            li.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: 0
        }
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFDDE0F2), Color(0xFFF1F4FB))))
        .statusBarsPadding().padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Explore", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Icon(Icons.Rounded.Add, "Import music", Modifier.size(28.dp).clickable { onImport() })
        }
        LazyColumn(state = st, modifier = Modifier.weight(1f), contentPadding = PaddingValues(top = 120.dp, bottom = 260.dp)) {
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                val sel = i == focus
                Row(Modifier.fillMaxWidth().height(100.dp).graphicsLayer {
                    val info = st.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.index == i }
                    if (item != null) {
                        val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                        val d = (((item.offset + item.size / 2f) - mid) / (info.viewportSize.height / 2f)).coerceIn(-1.5f, 1.5f)
                        translationX = d * d * 90.dp.toPx()
                        rotationZ = d * 10f
                        alpha = (1f - abs(d) * .6f).coerceAtLeast(.15f)
                    }
                }.padding(horizontal = 24.dp).clip(CircleShape)
                    .background(if (sel) Color.White else Color.Transparent)
                    .combinedClickable(onClick = { c.play(tracks, i) }, onLongClick = { onAdd(t) }).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(t, Modifier.size(76.dp).shadow(6.dp, CircleShape), CircleShape)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(t.title, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (sel) Color.Black else Color(0xFF555866))
                        Text(t.artist, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (sel) Box(Modifier.shadow(4.dp, CircleShape).background(Color(0xFFF4F6FC), CircleShape)
                        .padding(horizontal = 18.dp, vertical = 10.dp)) { Text("Play", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                }
            }
        }
    }
}

// ---------- Browse: cover-flow + player bar (image 3) ----------
@Composable
fun BrowseScreen(c: Controller, tracks: List<Track>, onAdd: (Track) -> Unit, onOpenPlaylists: () -> Unit) {
    var tab by remember { mutableIntStateOf(1) }
    val tabs = listOf("Listen Now" to Icons.Rounded.PlayCircle, "Browse" to Icons.Rounded.GridView,
        "Radio" to Icons.Rounded.Radio, "Playlists" to Icons.Rounded.QueueMusic)
    val items = remember(tab, tracks) {
        when (tab) { 0 -> tracks; 1 -> tracks.distinctBy { it.albumId }; 2 -> tracks.shuffled(); else -> tracks.distinctBy { it.artist } }
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A3632), Color(0xFF14110F))))
        .statusBarsPadding().padding(bottom = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = .12f)).padding(4.dp)) {
            tabs.forEachIndexed { i, (name, ic) ->
                Row(Modifier.clip(CircleShape).background(if (i == tab) Color.White.copy(alpha = .18f) else Color.Transparent)
                    .clickable { if (i == 3) onOpenPlaylists() else tab = i }.padding(horizontal = 9.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ic, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(name, color = Color.White, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        key(tab) { CoverFlow(c, items) }
        Spacer(Modifier.weight(1f))
        PlayerBar(c, onAdd)
    }
}

@Composable
fun CoverFlow(c: Controller, items: List<Track>) {
    val state = rememberPagerState(initialPage = items.size / 2) { items.size }
    HorizontalPager(state, Modifier.fillMaxWidth().height(260.dp), contentPadding = PaddingValues(horizontal = 80.dp)) { p ->
        val off = (state.currentPage - p) + state.currentPageOffsetFraction
        val a = abs(off).coerceIn(0f, 1f)
        Cover(items[p], Modifier.fillMaxWidth().aspectRatio(1f).zIndex(-abs(off)).graphicsLayer {
            rotationY = -off.coerceIn(-1f, 1f) * 50f
            cameraDistance = 12f * density
            translationX = off * size.width * .3f
            val s = 1f - .2f * a; scaleX = s; scaleY = s; alpha = 1f - .3f * a
        }.border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(28.dp)).clickable { c.play(items, p) },
            RoundedCornerShape(28.dp))
    }
    Spacer(Modifier.height(14.dp))
    val cur = items.getOrNull(state.currentPage)
    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .14f)).padding(horizontal = 22.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(cur?.title ?: "", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(cur?.artist ?: "", color = Color.White.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun PlayerBar(c: Controller, onAdd: (Track) -> Unit) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var showVol by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    val t = c.current
    val dur = (t?.duration ?: 1L).coerceAtLeast(1L)
    val white = Color.White
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .background(white.copy(alpha = .14f)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .3f)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Cover(t, Modifier.size(38.dp), RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(t?.title ?: "Nothing playing", color = white, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t?.let { "${it.artist} - ${it.album}" } ?: "", color = white.copy(alpha = .6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(fmt(c.pos), color = white.copy(alpha = .8f), fontSize = 12.sp)
            if (t != null) Icon(Icons.Rounded.PlaylistAdd, "Add to playlist", tint = white,
                modifier = Modifier.padding(start = 8.dp).size(22.dp).clickable { onAdd(t) })
        }
        Slider(value = (c.pos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { c.exo.seekTo((it * dur).toLong()); c.pos = (it * dur).toLong() },
            colors = SliderDefaults.colors(thumbColor = white, activeTrackColor = white, inactiveTrackColor = white.copy(alpha = .25f)))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            IconButton({ showLyrics = true }) { Icon(Icons.Rounded.ChatBubble, null, tint = white) }
            IconButton({ c.prev() }) { Icon(Icons.Rounded.SkipPrevious, null, tint = white) }
            IconButton({ c.toggle() }) { Icon(if (c.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = white, modifier = Modifier.size(34.dp)) }
            IconButton({ c.next() }) { Icon(Icons.Rounded.SkipNext, null, tint = white) }
            IconButton({ showQueue = true }) { Icon(Icons.Rounded.QueueMusic, null, tint = white) }
            IconButton({ showVol = !showVol }) { Icon(Icons.Rounded.VolumeUp, null, tint = white) }
        }
        if (showVol) Slider(value = vol, valueRange = 0f..am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat(),
            onValueChange = { vol = it; am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) },
            colors = SliderDefaults.colors(thumbColor = white, activeTrackColor = white))
    }
    if (showLyrics) AlertDialog(onDismissRequest = { showLyrics = false }, confirmButton = { TextButton({ showLyrics = false }) { Text("OK") } },
        title = { Text(t?.title ?: "Lyrics") }, text = { Text("No lyrics available for this track yet.") })
    if (showQueue) AlertDialog(onDismissRequest = { showQueue = false }, confirmButton = { TextButton({ showQueue = false }) { Text("Close") } },
        title = { Text("Up next") }, text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(c.queue) { i, q ->
                    Text(q.title, Modifier.fillMaxWidth().clickable { c.exo.seekTo(i, 0); c.exo.play(); showQueue = false }.padding(vertical = 10.dp),
                        fontWeight = if (i == c.index) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        })
}
