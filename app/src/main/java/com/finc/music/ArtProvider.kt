package com.finc.music

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

fun decodeSampled(bytes: ByteArray): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var sample = 1
    while (o.outWidth / sample > 1000) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Original cover art: the picture embedded in the file first, then Android's own album art. */
fun loadArtBitmap(ctx: Context, uri: Uri): Bitmap? {
    runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            r.embeddedPicture?.let { b -> decodeSampled(b)?.let { return it } }
        } finally { r.release() }
    }
    if (uri.authority == "media") {
        return runCatching { ctx.contentResolver.loadThumbnail(uri, Size(800, 800), null) }.getOrNull()
    }
    return null
}

fun placeholderBitmap(): Bitmap {
    val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
    val p = Paint().apply { shader = LinearGradient(0f, 0f, 600f, 600f, 0xFF7C5CFF.toInt(), 0xFFFF6FB5.toInt(), Shader.TileMode.CLAMP) }
    Canvas(b).drawRect(0f, 0f, 600f, 600f, p)
    return b
}

/**
 * content://com.finc.music.art/cover?u=<song uri>  ->  JPEG of that song's cover.
 * The notification, lock screen, Bluetooth/car displays all load album art from here.
 */
class ArtProvider : ContentProvider() {
    companion object {
        const val AUTH = "com.finc.music.art"
        fun uriFor(song: Uri): Uri = Uri.Builder().scheme("content").authority(AUTH)
            .appendPath("cover").appendQueryParameter("u", song.toString()).build()
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val src = uri.getQueryParameter("u") ?: return null
        val dir = File(ctx.cacheDir, "art").apply { mkdirs() }
        val f = File(dir, UUID.nameUUIDFromBytes(src.toByteArray()).toString() + ".jpg")
        if (!f.exists() || f.length() == 0L) {
            val bmp = loadArtBitmap(ctx, Uri.parse(src)) ?: placeholderBitmap()
            val tmp = File.createTempFile("art", ".tmp", dir)
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (!tmp.renameTo(f)) tmp.delete()
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
