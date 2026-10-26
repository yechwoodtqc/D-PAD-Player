package com.example.dpadplayer.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import androidx.preference.PreferenceManager
import com.example.dpadplayer.MainActivity
import com.example.dpadplayer.MediaStoreScanner
import com.example.dpadplayer.R
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.collection.LruCache

class PlaybackService : Service() {

    companion object {
        const val COMMAND_PLAY        = "com.example.dpadplayer.action.PLAY"
        const val COMMAND_PAUSE       = "com.example.dpadplayer.action.PAUSE"
        const val COMMAND_NEXT        = "com.example.dpadplayer.action.NEXT"
        const val COMMAND_PREV        = "com.example.dpadplayer.action.PREV"
        const val COMMAND_SEEK_FWD    = "com.example.dpadplayer.action.SEEK_FWD"
        const val COMMAND_SEEK_BWD    = "com.example.dpadplayer.action.SEEK_BWD"
        const val COMMAND_SEEK_TO     = "com.example.dpadplayer.action.SEEK_TO"
        const val COMMAND_PLAY_INDEX  = "com.example.dpadplayer.action.PLAY_INDEX"
        const val COMMAND_PLAY_TRACK_ID = "com.example.dpadplayer.action.PLAY_TRACK_ID"
        const val COMMAND_STOP        = "com.example.dpadplayer.action.STOP"
        const val EXTRA_INDEX         = "index"
        const val EXTRA_POSITION      = "position"
        const val EXTRA_TRACK_ID      = "track_id"

        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2

        const val NOTIF_CHANNEL_ID = "dpad_player_channel"
        const val NOTIF_ID = 1
        private const val PREF_KEEP_SERVICE_WHEN_PAUSED = "keep_service_when_paused"
        private const val PREF_AUDIO_FOCUS_PAUSE_ON_DUCK = "audio_focus_pause_on_duck"

        private const val REQ_OPEN_APP = 100
        private const val REQ_PREV = 101
        private const val REQ_PLAY = 102
        private const val REQ_PAUSE = 103
        private const val REQ_NEXT = 104
        private const val REQ_STOP = 105

        private const val METADATA_NOTIFICATION_DEBOUNCE_MS = 300L
        private const val NOTIFICATION_PROGRESS_UPDATE_MS = 1000L
        private const val DUCK_VOLUME = 0.2f
        private const val NORMAL_VOLUME = 1.0f

        private val MEDIA_SESSION_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }
    private val binder = LocalBinder()

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: Any? = null
    private var isStoppingService = false
    private var pausedForTransientFocusLoss = false
    private var wasDucked = false
    private var lastNotificationProgressUpdateAt = 0L
    private var cachedArtUri: String? = null
    private var cachedArtBitmap: Bitmap? = null
    private val albumArtCache = LruCache<String, Bitmap>(16)
    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerEndAtMs: Long = -1L
    private var lastSleepRemainingBucket: Long = Long.MIN_VALUE

    val tracks = mutableListOf<Track>()
    var currentIndex = 0
        private set

    // Shuffle / repeat state
    var shuffleOn  = false
    var repeatMode = REPEAT_OFF  // 0=off, 1=all, 2=one
    // Shuffled play order (indices into tracks)
    private val shuffleOrder = mutableListOf<Int>()
    private var shufflePos   = 0  // position within shuffleOrder

    // Callbacks for bound UI
    var onTrackChanged: ((Int) -> Unit)? = null
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
    var onPositionChanged: ((Long) -> Unit)? = null
    var onShuffleChanged: ((Boolean) -> Unit)? = null
    var onRepeatChanged: ((Int) -> Unit)? = null
    var onQueueChanged: ((List<Track>) -> Unit)? = null
    var onSleepTimerChanged: ((Long) -> Unit)? = null

