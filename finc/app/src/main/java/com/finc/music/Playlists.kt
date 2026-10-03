package com.finc.music

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

data class Playlist(val id: Long, val name: String, val uris: List<String>)

class Playlists(private val prefs: SharedPreferences) {
    var list by mutableStateOf(load())
        private set

    private fun load(): List<Playlist> = runCatching {
        val a = JSONArray(prefs.getString("playlists", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val u = o.getJSONArray("uris")
            Playlist(o.getLong("id"), o.getString("name"), (0 until u.length()).map { u.getString(it) })
        }
    }.getOrDefault(emptyList())

    private fun commit(n: List<Playlist>) {
        list = n
        val a = JSONArray()
        n.forEach { p -> a.put(JSONObject().put("id", p.id).put("name", p.name).put("uris", JSONArray(p.uris))) }
        prefs.edit().putString("playlists", a.toString()).apply()
    }

    fun create(name: String, first: String? = null): Long {
        val id = System.currentTimeMillis()
        commit(list + Playlist(id, name, listOfNotNull(first)))
        return id
    }
    fun delete(id: Long) = commit(list.filter { it.id != id })
    fun toggle(id: Long, uri: String) = commit(list.map {
        if (it.id == id) it.copy(uris = if (uri in it.uris) it.uris - uri else it.uris + uri) else it
    })
}

@Composable
fun PlaylistsScreen(c: Controller, tracks: List<Track>, pl: Playlists) {
    var openId by remember { mutableStateOf<Long?>(null) }
    var creating by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val byUri = remember(tracks) { tracks.associateBy { it.uri.toString() } }
    val open = pl.list.firstOrNull { it.id == openId }
    BackHandler(open != null) { openId = null }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFDDE0F2), Color(0xFFF1F4FB))))
        .statusBarsPadding().padding(top = 16.dp)) {
        if (open == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Playlists", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Icon(Icons.Rounded.Add, "New playlist", Modifier.size(28.dp).clickable { creating = true })
            }
            if (pl.list.isEmpty()) Text("No playlists yet. Tap + to create one.", Modifier.padding(30.dp), color = Color.Gray)
            LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 220.dp)) {
                items(pl.list, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).shadow(4.dp, RoundedCornerShape(20.dp))
                        .background(Color.White, RoundedCornerShape(20.dp)).clickable { openId = p.id }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Cover(byUri[p.uris.firstOrNull()], Modifier.size(60.dp), RoundedCornerShape(14.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(p.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.uris.size} songs", color = Color.Gray, fontSize = 13.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Color.Gray)
                    }
                }
            }
        } else {
            val list = open.uris.mapNotNull { byUri[it] }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowBack, "Back", Modifier.size(28.dp).clickable { openId = null })
                Text(open.name, Modifier.weight(1f).padding(horizontal = 14.dp), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.Delete, "Delete playlist", Modifier.size(26.dp).clickable { pl.delete(open.id); openId = null })
            }
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ if (list.isNotEmpty()) c.play(list, 0) }) { Icon(Icons.Rounded.PlayArrow, null); Text("Play") }
                OutlinedButton({ if (list.isNotEmpty()) c.play(list.shuffled(), 0) }) { Icon(Icons.Rounded.Shuffle, null); Text("Shuffle") }
                TextButton({ picking = true }) { Text("Add songs") }
            }
            LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 220.dp)) {
                itemsIndexed(list) { i, t ->
                    Row(Modifier.fillMaxWidth().clickable { c.play(list, i) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(t, Modifier.size(52.dp), RoundedCornerShape(12.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.Close, "Remove", tint = Color.Gray, modifier = Modifier.clickable { pl.toggle(open.id, t.uri.toString()) })
                    }
                }
            }
        }
    }

    if (creating) AlertDialog(onDismissRequest = { creating = false },
        title = { Text("New playlist") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Playlist name") }) },
        confirmButton = { TextButton({
            if (name.isNotBlank()) { openId = pl.create(name.trim()); name = ""; creating = false }
        }) { Text("Create") } },
        dismissButton = { TextButton({ creating = false }) { Text("Cancel") } })

    if (picking && open != null) AlertDialog(onDismissRequest = { picking = false },
        title = { Text("Add songs") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(tracks, key = { it.uri.toString() }) { t ->
                    val k = t.uri.toString()
                    Row(Modifier.fillMaxWidth().clickable { pl.toggle(open.id, k) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(k in open.uris, { pl.toggle(open.id, k) })
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton({ picking = false }) { Text("Done") } })
}

@Composable
fun AddToPlaylistDialog(t: Track, pl: Playlists, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val key = t.uri.toString()
    AlertDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
        title = { Text("Add to playlist") },
        text = {
            Column {
                Text(t.title, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(pl.list, key = { it.id }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { pl.toggle(p.id, key) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(key in p.uris, { pl.toggle(p.id, key) })
                            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("New playlist") })
                    TextButton({ if (name.isNotBlank()) { pl.create(name.trim(), key); name = "" } }) { Text("Create") }
                }
            }
        })
}
