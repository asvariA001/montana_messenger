package quest.montana.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock

/**
 * THE MUSIC OUTSIDE THE APP (iOS MPNowPlayingInfoCenter + MPRemoteCommandCenter): while a track stands on the bar, the system
 * knows it — the platform's own media session carries its name, «Montana» under it, its length and where it stands, and takes
 * the play, the pause, the next, the previous and the seek from the shade, the lock screen, the headphones and the car. The
 * service holds the app alive in the foreground so the track plays on with the screen off and the app closed; it goes with
 * the bar's cross (or the notification's own), and the unplugged headphones pause the track, as on iOS.
 */
class MusicService : Service() {
    companion object {
        private const val CHANNEL = "music"
        private const val ID = 7301
        private const val CLOSE = "close"
        @Volatile private var running: MusicService? = null

        /** The player's word: the track, its rest or its place changed — the system is told, or the service comes, or goes. */
        fun sync(c: Context) {
            val s = running
            when {
                MusicPlayer.current == null -> s?.end()
                s == null -> runCatching { c.startForegroundService(Intent(c, MusicService::class.java)) }
                else -> s.publish()
            }
        }
    }

    private lateinit var session: MediaSession
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) MusicPlayer.pause() }
    }

    // THE LOCK SCREEN, THE HEADSET AND THE CAR ARE SAID (iOS da6d7683): their buttons moved the player without a word in the diary.
    // No track, file or device's name is written.
    private fun remote(word: String) = android.util.Log.i("Montana", "music_remote " + word)

    // AN OUTPUT THAT CAME OR WENT IS SAID (iOS routeChanged). iOS names the output left standing, the platform names the one that moved.
    // The pause itself stays with `noisy`. Registering lists the devices already there: that first list is no news.
    private val outs = object : AudioDeviceCallback() {
        private var listed = false
        override fun onAudioDevicesAdded(d: Array<AudioDeviceInfo>) { if (listed) tell("new", d); listed = true }
        override fun onAudioDevicesRemoved(d: Array<AudioDeviceInfo>) = tell("gone", d)
        private fun tell(how: String, d: Array<AudioDeviceInfo>) = d.filter { it.isSink }.forEach {
            android.util.Log.i("Montana", "music_route " + how + " out=" + port(it.type) + " paused=" + (if (MusicPlayer.playing) 0 else 1))
        }
        private fun port(type: Int) = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BluetoothA2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BluetoothHFP"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BluetoothLE"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USBAudio"
            else -> type.toString()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = this
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_music), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
        session = MediaSession(this, "Montana").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { remote("play"); MusicPlayer.resume() }
                override fun onPause() { remote("pause"); MusicPlayer.pause() }
                override fun onSkipToNext() { remote("next"); MusicPlayer.next() }
                override fun onSkipToPrevious() { remote("prev"); MusicPlayer.prev() }
                override fun onSeekTo(ms: Long) { val d = MusicPlayer.duration; if (d > 0) MusicPlayer.seek(ms / 1000.0 / d) }
                override fun onStop() = MusicPlayer.stop()
                override fun onCustomAction(action: String, extras: android.os.Bundle?) { if (action == CLOSE) MusicPlayer.stop() }
            })
            setSessionActivity(open())
            isActive = true
        }
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(outs, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // the notification's own buttons (before Android 13 the shade draws these; later it draws the session's)
        when (intent?.action) {
            "prev" -> { remote("prev"); MusicPlayer.prev() }
            "toggle" -> { remote("toggle paused=" + (if (MusicPlayer.playing) 0 else 1)); MusicPlayer.toggle() }
            "next" -> { remote("next"); MusicPlayer.next() }
            CLOSE -> MusicPlayer.stop()
        }
        // started in the foreground's name, the service stands there at once — even a track gone meanwhile is shown, then let go
        publish()
        if (MusicPlayer.current == null) end()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = null
        runCatching { unregisterReceiver(noisy) }
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(outs)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun end() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(this, ID,
        (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun button(icon: Int, label: String, action: String) = Notification.Action.Builder(
        android.graphics.drawable.Icon.createWithResource(this, icon), label,
        PendingIntent.getService(this, action.hashCode(), Intent(this, MusicService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
    ).build()

    /** The session learns the track and its state; the notification is drawn anew over them. */
    private fun publish() {
        val t = MusicPlayer.current
        val playing = MusicPlayer.playing
        val title = t?.title ?: getString(R.string.app_music)
        val art = t?.let { TrackArt.of(this, it) } ?: TrackArt.lockIcon(this)
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)   // USER-DATA: the track's own name
            // the artist its own tags name, never where the track lies; none named -- the app's name (iOS pushNowPlaying 779-786)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t?.artist ?: "Montana")
            // THE LOCK SCREEN'S PICTURE (the author's word 29.09 ~23:10): the track's own cover, else the app's own icon (TrackArt)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, (MusicPlayer.duration * 1000).toLong())
            .build())
        // the system's clock runs the position on from here by itself: nothing is said at each tick (iOS pushNowPlaying)
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                MusicPlayer.positionMs, if (playing) 1f else 0f, SystemClock.elapsedRealtime())
            .addCustomAction(PlaybackState.CustomAction.Builder(CLOSE, getString(R.string.music_close), R.drawable.ic_close).build())
            .build())
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)   // USER-DATA: the track's own name
            .setContentText(t?.artist ?: "Montana")
            .setLargeIcon(art)
            .setContentIntent(open())
            .setDeleteIntent(PendingIntent.getService(this, CLOSE.hashCode(), Intent(this, MusicService::class.java).setAction(CLOSE), PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(button(R.drawable.ic_skip_prev, getString(R.string.music_prev), "prev"))
            .addAction(button(if (playing) R.drawable.ic_pause_fill else R.drawable.ic_play_fill,
                getString(if (playing) R.string.music_pause else R.string.music_play), "toggle"))
            .addAction(button(R.drawable.ic_skip_next, getString(R.string.music_next), "next"))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
    }
}
