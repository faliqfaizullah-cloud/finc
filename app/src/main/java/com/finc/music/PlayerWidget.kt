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
import android.util.TypedValue
import androidx.core.content.ContextCompat
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
        private var cachedArtPx = 0

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

        private fun iconBitmap(ctx: Context, res: Int, px: Int, color: Int): Bitmap {
            val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            ContextCompat.getDrawable(ctx, res)?.mutate()?.let {
                it.setTint(color)
                it.setBounds(0, 0, px, px)
                it.draw(Canvas(b))
            }
            b.density = ctx.resources.displayMetrics.densityDpi
            return b
        }

        private fun build(ctx: Context, mgr: AppWidgetManager, id: Int, playing: Boolean, hasMedia: Boolean): RemoteViews {
            val prefs = ctx.getSharedPreferences("finc_widget", Context.MODE_PRIVATE)
            val uri = prefs.getString("uri", null)
            val d = ctx.resources.displayMetrics.density
            val o: Bundle = mgr.getAppWidgetOptions(id)
            val wdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 200
            val hdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0).takeIf { it > 0 } ?: 200
            // The design is a 200dp square; everything scales from that so any size keeps the same proportions.
            val k = (minOf(wdp, hdp) / 200f).coerceIn(0.55f, 1.8f)
            fun px(dp: Float) = (dp * k * d).toInt().coerceAtLeast(1)

            val v = RemoteViews(ctx.packageName, R.layout.widget_finc)
            v.setTextViewText(R.id.widget_title, prefs.getString("title", null) ?: "F.INC")
            v.setTextViewText(R.id.widget_subtitle, prefs.getString("artist", null) ?: "Tap to choose music")

            if (uri != cachedUri || cachedSrc == null) {
                cachedSrc = uri?.let { runCatching { loadArtBitmap(ctx, Uri.parse(it)) }.getOrNull() } ?: placeholderBitmap()
                cachedUri = uri
                cachedArt = null
            }
            val artPx = px(52f)
            if (cachedArt == null || cachedArtPx != artPx) {
                cachedArt = runCatching { roundedSquare(cachedSrc!!, artPx, artPx * 0.23f) }.getOrNull()
                cachedArt?.density = ctx.resources.displayMetrics.densityDpi
                cachedArtPx = artPx
            }
            cachedArt?.let { v.setImageViewBitmap(R.id.widget_art, it) }

            // background + colours from the user's style, drawn at this widget's own size
            val style = WidgetStyle.load(ctx)
            val scale = minOf(1f, 560f / (maxOf(wdp, hdp) * d))
            val look = WidgetRenderer.render(cachedSrc, style,
                (wdp * d * scale).toInt().coerceAtLeast(64), (hdp * d * scale).toInt().coerceAtLeast(64), 31f * d * scale)
            v.setImageViewBitmap(R.id.widget_bg, look.bg)

            // sizes
            val pad = px(14f)
            v.setViewPadding(R.id.widget_content, pad, pad, pad, pad)
            v.setImageViewBitmap(R.id.widget_note, iconBitmap(ctx, R.drawable.ic_widget_note, px(24f), look.text))
            v.setImageViewBitmap(R.id.widget_play_icon,
                iconBitmap(ctx, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play, px(18f), look.text))
            v.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, 15f * k)
            v.setTextViewTextSize(R.id.widget_subtitle, TypedValue.COMPLEX_UNIT_SP, 12f * k)
            v.setTextViewTextSize(R.id.widget_play_label, TypedValue.COMPLEX_UNIT_SP, 15f * k)
            v.setViewPadding(R.id.widget_title, 0, px(12f), 0, 0)
            v.setViewPadding(R.id.widget_subtitle, 0, px(4f), 0, 0)
            v.setViewPadding(R.id.widget_play, px(10f), px(10f), px(16f), px(10f))
            v.setViewPadding(R.id.widget_play_label, px(4f), 0, 0, 0)

            // colours
            v.setTextColor(R.id.widget_title, look.text)
            v.setTextColor(R.id.widget_subtitle, look.sub)
            v.setTextColor(R.id.widget_play_label, look.text)
            v.setInt(R.id.widget_play, "setBackgroundResource",
                if (look.lightText) R.drawable.widget_pill_bg else R.drawable.widget_pill_bg_dark)
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
