package com.sonicsphere.audio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.sonicsphere.audio.metadata.AlbumArtExtractor
import com.sonicsphere.audio.MainActivity
import com.sonicsphere.audio.metadata.Music
import com.sonicsphere.audio.R
import java.io.File

class MusicService : Service() {

    private val binder = MusicBinder()
    private var notificationUpdateRunnable: Runnable? = null

    // STREAMING PLAYER
    private var player: StreamingAudioPlayer? = null

    // AUDIO EFFECTS
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var isEqualizerEnabled = false
    private var isBassBoostEnabled = false

    private var currentMusicIndex = 0
    private var musicList: MutableList<Music> = mutableListOf()
    private var isPrepared = false
    private var isShuffling = false
    private var repeatMode = REPEAT_NONE
    private var isServiceStopping = false

    private val channelId = "music_player_channel"
    private val notificationId = 1

    // Controles externos
    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var mediaButtonReceiver: BroadcastReceiver? = null

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_PLAY = "com.sonicsphere.audio.ACTION_PLAY"
        const val ACTION_PAUSE = "com.sonicsphere.audio.ACTION_PAUSE"
        const val ACTION_NEXT = "com.sonicsphere.audio.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.sonicsphere.audio.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.sonicsphere.audio.ACTION_STOP"
        const val ACTION_TOGGLE_SHUFFLE = "com.sonicsphere.audio.ACTION_TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.sonicsphere.audio.ACTION_TOGGLE_REPEAT"
        const val ACTION_SEEK_FORWARD = "com.sonicsphere.audio.ACTION_SEEK_FORWARD"
        const val ACTION_SEEK_BACKWARD = "com.sonicsphere.audio.ACTION_SEEK_BACKWARD"

        const val REPEAT_NONE = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2

        private var instance: MusicService? = null
        fun getInstance(): MusicService? = instance

        private const val PREFS_NAME = "music_prefs"
        private const val KEY_CURRENT_PATH = "current_path"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_SHUFFLE = "shuffle_mode"
        private const val KEY_REPEAT = "repeat_mode"
        private const val KEY_IS_PLAYING = "is_playing"
        private const val KEY_LAST_MUSIC_PATH = "last_music_path"
        private const val KEY_FAVORITES = "favorite_musics"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_HAAS_DELAY = "haas_delay_ms"
        private const val KEY_EQUALIZER_ENABLED = "equalizer_enabled"
        private const val KEY_BASS_BOOST_ENABLED = "bass_boost_enabled"
        private const val KEY_BASS_BOOST_STRENGTH = "bass_boost_strength"
        private const val KEY_PITCH = "pitch_semitones"
        private const val KEY_SPEED = "speed_factor"
        private const val KEY_REVERSE = "reverse_enabled"

