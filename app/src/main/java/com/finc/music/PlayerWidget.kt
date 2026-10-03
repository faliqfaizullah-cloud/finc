package com.finc.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import java.util.concurrent.Executors

/** Home-screen widget: current song + cover, Play / Pause pill, user-customisable look. */
class PlayerWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.finc.music.WIDGET_TOGGLE"
        private val io = Executors.newSingleThreadExecutor()
        private var cachedUri: String? = null
        private var cachedSrc: Bitmap? = null
        private var cachedArt: Bitmap? = null

        fun saveState(ctx: Context, t: Track?) {
            if (t == null) return
            ctx.getSharedPreferences("finc_widget", Context.MODE_PRIVATE).edit()
                .putString("title", t.title).putString("artist", t.artist).putString("uri", t.uri.toString()).apply()
        }

        /** Call from the main thread (the player may only be read there). */
        fun refresh(
            ctx: Context,
            playing: Boolean = PlayerHolder.peek()?.isPlaying == true,
            hasMedia: Boolean = (PlayerHolder.peek()?.mediaItemCount ?: 0) > 0
        ) {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            io.execute {
                ids.forEach { id -> runCatching { mgr.updateAppWidget(id, build(app, mgr, id, playing, hasMedia)) } }
            }
        }

        private fun roundedSquare(src: Bitmap, px: Int, radius: Float): Bitmap {
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val side = minOf(soft.width, soft.height)
            val crop = Bitmap.createBitmap(soft, (soft.width - side) / 2, (soft.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(crop, px, px, true)
            val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            Canvas(out).drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), radius, radius, paint)
            return out
        }

        private fun build(ctx: Context, mgr: AppWidgetManager, id: Int, playing: Boolean, hasMedia: Boolean): RemoteViews {
            val prefs = ctx.getSharedPreferences("finc_widget", Context.MODE_PRIVATE)
            val uri = prefs.getString("uri", null)
            val v = RemoteViews(ctx.packageName, R.layout.widget_finc)
            v.setTextViewText(R.id.widget_title, prefs.getString("title", null) ?: "F.INC")
            v.setTextViewText(R.id.widget_subtitle, prefs.getString("artist", null) ?: "Tap to choose music")

            if (uri != null && (uri != cachedUri || cachedArt == null)) {
                val src = runCatching { loadArtBitmap(ctx, Uri.parse(uri)) }.getOrNull() ?: placeholderBitmap()
                cachedSrc = src
                cachedArt = runCatching { roundedSquare(src, 256, 44f) }.getOrNull()
                cachedUri = uri
            }
            cachedArt?.let { v.setImageViewBitmap(R.id.widget_art, it) }

            // background + colours from the user's style, drawn at this widget's own size
            val style = WidgetStyle.load(ctx)
            val d = ctx.resources.displayMetrics.density
            val o: Bundle = mgr.getAppWidgetOptions(id)
            val wdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 160
            val hdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0).takeIf { it > 0 } ?: 160
            val scale = minOf(1f, 560f / (maxOf(wdp, hdp) * d))
            val look = WidgetRenderer.render(cachedSrc, style,
                (wdp * d * scale).toInt().coerceAtLeast(64), (hdp * d * scale).toInt().coerceAtLeast(64), 26f * d * scale)
            v.setImageViewBitmap(R.id.widget_bg, look.bg)
            v.setTextColor(R.id.widget_title, look.text)
            v.setTextColor(R.id.widget_subtitle, look.sub)
            v.setTextColor(R.id.widget_play_label, look.text)
            v.setInt(R.id.widget_play_icon, "setColorFilter", look.text)
            v.setInt(R.id.widget_note, "setColorFilter", look.text)
            v.setInt(R.id.widget_play, "setBackgroundResource",
                if (look.lightText) R.drawable.widget_pill_bg else R.drawable.widget_pill_bg_dark)

            v.setImageViewResource(R.id.widget_play_icon, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            v.setTextViewText(R.id.widget_play_label, if (playing) "Pause" else "Play")

            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.let { PendingIntent.getActivity(ctx, 0, it, flags) }
            if (open != null) {
                v.setOnClickPendingIntent(R.id.widget_root, open)
                v.setOnClickPendingIntent(R.id.widget_art, open)
                v.setOnClickPendingIntent(R.id.widget_note, open)
            }
            val toggle = PendingIntent.getBroadcast(ctx, 1,
                Intent(ctx, PlayerWidget::class.java).setAction(ACTION_TOGGLE), flags)
            v.setOnClickPendingIntent(R.id.widget_play, if (hasMedia) toggle else (open ?: toggle))
            return v
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val p = PlayerHolder.peek()
            if (p != null && p.mediaItemCount > 0) {
                if (p.isPlaying) p.pause() else p.play()
            } else {
                refresh(context, false, false)
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                    runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
    }
}
