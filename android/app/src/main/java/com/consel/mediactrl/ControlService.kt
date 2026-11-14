package com.consel.mediactrl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps the connection alive while the app is in the background and mirrors the
 * computer's player into a MediaSession, so the notification shade, lock screen,
 * headset buttons and (optionally) the phone's volume keys control the computer.
 */
class ControlService : Service() {
    companion object {
        private const val TAG = "MediaCtrl"
        private const val CHANNEL = "control"
        private const val NOTE_ID = 1
        private const val ACTION_PLAY_PAUSE = "com.consel.mediactrl.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.consel.mediactrl.NEXT"
        private const val ACTION_PREV = "com.consel.mediactrl.PREV"
        private const val ACTION_DISCONNECT = "com.consel.mediactrl.DISCONNECT"
        private const val CUSTOM_DISCONNECT = "disconnect"
        const val VOLUME_STEP = 0.05f

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ControlService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "cannot start service: $e")
            }
        }
    }

    private val scope = MainScope()
    private lateinit var session: MediaSession
    private lateinit var volumeProvider: VolumeProvider
    private lateinit var nm: NotificationManager
    private var remoteVolume: Boolean? = null
    private var lastMeta: List<Any?>? = null
    private var lastPlayback: List<Any?>? = null
    private var lastNote: List<Any?>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        session = MediaSession(this, "MediaControl").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Remote.command("play")
                override fun onPause() = Remote.command("pause")
                override fun onStop() = Remote.command("stop")
                override fun onSkipToNext() = Remote.command("next")
                override fun onSkipToPrevious() = Remote.command("previous")
                override fun onSeekTo(pos: Long) = Remote.seek(pos * 1000)
                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == CUSTOM_DISCONNECT) Remote.disconnect()
                }
            })
            setSessionActivity(contentIntent())
            isActive = true
        }
        volumeProvider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, 50) {
            override fun onSetVolumeTo(volume: Int) {
                Remote.setSysVolume(volume / 100f)
                currentVolume = volume
            }

            override fun onAdjustVolume(direction: Int) {
                when (direction) {
                    1, -1 -> Remote.stepVolume(direction * VOLUME_STEP)
                    101 -> Remote.setMuted(!Remote.state.value.muted) // ADJUST_TOGGLE_MUTE
                    -100 -> Remote.setMuted(true)
                    100 -> Remote.setMuted(false)
                }
                Remote.state.value.sysVolume?.let { currentVolume = (it * 100).roundToInt() }
            }
        }
        goForeground()
        scope.launch {
            combine(Remote.state, Remote.art, Remote.pinned, Remote.volumeKeys) { s, art, pinned, keys ->
                Snapshot(s, art, pinned, keys)
            }.collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> Remote.command("play_pause")
            ACTION_NEXT -> Remote.command("next")
            ACTION_PREV -> Remote.command("previous")
            ACTION_DISCONNECT -> Remote.disconnect()
            else -> goForeground()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private fun goForeground() {
        lastNote = null
        startForeground(NOTE_ID, buildNotification(Snapshot(Remote.state.value, Remote.art.value,
            Remote.pinned.value, Remote.volumeKeys.value)), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private data class Snapshot(
        val s: RemoteState, val art: Map<String, Bitmap>, val pinned: String?, val volumeKeys: Boolean,
    ) {
        val player: PlayerState? get() = if (s.link == Link.Connected || s.link == Link.Lost) s.current(pinned) else null
    }

    private fun render(snap: Snapshot) {
        val s = snap.s
        if (s.server == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        applyVolumeMode(snap.volumeKeys)
        s.sysVolume?.let { volumeProvider.currentVolume = (it * 100).roundToInt() }

        val p = snap.player
        val (title, text) = texts(snap)
        val bmp = p?.artId?.let { snap.art[it] }

        val meta = listOf(title, text, p?.album, p?.lengthUs, p?.canSeek, p?.artId, bmp != null)
        if (meta != lastMeta) {
            lastMeta = meta
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, text)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, p?.album ?: "")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION,
                        if (p != null && p.canSeek) p.lengthUs / 1000 else -1L)
                    .apply { if (bmp != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp) }
                    .build()
            )
        }

        val connected = s.link == Link.Connected
        val state = when {
            p == null && (s.link == Link.Connecting || s.link == Link.Lost) -> PlaybackState.STATE_CONNECTING
            p == null -> PlaybackState.STATE_STOPPED
            !connected -> PlaybackState.STATE_CONNECTING
            p.playing -> PlaybackState.STATE_PLAYING
            p.status == "Paused" -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        var actions = 0L
        if (p != null && connected && p.canControl) {
            if (p.canPlay || p.canPause) {
                actions = actions or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE
            }
            if (p.canNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
            if (p.canPrev) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            if (p.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO
        }
        val playback = listOf(state, actions, p?.positionUs, p?.stampMs, p?.rate)
        if (playback != lastPlayback) {
            lastPlayback = playback
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(state, (p?.positionUs ?: 0L) / 1000,
                        if (p?.playing == true) p.rate.toFloat() else 0f,
                        p?.stampMs ?: android.os.SystemClock.elapsedRealtime())
                    .addCustomAction(PlaybackState.CustomAction.Builder(
                        CUSTOM_DISCONNECT, getString(R.string.disconnect), R.drawable.ic_close).build())
                    .build()
            )
        }

        val note = listOf(title, text, p?.artId, bmp != null, p?.playing, connected, p?.canNext,
            p?.canPrev, s.displayName)
        if (note != lastNote) {
            lastNote = note
            nm.notify(NOTE_ID, buildNotification(snap))
        }
    }

    private fun texts(snap: Snapshot): Pair<String, String> {
        val s = snap.s
        val host = s.displayName
        val p = snap.player
        return when {
            p != null -> p.title.ifBlank { p.identity } to p.artist.ifBlank { p.identity }
            s.link == Link.Connected -> getString(R.string.nothing_playing) to host
            s.link == Link.Pending -> getString(R.string.waiting_approval_short) to host
            s.link == Link.Denied -> getString(R.string.denied_short) to host
            else -> getString(R.string.connecting_to, host) to (s.server?.host ?: "")
        }
    }

    private fun applyVolumeMode(remote: Boolean) {
        if (remoteVolume == remote) return
        remoteVolume = remote
        if (remote) session.setPlaybackToRemote(volumeProvider)
        else session.setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, ControlService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    private fun action(icon: Int, label: Int, intent: String, code: Int) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label),
            serviceIntent(intent, code)).build()

    private fun buildNotification(snap: Snapshot): Notification {
        val p = snap.player
        val (title, text) = texts(snap)
        val bmp = p?.artId?.let { snap.art[it] }
        val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(snap.s.displayName)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (bmp != null) b.setLargeIcon(bmp)
        if (p != null && snap.s.link == Link.Connected) {
            b.addAction(action(R.drawable.ic_skip_previous, R.string.previous, ACTION_PREV, 1))
            b.addAction(if (p.playing) action(R.drawable.ic_pause, R.string.pause, ACTION_PLAY_PAUSE, 2)
                        else action(R.drawable.ic_play, R.string.play, ACTION_PLAY_PAUSE, 2))
            b.addAction(action(R.drawable.ic_skip_next, R.string.next, ACTION_NEXT, 3))
            style.setShowActionsInCompactView(0, 1, 2)
        }
        b.addAction(action(R.drawable.ic_close, R.string.disconnect, ACTION_DISCONNECT, 4))
        return b.setStyle(style).build()
    }
}