    // Seek-bar position polling
    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionRunnable: Runnable = object : Runnable {
        override fun run() {
            onPositionChanged?.invoke(player.currentPosition)
            emitSleepTimerRemainingIfNeeded()
            val now = SystemClock.elapsedRealtime()
            if (player.isPlaying && now - lastNotificationProgressUpdateAt >= NOTIFICATION_PROGRESS_UPDATE_MS) {
                lastNotificationProgressUpdateAt = now
                updateNotification()
            }
            mainHandler.postDelayed(this, 500)
        }
    }
    private val metadataNotificationRunnable = Runnable {
        if (!isStoppingService) {
            updateNotification()
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        player = ExoPlayer.Builder(this).build()
        // Coroutine scope for background work tied to Service lifecycle
        serviceScope = CoroutineScope(Dispatchers.Main + Job())

        mediaSession = MediaSessionCompat(this, "dpad_player").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                     MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()              { requestAudioFocusAndPlay() }
                override fun onPause()             { pausePlayback() }
                override fun onSkipToNext()        { next() }
                override fun onSkipToPrevious()    { prev() }
                override fun onStop()              { requestServiceStop(removeNotification = true) }
                override fun onSeekTo(pos: Long)   { player.seekTo(pos); updatePlaybackState() }
            })
            isActive = true
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                android.util.Log.d("PlaybackSvc", "onIsPlayingChanged isPlaying=$isPlaying")
                updatePlaybackState()
                if (isPlaying) {
                    tryStartForeground()
                    startPositionPolling()
                } else {
                    // When playback stops, decide whether the service should remain
                    // around (to show the notification and allow resume) or shut
                    // down entirely to avoid wasted background work and battery.
                    stopPositionPolling()
                    if (shouldStopServiceWhenIdle()) {
                        // Remove the notification and stop the service so it no longer
                        // appears in the status bar and doesn't keep the process alive.
                        requestServiceStop(removeNotification = true)
                    } else {
                        // API 23 foreground notifications cannot reliably be swiped away.
                        // Remove the service when genuinely paused by default, but never
                        // stop it while buffering or changing tracks.
                        if (!keepServiceWhenPaused() && !player.playWhenReady && !isTransitioning) {
                            requestServiceStop(removeNotification = true)
                            return
                        }
                        tryStartForeground()
                    }
                }
                onPlaybackStateChanged?.invoke(isPlaying)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) next()
            }
        })

        // Tracks are pushed in by MainActivity after binding; nothing to load here.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("PlaybackSvc", "onStartCommand action=${intent?.action} flags=$flags")
        // Must call startForeground() immediately for any startForegroundService() request,
        // regardless of playback state, to avoid RemoteServiceException.
        tryStartForeground()
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            COMMAND_PLAY       -> requestAudioFocusAndPlay()
            COMMAND_PAUSE      -> pausePlayback()
            COMMAND_NEXT       -> next()
            COMMAND_PREV       -> prev()
            COMMAND_SEEK_FWD   -> seekBy(configuredSeekStepMs())
            COMMAND_SEEK_BWD   -> seekBy(-configuredSeekStepMs())
            COMMAND_SEEK_TO    -> { intent.getLongExtra(EXTRA_POSITION, 0).let { player.seekTo(it); updatePlaybackState() } }
            COMMAND_STOP       -> {
                pausePlayback()
                requestServiceStop(removeNotification = true)
                return START_NOT_STICKY
            }
            COMMAND_PLAY_INDEX -> {
                val idx = intent.getIntExtra(EXTRA_INDEX, 0)
                prepareAndPlay(idx)
            }
            COMMAND_PLAY_TRACK_ID -> {
                val trackId = intent.getLongExtra(EXTRA_TRACK_ID, -1L)
                if (trackId > 0L) playTrackById(trackId)
            }
        }
        return START_STICKY
    }

    private fun configuredSeekStepMs(): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getString("seek_step", "15000")?.toLongOrNull() ?: 15_000L
    }

    private fun keepServiceWhenPaused(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean(PREF_KEEP_SERVICE_WHEN_PAUSED, false)
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it >= 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
        updatePlaybackState()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        android.util.Log.d("PlaybackSvc", "onDestroy — player released")
        stopPositionPolling()
        mainHandler.removeCallbacks(metadataNotificationRunnable)
        currentEnrichmentJob?.cancel()
        batchEnrichmentJob?.cancel()
        cachedArtBitmap = null
        cachedArtUri = null
        albumArtCache.evictAll()
        cancelSleepTimer()
        // Cancel background work
        serviceScope.cancel()
        abandonAudioFocus()
        mediaSession.isActive = false
        mediaSession.release()
        player.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ─── Playback control ─────────────────────────────────────────────────────

    fun prepareAndPlay(index: Int) {
        if (index < 0 || index >= tracks.size) { isTransitioning = false; return }
        isTransitioning = true
        currentIndex = index
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(tracks[index].uri))
        player.prepare()

        // Request audio focus before playing
        val gainedFocus = requestAudioFocus()
        if (gainedFocus) {
            player.play()
        }

        // Cancel any pending per-track enrichment for previous items
        currentEnrichmentJob?.cancel()
        // Launch a coroutine to lazily load embedded artwork and basic tags
        val ctx = applicationContext
        val t = tracks[index]
        currentEnrichmentJob = serviceScope.launch {
            // Run IO-bound work on IO dispatcher
            val enriched = withContext(Dispatchers.IO) {
                MediaStoreScanner.enrichTrack(ctx, t)
            }
            // If enrichment provided new album art or updated metadata, apply it
            ensureActive()
            if (index in tracks.indices) {
                val old = tracks[index]
                if (enriched != old) {
                    tracks[index] = enriched
                    // Notify listeners on main thread (we are already on Main)
                    onTrackChanged?.invoke(index)
                    if (player.isPlaying && index == currentIndex) updateMetadata(index)
                }
            }
        }

        updateMetadata(index)
        updatePlaybackState()
        onTrackChanged?.invoke(index)
        recordPlayStat(tracks[index].id)
        isTransitioning = false
    }

    private fun playTrackById(trackId: Long) {
        val existingIndex = tracks.indexOfFirst { it.id == trackId }
        if (existingIndex >= 0) {
            prepareAndPlay(existingIndex)
            return
        }
        serviceScope.launch {
            val loaded = withContext(Dispatchers.IO) { MediaStoreScanner.loadTracks(applicationContext, "title") }
            val idx = loaded.indexOfFirst { it.id == trackId }
            if (idx < 0) return@launch
            tracks.clear()
            tracks.addAll(loaded)
            prepareAndPlay(idx)
            notifyQueueChanged()
            restartBatchEnrichment()
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForTransientFocusLoss = false
                if (player.isPlaying) {
                    pausePlayback(releaseAudioFocus = false)
                }
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (player.isPlaying) {
                    pausedForTransientFocusLoss = true
                    pausePlayback(releaseAudioFocus = false)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (pauseWhenDucked()) {
                    if (player.isPlaying) {
                        pausedForTransientFocusLoss = true
                        pausePlayback(releaseAudioFocus = false)
                    }
                } else if (player.isPlaying) {
                    player.volume = DUCK_VOLUME
                    wasDucked = true
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasDucked) {
                    player.volume = NORMAL_VOLUME
                    wasDucked = false
                }
                if (pausedForTransientFocusLoss && !player.isPlaying) {
                    pausedForTransientFocusLoss = false
                    player.play()
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setWillPauseWhenDucked(pauseWhenDucked())
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun requestAudioFocusAndPlay() {
        if (requestAudioFocus()) {
            if (tracks.isEmpty()) return
            if (!player.isCurrentMediaItemSeekable && player.playbackState == Player.STATE_IDLE) {
                prepareAndPlay(currentIndex)
            } else {
                player.play()
            }
        }
    }

    private fun pausePlayback(releaseAudioFocus: Boolean = true) {
        player.pause()
        if (releaseAudioFocus) {
            pausedForTransientFocusLoss = false
            abandonAudioFocus()
        }
    }

    private fun pauseWhenDucked(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean(PREF_AUDIO_FOCUS_PAUSE_ON_DUCK, true)
    }

    private fun abandonAudioFocus() {
        if (wasDucked) {
            player.volume = NORMAL_VOLUME
            wasDucked = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (audioFocusRequest as? AudioFocusRequest)?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun notifyQueueChanged() {
        onQueueChanged?.invoke(tracks.toList())
    }

    fun playTracks(newTracks: List<Track>, startIndex: Int) {
        tracks.clear()
        tracks.addAll(newTracks)
        if (shuffleOn) buildShuffleOrder()
        prepareAndPlay(startIndex)
        notifyQueueChanged()
        // Restart batch enrichment when a new queue is played
        restartBatchEnrichment()
    }

    private var isTransitioning = false
    // Coroutine scope and jobs for background enrichment
    private lateinit var serviceScope: CoroutineScope
    private var currentEnrichmentJob: Job? = null
    private var batchEnrichmentJob: Job? = null

    fun next() {
        if (isTransitioning) return
        isTransitioning = true
        if (tracks.isEmpty()) { isTransitioning = false; return }
        when {
            repeatMode == REPEAT_ONE -> {
                player.seekTo(0)
                player.play()
                isTransitioning = false
                return
            }
            shuffleOn -> {
                shufflePos = (shufflePos + 1) % shuffleOrder.size
                prepareAndPlay(shuffleOrder[shufflePos])
            }
            repeatMode == REPEAT_ALL -> prepareAndPlay((currentIndex + 1) % tracks.size)
            currentIndex < tracks.size - 1 -> prepareAndPlay(currentIndex + 1)
            else -> { player.seekTo(0); player.pause() } // end of list, stop
        }
    }

    fun prev() {
        if (isTransitioning) return
        isTransitioning = true
        if (tracks.isEmpty()) { isTransitioning = false; return }
        if (player.currentPosition > 3_000) {
            player.seekTo(0); updatePlaybackState(); isTransitioning = false; return
        }
        when {
            shuffleOn -> {
                shufflePos = ((shufflePos - 1) + shuffleOrder.size) % shuffleOrder.size
                prepareAndPlay(shuffleOrder[shufflePos])
            }
            else -> prepareAndPlay(if (currentIndex - 1 < 0) tracks.size - 1 else currentIndex - 1)
        }
    }

    fun toggleShuffle() {
        shuffleOn = !shuffleOn
        if (shuffleOn) buildShuffleOrder()
        onShuffleChanged?.invoke(shuffleOn)
    }

    fun playNextTrack(track: Track) {
        if (tracks.isEmpty()) {
            tracks.add(track)
            prepareAndPlay(0)
            notifyQueueChanged()
            return
        }
        val insertIndex = currentIndex + 1
        tracks.add(insertIndex, track)
        if (shuffleOn) {
            shuffleOrder.add(shufflePos + 1, insertIndex)
            for (i in 0 until shuffleOrder.size) {
                if (i != (shufflePos + 1) && shuffleOrder[i] >= insertIndex) {
                    shuffleOrder[i]++
                }
            }
        }
        notifyQueueChanged()
    }

    fun enqueueTrack(track: Track) {
        if (tracks.isEmpty()) {
            tracks.add(track)
            prepareAndPlay(0)
            notifyQueueChanged()
            return
        }
        tracks.add(track)
        if (shuffleOn) {
            shuffleOrder.add(tracks.size - 1)
        }
        notifyQueueChanged()
        restartBatchEnrichment()
    }

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        onRepeatChanged?.invoke(repeatMode)
    }

    private fun buildShuffleOrder() {
        shuffleOrder.clear()
        shuffleOrder.addAll((tracks.indices).toMutableList().also { it.shuffle() })
        shufflePos = shuffleOrder.indexOf(currentIndex).coerceAtLeast(0)
    }

    // Start or restart the background batch enrichment worker. The worker runs
    // slowly across the queue, enriching one track roughly every second to
    // avoid resource spikes on large libraries.
    private fun restartBatchEnrichment() {
        batchEnrichmentJob?.cancel()
        batchEnrichmentJob = serviceScope.launch {
            val snapshot = tracks.toList()
            for (i in snapshot.indices) {
                val t = snapshot[i]
                if (t.filePath.isNotBlank() && t.albumArtUri == t.mediaStoreAlbumArtUri) {
                    val enriched = withContext(Dispatchers.IO) { MediaStoreScanner.enrichTrack(applicationContext, t) }
                    ensureActive()
                    if (i in tracks.indices && enriched != tracks[i]) {
                        tracks[i] = enriched
                        mainHandler.post { onQueueChanged?.invoke(tracks.toList()) }
                    }
                }
                try { delay(1000L) } catch (_: Exception) { break }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        updatePlaybackState()
    }

    // Decide whether the service should stop when playback is not active.
    // We stop the service when there's no queue (tracks empty) or when the
    // player is idle/ended and user hasn't left a paused session to resume.
    private fun shouldStopServiceWhenIdle(): Boolean {
        // During track transitions the player briefly enters STATE_IDLE — don't stop.
        if (isTransitioning) return false
        // If there are no tracks queued, nothing to show — stop the service.
        if (tracks.isEmpty()) return true
        // If player is in an ended or idle state and not playing, stop.
        val state = player.playbackState
        if (!player.isPlaying && (state == Player.STATE_IDLE || state == Player.STATE_ENDED)) return true
        // Otherwise keep the service alive so the user can resume playback.
        return false
    }

    private fun requestServiceStop(removeNotification: Boolean) {
        if (isStoppingService) return
        android.util.Log.d("PlaybackSvc", "requestServiceStop removeNotification=$removeNotification")
        isStoppingService = true
        stopPositionPolling()
        mainHandler.removeCallbacks(metadataNotificationRunnable)
        currentEnrichmentJob?.cancel()
        batchEnrichmentJob?.cancel()
        cachedArtBitmap = null
        cachedArtUri = null
        albumArtCache.evictAll()
        abandonAudioFocus()
        if (removeNotification) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
        stopSelf()
    }

    val isPlaying get() = player.isPlaying
    val currentPosition get() = player.currentPosition
    val duration get() = if (currentIndex in tracks.indices) tracks[currentIndex].duration else 0L
    val audioSessionId get() = player.audioSessionId

    fun setPlaybackSpeed(speed: Float) {
        val value = speed.coerceIn(0.5f, 2.0f)
        player.setPlaybackSpeed(value)
        updatePlaybackState()
    }

    fun getPlaybackSpeed(): Float = player.playbackParameters.speed

    fun startSleepTimer(durationMs: Long) {
        cancelSleepTimer()
        if (durationMs <= 0L) return
        sleepTimerEndAtMs = SystemClock.elapsedRealtime() + durationMs
        lastSleepRemainingBucket = Long.MIN_VALUE
        onSleepTimerChanged?.invoke(durationMs)
        sleepTimerRunnable = Runnable {
            pausePlayback()
            sleepTimerEndAtMs = -1L
            lastSleepRemainingBucket = Long.MIN_VALUE
            onSleepTimerChanged?.invoke(-1L)
            requestServiceStop(removeNotification = true)
        }.also { runnable ->
            mainHandler.postDelayed(runnable, durationMs)
        }
    }

    fun cancelSleepTimer() {
        sleepTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepTimerRunnable = null
        sleepTimerEndAtMs = -1L
        lastSleepRemainingBucket = Long.MIN_VALUE
        onSleepTimerChanged?.invoke(-1L)
    }

    fun sleepTimerRemainingMs(): Long {
        if (sleepTimerEndAtMs <= 0L) return -1L
        return (sleepTimerEndAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    // ─── MediaSession state ───────────────────────────────────────────────────

    private fun updatePlaybackState() {
        val state = if (player.isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MEDIA_SESSION_ACTIONS)
                .setState(state, player.currentPosition, player.playbackParameters.speed)
                .build()
        )
    }

    private fun updateMetadata(index: Int) {
        if (index < 0 || index >= tracks.size) return
        val t = tracks[index]
        val artBitmap = loadAlbumArtBitmap(t.albumArtUri)
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  t.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, t.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,  t.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, t.duration)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, t.albumArtUri.toString())
        if (artBitmap != null) {
            // Embedding the bitmap drives the lock screen album art card directly
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artBitmap)
        }
        mediaSession.setMetadata(builder.build())
        scheduleNotificationRefreshFromMetadata()
    }

    private fun scheduleNotificationRefreshFromMetadata() {
        if (isStoppingService) return
        mainHandler.removeCallbacks(metadataNotificationRunnable)
        mainHandler.postDelayed(metadataNotificationRunnable, METADATA_NOTIFICATION_DEBOUNCE_MS)
    }

    // Decode album art from a URI into a Bitmap suitable for the notification and lock screen.
    // Returns null gracefully if the URI is empty, null, or unreadable.
    private fun loadAlbumArtBitmap(uri: Uri?): Bitmap? {
        if (uri == null || uri == Uri.EMPTY) return null
        val key = uri.toString()
        albumArtCache.get(key)?.let { return it }
        if (cachedArtUri == key && cachedArtBitmap != null) {
            return cachedArtBitmap
        }
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.also { decoded ->
                    cachedArtUri = key
                    cachedArtBitmap = decoded
                    albumArtCache.put(key, decoded)
                }
            }
        } catch (_: Exception) {
            if (cachedArtUri == key) {
                cachedArtUri = null
                cachedArtBitmap = null
            }
            null
        }
    }

    fun moveQueueItem(from: Int, to: Int): Boolean {
        if (from !in tracks.indices || to !in tracks.indices || from == to) return false
        val moved = tracks.removeAt(from)
        tracks.add(to, moved)

        currentIndex = when {
            currentIndex == from -> to
            from < currentIndex && to >= currentIndex -> currentIndex - 1
            from > currentIndex && to <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }

        if (shuffleOn) {
            buildShuffleOrder()
        }
        onTrackChanged?.invoke(currentIndex)
        notifyQueueChanged()
        return true
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID, "DPAD Music",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): android.app.Notification {
        createNotificationChannel()
        val t = if (currentIndex in tracks.indices) tracks[currentIndex] else null

        // Tapping the notification opens the app
        val openIntent = PendingIntent.getActivity(
            this, REQ_OPEN_APP, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevPendingIntent = PendingIntent.getService(
            this,
            REQ_PREV,
            Intent(this, PlaybackService::class.java).setAction(COMMAND_PREV),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPendingIntent = PendingIntent.getService(
            this,
            REQ_PLAY,
            Intent(this, PlaybackService::class.java).setAction(COMMAND_PLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pausePendingIntent = PendingIntent.getService(
            this,
            REQ_PAUSE,
            Intent(this, PlaybackService::class.java).setAction(COMMAND_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextPendingIntent = PendingIntent.getService(
            this,
            REQ_NEXT,
            Intent(this, PlaybackService::class.java).setAction(COMMAND_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop / dismiss action — sends COMMAND_STOP which pauses and calls stopSelf()
        val stopPendingIntent = PendingIntent.getService(
            this, REQ_STOP,
            Intent(this, PlaybackService::class.java).setAction(COMMAND_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Custom API 23 layout uses icon-only buttons; keep text labels on newer APIs.
        val oldNotificationUi = Build.VERSION.SDK_INT <= Build.VERSION_CODES.M
        val prevTitle = if (oldNotificationUi) "" else getString(R.string.notification_action_previous)
        val playTitle = if (oldNotificationUi) "" else getString(
            if (player.isPlaying) R.string.notification_action_pause else R.string.notification_action_play
        )
        val nextTitle = if (oldNotificationUi) "" else getString(R.string.notification_action_next)
        val stopTitle = if (oldNotificationUi) "" else getString(R.string.notification_action_stop)
        val prevAction = NotificationCompat.Action.Builder(R.drawable.ic_skip_previous, prevTitle, prevPendingIntent).build()
        val playPauseAction = if (player.isPlaying) {
            NotificationCompat.Action.Builder(R.drawable.ic_pause, playTitle, pausePendingIntent).build()
        } else {
            NotificationCompat.Action.Builder(R.drawable.ic_play, playTitle, playPendingIntent).build()
        }
        val nextAction = NotificationCompat.Action.Builder(R.drawable.ic_skip_next, nextTitle, nextPendingIntent).build()
        val stopAction = NotificationCompat.Action.Builder(R.drawable.ic_stop, stopTitle, stopPendingIntent).build()

        // Load album art bitmap for the large icon (notification) and lock screen
        val artBitmap = t?.albumArtUri?.let { loadAlbumArtBitmap(it) }

        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(t?.title ?: getString(R.string.app_name))
            .setContentText(t?.let { "${it.artist} — ${it.album}" } ?: "")
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(player.isPlaying)
            .addAction(prevAction)        // index 0
            .addAction(playPauseAction)   // index 1
            .addAction(nextAction)        // index 2
            .addAction(stopAction)        // index 3
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    // Show prev, play/pause, next in the collapsed single-line view
                    .setShowActionsInCompactView(0, 1, 2)
                    // Show the cancel/stop button so swiping expanded shows it
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopPendingIntent)
            )

        // API 23/non-touch devices get fixed-width focusable icon controls instead
        // of the stock action row which expands text buttons across the notification.
        if (oldNotificationUi) {
            val playPausePendingIntent = if (player.isPlaying) pausePendingIntent else playPendingIntent
            val compact = RemoteViews(packageName, R.layout.notification_player_compact)
            populateNotificationRemoteViews(
                compact, t, openIntent, prevPendingIntent, playPausePendingIntent, nextPendingIntent, null
            )
            builder.setCustomContentView(compact)
            val expanded = RemoteViews(packageName, R.layout.notification_player_expanded)
            populateNotificationRemoteViews(
                expanded, t, openIntent, prevPendingIntent, playPausePendingIntent, nextPendingIntent, stopPendingIntent
            )
            builder.setCustomBigContentView(expanded)
        }

        // Album art as large icon in the notification shade
        if (artBitmap != null) {
            builder.setLargeIcon(artBitmap)
        }

        // Seekbar: on Android 13+ (API 33) NotificationCompat supports progress natively
        // through MediaStyle + MediaSession; the system reads position from PlaybackState.
        // For pre-33 we set a determinate progress bar as a fallback.
        val duration = t?.duration ?: 0L
        val position = player.currentPosition
        if (duration > 0) {
            builder.setProgress(duration.toInt().coerceAtLeast(1), position.toInt().coerceIn(0, duration.toInt()), false)
        }

        return builder.build()
    }

    private fun populateNotificationRemoteViews(
        views: RemoteViews,
        track: Track?,
        openIntent: PendingIntent,
        prevPendingIntent: PendingIntent,
        playPausePendingIntent: PendingIntent,
        nextPendingIntent: PendingIntent,
        stopPendingIntent: PendingIntent?
    ) {
        views.setTextViewText(R.id.notification_title, track?.title ?: getString(R.string.app_name))
        views.setTextViewText(R.id.notification_subtitle, track?.let { it.artist + " — " + it.album } ?: "")
        views.setOnClickPendingIntent(R.id.notification_container, openIntent)
        views.setOnClickPendingIntent(R.id.notification_previous, prevPendingIntent)
        views.setContentDescription(R.id.notification_previous, getString(R.string.notification_action_previous))
        views.setOnClickPendingIntent(R.id.notification_play_pause, playPausePendingIntent)
        views.setImageViewResource(
            R.id.notification_play_pause,
            if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setContentDescription(
            R.id.notification_play_pause,
            getString(if (player.isPlaying) R.string.notification_action_pause else R.string.notification_action_play)
        )
        views.setOnClickPendingIntent(R.id.notification_next, nextPendingIntent)
        views.setContentDescription(R.id.notification_next, getString(R.string.notification_action_next))
        if (stopPendingIntent != null) {
            views.setOnClickPendingIntent(R.id.notification_stop, stopPendingIntent)
            views.setContentDescription(R.id.notification_stop, getString(R.string.notification_action_stop))
        }
    }

    private fun tryStartForeground() {
        try {
            android.util.Log.d("PlaybackSvc", "tryStartForeground calling startForeground")
            startForeground(NOTIF_ID, buildNotification())
            android.util.Log.d("PlaybackSvc", "tryStartForeground succeeded")
        } catch (e: Exception) {
            android.util.Log.e("PlaybackSvc", "tryStartForeground FAILED", e)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun recordPlayStat(trackId: Long) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = com.example.dpadplayer.db.AppDatabase.getInstance(applicationContext)
                db.playStatsDao().recordPlay(trackId, System.currentTimeMillis())
            } catch (_: Exception) { }
        }
    }

    private fun emitSleepTimerRemainingIfNeeded() {
        val remaining = sleepTimerRemainingMs()
        val bucket = if (remaining < 0L) -1L else (remaining / 1000L)
        if (bucket != lastSleepRemainingBucket) {
            lastSleepRemainingBucket = bucket
            onSleepTimerChanged?.invoke(remaining)
        }
    }

    // ─── Position polling ─────────────────────────────────────────────────────

    private fun startPositionPolling() {
        stopPositionPolling()
        mainHandler.post(positionRunnable)
    }

    private fun stopPositionPolling() {
        mainHandler.removeCallbacks(positionRunnable)
    }
}
