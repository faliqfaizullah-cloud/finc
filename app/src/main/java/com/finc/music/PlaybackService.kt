package com.finc.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** One ExoPlayer shared by the UI and the background service. */
object PlayerHolder {
    private var player: ExoPlayer? = null
    /** The player if it already exists (never creates one). */
    fun peek(): ExoPlayer? = player
    @Synchronized
    fun get(ctx: Context): ExoPlayer = player ?: ExoPlayer.Builder(ctx.applicationContext)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build().also { player = it }
}

/**
 * Publishes the player to Android as a media session: notification, lock screen,
 * quick-settings media card, Bluetooth / car / headset / watch controls.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        session = MediaSession.Builder(this, PlayerHolder.get(this))
            .apply { if (open != null) setSessionActivity(open) }
            .build()
        // Register the session right away so the notification / lock-screen card appears
        // as soon as playback starts, even when no controller has connected to the service.
        session?.let { addSession(it) }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.ic_notification) })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