        private const val SEEK_THRESHOLD_MS = 5000 // 5 segundos
        private const val SEEK_JUMP_MS = 10000 // 10 segundos para avançar/retroceder
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, true).apply()

        createNotificationChannel()
        setupMediaSession()
        registerAudioFocus()
        registerMediaButtonReceiver()

        if (shouldStartForeground()) {
            startForegroundService()
        }

        startNotificationUpdate()

        Log.d("MusicService", "✅ Service criado")

        restoreState()

        val savedHaasDelay = getHaasDelay()
        if (savedHaasDelay > 0) {
            Log.d("MusicService", "🎧 Haas inicial configurado: ${savedHaasDelay}ms")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY -> resumeMusic()
                ACTION_PAUSE -> pauseMusic()
                ACTION_NEXT -> playNext()
                ACTION_PREVIOUS -> handlePreviousWithThreshold()
                ACTION_SEEK_FORWARD -> seekForward()
                ACTION_SEEK_BACKWARD -> seekBackward()
                ACTION_STOP -> {
                    stopMusicCompletely()
                    return START_NOT_STICKY
                }
                ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
                ACTION_TOGGLE_REPEAT -> toggleRepeat()
            }
        }

        if (Intent.ACTION_MEDIA_BUTTON == intent?.action) {
            val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }

            event?.let {
                if (it.action == KeyEvent.ACTION_DOWN) {
                    when (it.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> resumeMusic()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> pauseMusic()
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlayPause()
                        KeyEvent.KEYCODE_MEDIA_NEXT -> playNext()
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> handlePreviousWithThreshold()
                        KeyEvent.KEYCODE_MEDIA_STOP -> stopMusicCompletely()
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekForward()
                        KeyEvent.KEYCODE_MEDIA_REWIND -> seekBackward()
                    }
                }
            }
        }

        if (intent?.action == null && shouldRestorePlayback()) {
            restorePlaybackState()
        }

        return START_NOT_STICKY
    }

    // ========== SEEK FORWARD/BACKWARD ==========

    fun seekForward() {
        try {
            val currentPos = getCurrentPosition()
            val duration = getDuration()
            val newPos = (currentPos + SEEK_JUMP_MS).coerceAtMost(duration)
            seekTo(newPos)
            Log.d("MusicService", "⏩ +10s: ${formatTime(newPos)}")
        } catch (e: Exception) {
            Log.e("MusicService", "❌ Erro ao avançar", e)
        }
    }

    fun seekBackward() {
        try {
            val currentPos = getCurrentPosition()
            val newPos = (currentPos - SEEK_JUMP_MS).coerceAtLeast(0)
            seekTo(newPos)
            Log.d("MusicService", "⏪ -10s: ${formatTime(newPos)}")
        } catch (e: Exception) {
            Log.e("MusicService", "❌ Erro ao retroceder", e)
        }
    }

    // ========== PITCH, SPEED, REVERSE ==========

    fun setPitch(semitones: Int) {
        player?.setPitch(semitones)
        prefs.edit().putInt(KEY_PITCH, semitones).apply()
        Log.d("MusicService", "🎵 Pitch: ${semitones} semitons")
    }

    fun getPitch(): Int {
        return prefs.getInt(KEY_PITCH, 0)
    }

    fun setSpeed(speedFactor: Float) {
        player?.setSpeed(speedFactor)
        prefs.edit().putFloat(KEY_SPEED, speedFactor).apply()
        Log.d("MusicService", "⚡ Velocidade: ${speedFactor}x")
    }

    fun getSpeed(): Float {
        return prefs.getFloat(KEY_SPEED, 1.0f)
    }

    fun setReverse(enabled: Boolean) {
        player?.setReverse(enabled)
        prefs.edit().putBoolean(KEY_REVERSE, enabled).apply()
        Log.d("MusicService", "🔄 Reverse: $enabled")
    }

    fun isReversed(): Boolean {
        return prefs.getBoolean(KEY_REVERSE, false)
    }

    // ========== AUDIO EFFECTS ==========

    private fun setupAudioEffects() {
        try {
            val audioSessionId = player?.getAudioSessionId() ?: return

            // Setup Equalizer
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = false
            }

            // Setup Bass Boost
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = false
            }

            restoreAudioEffectsSettings()

            Log.d("MusicService", "✅ Efeitos de áudio configurados")
        } catch (e: Exception) {
            Log.e("MusicService", "❌ Erro ao configurar efeitos de áudio", e)
        }
    }

    private fun restoreAudioEffectsSettings() {
        // Restaurar Equalizer
        isEqualizerEnabled = prefs.getBoolean(KEY_EQUALIZER_ENABLED, false)
        equalizer?.enabled = isEqualizerEnabled

        // Restaurar Bass Boost
        isBassBoostEnabled = prefs.getBoolean(KEY_BASS_BOOST_ENABLED, false)
        bassBoost?.enabled = isBassBoostEnabled

        val savedStrength = prefs.getInt(KEY_BASS_BOOST_STRENGTH, 0)
        if (savedStrength > 0) {
            bassBoost?.setStrength(savedStrength.toShort())
        }

        // Restaurar bandas do equalizer
        equalizer?.let { eq ->
            for (band in 0 until eq.numberOfBands) {
                val savedLevel = prefs.getInt("eq_band_$band", 0)
                eq.setBandLevel(band.toShort(), savedLevel.toShort())
            }
        }
    }

    private fun saveAudioEffectsSettings() {
        prefs.edit().apply {
            putBoolean(KEY_EQUALIZER_ENABLED, isEqualizerEnabled)
            putBoolean(KEY_BASS_BOOST_ENABLED, isBassBoostEnabled)

            bassBoost?.let {
                putInt(KEY_BASS_BOOST_STRENGTH, it.roundedStrength.toInt())
            }

            equalizer?.let { eq ->
                for (band in 0 until eq.numberOfBands) {
                    putInt("eq_band_$band", eq.getBandLevel(band.toShort()).toInt())
                }
            }
        }.apply()
    }

    // EQUALIZER
    fun isEqualizerEnabled(): Boolean = isEqualizerEnabled

    fun setEqualizerEnabled(enabled: Boolean) {
        isEqualizerEnabled = enabled
        equalizer?.enabled = enabled
        saveAudioEffectsSettings()
        Log.d("MusicService", "🎚️ Equalizer ${if (enabled) "ativado" else "desativado"}")
    }

    fun getEqualizerNumberOfBands(): Short? = equalizer?.numberOfBands

    fun getEqualizerBandLevelRange(): ShortArray? = equalizer?.bandLevelRange

    fun getEqualizerCenterFreq(band: Short): Int? = equalizer?.getCenterFreq(band)

    fun getEqualizerBandLevel(band: Short): Short? = equalizer?.getBandLevel(band)

    fun setEqualizerBandLevel(band: Short, level: Short) {
        equalizer?.setBandLevel(band, level)
        saveAudioEffectsSettings()
    }

    fun getEqualizerPresetNames(): Array<String>? {
        return try {
            val count = equalizer?.numberOfPresets ?: return null
            Array(count.toInt()) { index ->
                equalizer?.getPresetName(index.toShort()) ?: "Preset $index"
            }
        } catch (e: Exception) {
            null
        }
    }

    fun setEqualizerPreset(preset: Short) {
        try {
            equalizer?.usePreset(preset)
            prefs.edit().putInt("equalizer_preset", preset.toInt()).apply()
            Log.d("MusicService", "🎚️ Preset aplicado: $preset")
        } catch (e: Exception) {
            Log.e("MusicService", "❌ Erro ao aplicar preset", e)
        }
    }

    // BASS BOOST
    fun isBassBoostEnabled(): Boolean = isBassBoostEnabled

    fun setBassBoostEnabled(enabled: Boolean) {
        isBassBoostEnabled = enabled
        bassBoost?.enabled = enabled
        saveAudioEffectsSettings()
        Log.d("MusicService", "🔊 Bass Boost ${if (enabled) "ativado" else "desativado"}")
    }

    fun getBassBoostStrength(): Short? = bassBoost?.roundedStrength

    fun setBassBoostStrength(strength: Short) {
        try {
            bassBoost?.setStrength(strength)
            saveAudioEffectsSettings()
            Log.d("MusicService", "🔊 Bass Boost: $strength")
        } catch (e: Exception) {
            Log.e("MusicService", "❌ Erro ao definir Bass Boost", e)
        }
    }

    // ========== CONTROLES EXTERNOS ==========

    private fun setupMediaSession() {
        try {
            mediaSession = MediaSession(this, "MusicService")
            mediaSession?.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            mediaSession?.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    resumeMusic()
                }

                override fun onPause() {
                    pauseMusic()
                }

                override fun onSkipToNext() {
                    playNext()
                }

                override fun onSkipToPrevious() {
                    handlePreviousWithThreshold()
                }

                override fun onStop() {
                    stopMusicCompletely()
                }

                override fun onSeekTo(pos: Long) {
                    seekTo(pos.toInt())
                }

                override fun onFastForward() {
                    seekForward()
                }

                override fun onRewind() {
                    seekBackward()
                }
            })

            mediaSession?.isActive = true
            updateMediaSessionState()

            Log.d("MusicService", "✅ Media Session configurada")
        } catch (e: Exception) {
            Log.e("MusicService", "❌ Erro ao configurar Media Session", e)
        }
    }

    private fun registerAudioFocus() {
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val result = audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.d("MusicService", "✅ Foco de áudio concedido")
            } else {
                Log.w("MusicService", "⚠️ Foco de áudio não concedido")
            }
        } catch (e: Exception) {
            Log.e("MusicService", "❌ Erro ao registrar foco de áudio", e)
        }
    }

    private fun registerMediaButtonReceiver() {
        mediaButtonReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (Intent.ACTION_MEDIA_BUTTON == intent?.action) {
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    }

                    event?.let {
                        if (it.action == KeyEvent.ACTION_DOWN) {
                            Log.d("MusicService", "📱 Botão de mídia pressionado: ${it.keyCode}")
                            when (it.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PLAY -> resumeMusic()
                                KeyEvent.KEYCODE_MEDIA_PAUSE -> pauseMusic()
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlayPause()
                                KeyEvent.KEYCODE_MEDIA_NEXT -> playNext()
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> handlePreviousWithThreshold()
                                KeyEvent.KEYCODE_MEDIA_STOP -> stopMusicCompletely()
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekForward()
                                KeyEvent.KEYCODE_MEDIA_REWIND -> seekBackward()
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_MEDIA_BUTTON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mediaButtonReceiver?.let {
                registerReceiver(it, filter, Context.RECEIVER_EXPORTED)
            }
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            mediaButtonReceiver?.let {
                registerReceiver(it, filter)
            }
        }
    }

        // Substitui o audioFocusChangeListener existente no MusicService
    private var wasPlayingBeforeFocusLoss = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Perda permanente — pausa e não retoma automaticamente
                wasPlayingBeforeFocusLoss = false
                if (isPlaying()) pauseMusic()
                Log.d("MusicService", "🔇 Foco perdido permanentemente")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Perda temporária — guarda estado para retomar depois
                wasPlayingBeforeFocusLoss = isPlaying()
                if (wasPlayingBeforeFocusLoss) pauseMusic()
                Log.d("MusicService", "⏸️ Foco perdido temporariamente")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d("MusicService", "🔈 Ducking")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Retoma só se estava tocando antes da perda transiente
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    resumeMusic()
                }
                Log.d("MusicService", "🔊 Foco recuperado")
            }
        }
    }


    fun handlePreviousWithThreshold() {
        val currentPosition = getCurrentPosition()
        val wasPlaying = isPlaying()

        if (currentPosition > SEEK_THRESHOLD_MS) {
            seekTo(0)

            if (wasPlaying && !isPlaying()) {
                handler.postDelayed({
                    resumeMusic()
                }, 100)
            }

            Log.d("MusicService", "⏪ Voltar ao início (posição: ${formatTime(currentPosition)})")
        } else {
            playPrevious()
            Log.d("MusicService", "⏮️ Música anterior (posição: ${formatTime(currentPosition)})")
        }

        updateMediaSessionState()
    }

    private fun updateMediaSessionState() {
        try {
            val currentMusic = getCurrentMusic()
            val playbackState = if (isPlaying()) {
                PlaybackState.STATE_PLAYING
            } else {
                PlaybackState.STATE_PAUSED
            }

            val stateBuilder = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SEEK_TO or
                            PlaybackState.ACTION_STOP or
                            PlaybackState.ACTION_FAST_FORWARD or
                            PlaybackState.ACTION_REWIND
                )
                .setState(playbackState, getCurrentPosition().toLong(), 1.0f)

            mediaSession?.setPlaybackState(stateBuilder.build())

            currentMusic?.let { music ->
                val metadataBuilder = android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, music.title)
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, music.artist)
                    .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, music.album)
                    .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, getDuration().toLong())

                mediaSession?.setMetadata(metadataBuilder.build())
            }
        } catch (e: Exception) {
            Log.e("MusicService", "❌ Erro ao atualizar Media Session", e)
        }
    }

    private fun togglePlayPause() {
        if (isPlaying()) {
            pauseMusic()
        } else {
            resumeMusic()
        }
    }

    // ========== MÉTODOS EXISTENTES ==========

    private fun shouldStartForeground(): Boolean {
        val wasPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)
        val hasMusicPath = prefs.getString(KEY_LAST_MUSIC_PATH, "")?.isNotEmpty() == true
        return wasPlaying && hasMusicPath
    }

    private fun shouldRestorePlayback(): Boolean {
        val wasPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)
        val hasMusicPath = prefs.getString(KEY_LAST_MUSIC_PATH, "")?.isNotEmpty() == true
        return wasPlaying && hasMusicPath && musicList.isEmpty()
    }

    private fun startForegroundService() {
        val notification = createNotification(null)
        startForeground(notificationId, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Music Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        if (isServiceStopping) return

        val currentMusic = getCurrentMusic()
        val notification = createNotification(currentMusic)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (shouldStartForeground()) {
            startForeground(notificationId, notification)
        } else {
            notificationManager.notify(notificationId, notification)
        }
    }

    private fun createNotification(music: Music?): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            action = "OPEN_EXISTING"
            putExtra("FROM_NOTIFICATION", true)
            putExtra("RESTORE_PLAYBACK", true)
        }

        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // PendingIntents para controles
        val seekBackwardIntent = createActionIntent(ACTION_SEEK_BACKWARD, 1)
        val previousIntent = createActionIntent(ACTION_PREVIOUS, 2)
        val playPauseIntent = createActionIntent(if (isPlaying()) ACTION_PAUSE else ACTION_PLAY, 3)
        val nextIntent = createActionIntent(ACTION_NEXT, 4)
        val seekForwardIntent = createActionIntent(ACTION_SEEK_FORWARD, 5)
        val stopIntent = createActionIntent(ACTION_STOP, 6)

        val playPauseIcon = if (isPlaying()) R.drawable.ic_pause else R.drawable.ic_play

        val contentText = buildString {
            append(music?.artist ?: "Artista desconhecido")
            append(" • ")
            append(formatTime(getCurrentPosition()))
            append(" / ")
            append(formatTime(getDuration()))
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(music?.title ?: "SonicSphere")
            .setContentText(contentText)
            .setSubText(if (isPlaying()) "Tocando" else "Pausado")
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.album_placeholder))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_replay_10, "−10s", seekBackwardIntent)
            .addAction(R.drawable.ic_skip_previous, "Anterior", previousIntent)
            .addAction(playPauseIcon, if (isPlaying()) "Pausar" else "Tocar", playPauseIntent)
            .addAction(R.drawable.ic_skip_next, "Próxima", nextIntent)
            .addAction(R.drawable.ic_forward_10, "+10s", seekForwardIntent)
            .addAction(R.drawable.ic_stop1, "Parar", stopIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationBuilder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        notificationBuilder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(1, 2, 3) // Previous, Play/Pause, Next
                .setShowCancelButton(true)
                .setCancelButtonIntent(stopIntent)
        )

        return notificationBuilder.build()
    }

    private fun createActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startNotificationUpdate() {
        stopNotificationUpdate()

        notificationUpdateRunnable = object : Runnable {
            override fun run() {
                if (!isServiceStopping && hasMusic()) {
                    updateNotification()
                    updateMediaSessionState()
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(notificationUpdateRunnable!!)
    }

    private fun stopNotificationUpdate() {
        notificationUpdateRunnable?.let {
            handler.removeCallbacks(it)
            notificationUpdateRunnable = null
        }
    }

    private fun restorePlaybackState() {
        val lastMusicPath = prefs.getString(KEY_LAST_MUSIC_PATH, "")
        val wasPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)

        if (lastMusicPath?.isNotEmpty() == true) {
            val file = File(lastMusicPath)
            if (file.exists()) {
                playMusicFile(lastMusicPath)

                if (!wasPlaying) {
                    handler.postDelayed({
                        pauseMusic()
                    }, 1000)
                }
            }
        }
    }

    fun loadMusicFilesFromFolder(folderPath: String) {
        musicList.clear()
        val folder = File(folderPath)
        val musicExtensions = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus")

        if (folder.exists() && folder.isDirectory) {
            folder.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (file.isFile) {
                    val extension = file.extension.lowercase()
                    if (musicExtensions.contains(extension)) {
                        val isFavorite = isFavorite(file.absolutePath)

                        val music = Music(
                            id = System.currentTimeMillis() + musicList.size,
                            title = file.nameWithoutExtension,
                            artist = "Artista Desconhecido",
                            album = folder.name,
                            duration = 0,
                            path = file.absolutePath,
                            isFavorite = isFavorite
                        )
                        musicList.add(music)
                    }
                }
            }
        }

        saveCurrentFolder(folderPath)
    }

    fun playMusicFile(filePath: String) {
        val file = File(filePath)
        val folderPath = file.parent ?: return

        loadMusicFilesFromFolder(folderPath)

        val index = musicList.indexOfFirst { it.path == filePath }
        if (index != -1) {
            currentMusicIndex = index
            playMusic(index)
        }
    }

    fun playMusic(index: Int) {
        if (index !in musicList.indices) return

        currentMusicIndex = index
        val music = musicList[index]

        isPrepared = false

        player?.release()

        val savedHaasDelay = getHaasDelay()
        val savedPitch = getPitch()
        val savedSpeed = getSpeed()
        val savedReverse = isReversed()

        Log.d("MusicService", "🎧 Preparando música com configurações salvas:")
        Log.d("MusicService", "   Haas: ${savedHaasDelay}ms, Pitch: $savedPitch, Speed: ${savedSpeed}x, Reverse: $savedReverse")

        player = StreamingAudioPlayer().apply {
            onPrepared = {
                handler.post {
                    isPrepared = true

                    // Configurar efeitos de áudio
                    setupAudioEffects()

                    // Aplicar configurações salvas
                    setHaasDelay(savedHaasDelay)
                    setPitch(savedPitch)
                    setSpeed(savedSpeed)
                    setReverse(savedReverse)

                    Thread {
                        val metadata = AlbumArtExtractor.getMetadata(music.path)
                        if (metadata != null) {
                            handler.post {
                                musicList[currentMusicIndex] = musicList[currentMusicIndex].copy(
                                    title = metadata.title ?: musicList[currentMusicIndex].title,
                                    artist = metadata.artist ?: "Artista Desconhecido",
                                    album = metadata.album ?: musicList[currentMusicIndex].album,
                                    duration = metadata.duration
                                )
                                updateNotification()
                                updateMediaSessionState()
                            }
                        }
                    }.start()

                    player?.play()
                    updateNotification()
                    updateMediaSessionState()
                    saveState()

                    Log.d("MusicService", "✅ Tocando: ${music.title}")
                }
            }

            onCompletion = {
                handler.post {
                    onTrackCompletion()
                }
            }

            onError = { error ->
                handler.post {
                    Log.e("MusicService", "❌ Erro: $error")
                }
            }
        }

        player?.prepare(music.path)
        updateNotification()
        updateMediaSessionState()

        Log.d("MusicService", "▶️ Carregando: ${music.title}")
    }

    fun playCurrentMusic() {
        if (musicList.isNotEmpty()) {
            playMusic(currentMusicIndex)
        }
    }

    fun pauseMusic() {
        player?.pause()
        updateNotification()
        updateMediaSessionState()
        saveState()
        Log.d("MusicService", "⏸️ Pausado")
    }

    fun resumeMusic() {
        if (isPrepared) {
            player?.play()
            updateNotification()
            updateMediaSessionState()
        } else if (musicList.isNotEmpty()) {
            playCurrentMusic()
        }
        saveState()
        Log.d("MusicService", "▶️ Retomado")
    }

    fun stopMusicCompletely() {
        try {
            Log.d("MusicService", "⏹️ Parando...")

            isServiceStopping = true
            saveState()

            player?.stop()
            player?.release()
            player = null

            isPrepared = false

            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.e("MusicService", "Erro ao parar foreground", e)
            }

            prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply()
            stopSelf()
            instance = null

            Log.d("MusicService", "✅ Parado")

        } catch (e: Exception) {
            Log.e("MusicService", "ERRO ao parar", e)
        }
    }

    fun playNext() {
        if (musicList.isNotEmpty()) {
            currentMusicIndex = if (isShuffling) {
                (0 until musicList.size).random()
            } else {
                (currentMusicIndex + 1) % musicList.size
            }
            playMusic(currentMusicIndex)
        }
    }

    fun playPrevious() {
        if (musicList.isNotEmpty()) {
            currentMusicIndex = if (currentMusicIndex - 1 < 0) {
                musicList.size - 1
            } else {
                currentMusicIndex - 1
            }
            playMusic(currentMusicIndex)
        }
    }

    fun seekTo(position: Int) {
    try {
        if (isPrepared && player != null) {
            val wasPlaying = isPlaying()
            player?.seekTo(position.toLong())
            if (wasPlaying) {
                handler.postDelayed({
                    player?.play()
                }, 150)
            }
            updateMediaSessionState()
            Log.d("MusicService", "⏩ Seek para: ${formatTime(position)}")
        }
    } catch (e: Exception) {
        Log.e("MusicService", "❌ Erro no seekTo", e)
    }
    }

    fun getCurrentPosition(): Int {
        return try {
            player?.getCurrentPositionMs() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun getDuration(): Int {
        return try {
            player?.getDurationMs() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun toggleShuffle(): Boolean {
        isShuffling = !isShuffling
        updateNotification()
        updateMediaSessionState()
        saveState()
        return isShuffling
    }

    fun toggleRepeat(): Int {
        repeatMode = (repeatMode + 1) % 3
        updateNotification()
        updateMediaSessionState()
        saveState()
        return repeatMode
    }

    fun isShuffling(): Boolean = isShuffling
    fun getRepeatMode(): Int = repeatMode

    private fun onTrackCompletion() {
        when (repeatMode) {
            REPEAT_ONE -> playMusic(currentMusicIndex)
            REPEAT_ALL -> playNext()
            else -> if (currentMusicIndex < musicList.size - 1) playNext() else {
                pauseMusic()
            }
        }
    }

    // Getters
    fun isPlaying(): Boolean = player?.isPlaying() ?: false
    fun getCurrentMusic(): Music? = if (currentMusicIndex in musicList.indices) musicList[currentMusicIndex] else null
    fun getMusicList(): List<Music> = musicList
    fun hasMusic(): Boolean = musicList.isNotEmpty()

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // Favoritos
    private fun getFavoritePaths(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, mutableSetOf()) ?: mutableSetOf()
    }

    private fun saveFavoritePaths(favorites: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
    }

    fun toggleFavorite(musicPath: String): Boolean {
        val favorites = getFavoritePaths().toMutableSet()
        val isNowFavorite = if (favorites.contains(musicPath)) {
            favorites.remove(musicPath)
            false
        } else {
            favorites.add(musicPath)
            true
        }
        saveFavoritePaths(favorites)

        val musicIndex = musicList.indexOfFirst { it.path == musicPath }
        if (musicIndex != -1) {
            musicList[musicIndex] = musicList[musicIndex].copy(isFavorite = isNowFavorite)
        }

        return isNowFavorite
    }

    fun isFavorite(musicPath: String): Boolean {
        return getFavoritePaths().contains(musicPath)
    }

    fun getFavorites(): List<Music> {
        val favoritePaths = getFavoritePaths()
        return musicList.filter { favoritePaths.contains(it.path) }
    }

    // Haas Effect
    fun getHaasDelay(): Int {
        return prefs.getInt(KEY_HAAS_DELAY, 0)
    }

    fun setHaasDelay(delayMs: Int) {
        player?.setHaasDelay(delayMs)
        prefs.edit().putInt(KEY_HAAS_DELAY, delayMs).apply()
        Log.d("MusicService", "🎧 Haas delay atualizado: ${delayMs}ms")
    }

    fun applyHaasToCurrentMusic() {
        val savedHaasDelay = getHaasDelay()
        player?.setHaasDelay(savedHaasDelay)
        Log.d("MusicService", "🎧 Haas verificado: ${savedHaasDelay}ms")
    }

    // Estado
    private val saveStateRunnable = object : Runnable {
        override fun run() {
            if (isPrepared && !isServiceStopping) {
                saveState()
            }
            handler.postDelayed(this, 5000)
        }
    }

    private fun saveState() {
        if (isServiceStopping) return

        val currentMusic = getCurrentMusic()
        prefs.edit().apply {
            putInt(KEY_CURRENT_INDEX, currentMusicIndex)
            putBoolean(KEY_SHUFFLE, isShuffling)
            putInt(KEY_REPEAT, repeatMode)
            putBoolean(KEY_IS_PLAYING, isPlaying())
            putString(KEY_LAST_MUSIC_PATH, currentMusic?.path ?: "")
            putBoolean(KEY_SERVICE_RUNNING, true)
        }.apply()
    }

    private fun saveCurrentFolder(folderPath: String) {
        prefs.edit().putString(KEY_CURRENT_PATH, folderPath).apply()
    }

    fun getCurrentFolder(): String {
        return prefs.getString(KEY_CURRENT_PATH, "") ?: ""
    }

    private fun restoreState() {
        currentMusicIndex = prefs.getInt(KEY_CURRENT_INDEX, 0)
        isShuffling = prefs.getBoolean(KEY_SHUFFLE, false)
        repeatMode = prefs.getInt(KEY_REPEAT, REPEAT_NONE)

        val savedFolder = getCurrentFolder()
        if (savedFolder.isNotEmpty() && File(savedFolder).exists()) {
            loadMusicFilesFromFolder(savedFolder)
        }

        handler.post(saveStateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            Log.d("MusicService", "🔴 Destruindo...")

            handler.removeCallbacks(saveStateRunnable)
            stopNotificationUpdate()

            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null

            audioManager?.abandonAudioFocus(audioFocusChangeListener)
            audioManager = null

            mediaButtonReceiver?.let { unregisterReceiver(it) }
            mediaButtonReceiver = null

            // Liberar efeitos de áudio
            equalizer?.release()
            equalizer = null

            bassBoost?.release()
            bassBoost = null

            if (!isServiceStopping) {
                saveState()
            }

            player?.release()
            player = null

            instance = null

            Log.d("MusicService", "✅ Service destruído")

        } catch (e: Exception) {
            Log.e("MusicService", "💥 ERRO onDestroy", e)
            instance = null
        }
    }
}
