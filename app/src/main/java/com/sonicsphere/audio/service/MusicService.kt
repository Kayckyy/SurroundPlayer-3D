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
import androidx.core.app.NotificationCompat
import com.sonicsphere.audio.metadata.AlbumArtExtractor
import com.sonicsphere.audio.MainActivity
import com.sonicsphere.audio.metadata.Music
import com.sonicsphere.audio.R
import java.io.File
import kotlin.math.pow

class MusicService : Service() {

    private val binder = MusicBinder()
    private var notificationUpdateRunnable: Runnable? = null

    // STREAMING PLAYER
    private var player: StreamingAudioPlayer? = null

    // CONVOLUTION ENGINE — persistente entre músicas, não recriado com o player
    private var convolutionEngine: ConvolutionEngine? = null

    // AUDIO EFFECTS (Android API — mantidos para compatibilidade de sessão)
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

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var mediaButtonReceiver: BroadcastReceiver? = null

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var wasPlayingBeforeFocusLoss = false

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

        private const val SEEK_THRESHOLD_MS = 5000
        private const val SEEK_JUMP_MS = 10000
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

        // Cria o ConvolutionEngine UMA VEZ — persiste por toda a vida do service
        convolutionEngine = ConvolutionEngine(44100)

        createNotificationChannel()
        setupMediaSession()
        registerAudioFocus()
        registerMediaButtonReceiver()

        if (shouldStartForeground()) startForegroundService()

        startNotificationUpdate()
        Log.d("MusicService", "✅ Service criado")

        restoreState()

        // Restaura IRs salvos em background
        //restoreConvolutionIrs()
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

        if (intent?.action == null && shouldRestorePlayback()) restorePlaybackState()

        return START_NOT_STICKY
    }

    // ========== SEEK ==========

    fun seekForward() {
        try {
            val newPos = (getCurrentPosition() + SEEK_JUMP_MS).coerceAtMost(getDuration())
            seekTo(newPos)
        } catch (e: Exception) { Log.e("MusicService", "❌ Erro ao avançar", e) }
    }

    fun seekBackward() {
        try {
            val newPos = (getCurrentPosition() - SEEK_JUMP_MS).coerceAtLeast(0)
            seekTo(newPos)
        } catch (e: Exception) { Log.e("MusicService", "❌ Erro ao retroceder", e) }
    }

    // ========== PITCH / SPEED ==========

    fun setPitch(semitones: Int) {
        player?.setPitch(semitones)
        prefs.edit().putInt(KEY_PITCH, semitones).apply()
    }

    fun getPitch(): Int = prefs.getInt(KEY_PITCH, 0)

    fun setSpeed(speedFactor: Float) {
        player?.setSpeed(speedFactor)
        prefs.edit().putFloat(KEY_SPEED, speedFactor).apply()
    }

    fun getSpeed(): Float = prefs.getFloat(KEY_SPEED, 1.0f)

    // ========== CONVOLUTION ENGINE ==========

    fun isBinauralEnabled(): Boolean = convolutionEngine?.enabled ?: false

    fun setBinauralEnabled(enabled: Boolean) {
    convolutionEngine?.enabled = enabled 
    prefs.edit().apply {
        putBoolean("binaural_enabled", enabled)
    }.apply()
    
    Log.d("MusicService", "🎧 Áudio 3D ${if (enabled) "ON" else "OFF"}")
    }

    fun getConvolutionEngine(): ConvolutionEngine? = convolutionEngine

    fun loadConvolutionIr(
        slot: ConvolutionEngine.IrSlot,
        file: File,
        onComplete: ((success: Boolean, error: String?) -> Unit)? = null
    ) {
        val engine = convolutionEngine ?: run {
            onComplete?.invoke(false, "Engine não inicializado"); return
        }
        Thread {
            IrLoader.loadIntoSlot(file, slot, engine) { success, error ->
                if (success) prefs.edit().putString("ir_slot_${slot.name}", file.absolutePath).apply()
                handler.post { onComplete?.invoke(success, error) }
            }
        }.apply { isDaemon = true; start() }
    }

    fun unloadConvolutionIr(slot: ConvolutionEngine.IrSlot) {
        convolutionEngine?.unloadIr(slot)
        prefs.edit().remove("ir_slot_${slot.name}").apply()
    }

    /**
     * Carrega os IRs padrão dos assets + restaura eventuais IRs externos salvos.
     * Deve ser chamado em background thread.
     */
    private fun loadAndRestoreIrs(engine: ConvolutionEngine) {
        // 1. Carrega assets padrão (LEFT + RIGHT)
        val defaultsOk = IrLoader.loadDefaults(applicationContext, engine)
        if (!defaultsOk) Log.w("MusicService", "⚠️ Falha ao carregar IRs padrão dos assets")

        // 2. Sobrescreve com arquivos externos salvos (se existirem)
        ConvolutionEngine.IrSlot.values().forEach { slot ->
            val path = prefs.getString("ir_slot_${slot.name}", null) ?: return@forEach
            val file = File(path)
            if (file.exists()) {
                IrLoader.loadIntoSlot(file, slot, engine) { success, _ ->
                    Log.d("MusicService", "IR externo restaurado $slot: $success")
                }
            } else {
                prefs.edit().remove("ir_slot_${slot.name}").apply()
            }
        }

        // 3. Ativa binaural se estava ligado
        if (prefs.getBoolean("binaural_enabled", false)) engine.enabled = true
    }

    // ========== REVERB ==========

    fun setBinauralEnabled(enabled: Boolean) {
    convolutionEngine?.enabled = enabled
    prefs.edit().apply {
        putBoolean("binaural_enabled", enabled)
    }.apply()
    }

fun isReverbEnabled(): Boolean = prefs.getBoolean("reverb_enabled", false)

fun getReverbRoomSize(): Float = prefs.getFloat("reverb_room_size", 0.25f)
fun getReverbWet(): Float = prefs.getFloat("reverb_wet", 0.18f)
fun getReverbDamping(): Float = prefs.getFloat("reverb_damping", 0.7f)

fun setReverbRoomSize(v: Float) {
    player?.reverbProcessor?.setRoomSize(v)
    prefs.edit().putFloat("reverb_room_size", v).apply()
}

fun setReverbWet(v: Float) {
    player?.reverbProcessor?.setWetLevel(v)
    prefs.edit().putFloat("reverb_wet", v).apply()
}

fun setReverbDamping(v: Float) {
    player?.reverbProcessor?.setDamping(v)
    prefs.edit().putFloat("reverb_damping", v).apply()
}
    // ========== AUDIO EFFECTS (pipeline próprio) ==========

    private fun setupAudioEffects() {
        // Restaura estado salvo nos processadores do pipeline
        val eqEnabled = prefs.getBoolean(KEY_EQUALIZER_ENABLED, false)
        player?.equalizerProcessor?.setEnabled(eqEnabled)

        val bbEnabled = prefs.getBoolean(KEY_BASS_BOOST_ENABLED, false)
        player?.bassBoostProcessor?.setEnabled(bbEnabled)

        val bbStrength = prefs.getInt(KEY_BASS_BOOST_STRENGTH, 0)
        if (bbStrength > 0) {
            player?.bassBoostProcessor?.setGainDb(bbStrength.toFloat() / 100f)
        }

        for (band in 0 until EqualizerProcessor.BAND_COUNT) {
            val level = prefs.getInt("eq_band_$band", 0)
            if (level != 0) {
                player?.equalizerProcessor?.setBandGainDb(band, level.toFloat() / 100f)
            }
        }
    }

    fun isEqualizerEnabled(): Boolean = player?.equalizerProcessor?.isEnabled() ?: false

    fun setEqualizerEnabled(enabled: Boolean) {
        player?.equalizerProcessor?.setEnabled(enabled)
        prefs.edit().putBoolean(KEY_EQUALIZER_ENABLED, enabled).apply()
    }

    fun getEqualizerNumberOfBands(): Short = EqualizerProcessor.BAND_COUNT.toShort()

    fun getEqualizerBandLevelRange(): ShortArray = shortArrayOf(-1500, 1500)

    fun getEqualizerCenterFreq(band: Short): Int {
        val idx = band.toInt()
        if (idx !in 0 until EqualizerProcessor.BAND_COUNT) return 0
        return EqualizerProcessor.BAND_FREQUENCIES[idx] * 1000
    }

    fun getEqualizerBandLevel(band: Short): Short {
        val db = player?.equalizerProcessor?.getBandGainDb(band.toInt()) ?: 0f
        return (db * 100).toInt().toShort()
    }

    fun setEqualizerBandLevel(band: Short, level: Short) {
        val db = level.toFloat() / 100f
        player?.equalizerProcessor?.setBandGainDb(band.toInt(), db)
        prefs.edit().putInt("eq_band_$band", level.toInt()).apply()
    }

    fun getEqualizerPresetNames(): Array<String>? = null
    fun setEqualizerPreset(preset: Short) {}

    fun isBassBoostEnabled(): Boolean = player?.bassBoostProcessor?.isEnabled() ?: false

    fun setBassBoostEnabled(enabled: Boolean) {
        player?.bassBoostProcessor?.setEnabled(enabled)
        prefs.edit().putBoolean(KEY_BASS_BOOST_ENABLED, enabled).apply()
    }

    fun getBassBoostStrength(): Short {
        val db = player?.bassBoostProcessor?.gainDb ?: 0f
        return (db * 100).toInt().toShort()
    }

    fun setBassBoostStrength(strength: Short) {
        val db = strength.toFloat() / 100f
        player?.bassBoostProcessor?.setGainDb(db)
        prefs.edit().putInt(KEY_BASS_BOOST_STRENGTH, strength.toInt()).apply()
    }

    fun getBassBoostCutoffHz(): Float =
        player?.bassBoostProcessor?.cutoffHz ?: BassBoostProcessor.DEFAULT_CUTOFF_HZ

    fun setBassBoostCutoffHz(hz: Float) {
        player?.bassBoostProcessor?.setCutoffHz(hz)
        prefs.edit().putFloat("bass_boost_cutoff_hz", hz).apply()
    }

    // ========== MEDIA SESSION ==========

    private fun setupMediaSession() {
        try {
            mediaSession = MediaSession(this, "MusicService")
            mediaSession?.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            mediaSession?.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resumeMusic() }
                override fun onPause() { pauseMusic() }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { handlePreviousWithThreshold() }
                override fun onStop() { stopMusicCompletely() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                override fun onFastForward() { seekForward() }
                override fun onRewind() { seekBackward() }
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
            Log.d("MusicService", if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                "✅ Foco de áudio concedido" else "⚠️ Foco de áudio não concedido")
        } catch (e: Exception) {
            Log.e("MusicService", "❌ Erro ao registrar foco de áudio", e)
        }
    }

    private fun registerMediaButtonReceiver() {
        mediaButtonReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (Intent.ACTION_MEDIA_BUTTON != intent?.action) return
                val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
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
        }
        val filter = IntentFilter(Intent.ACTION_MEDIA_BUTTON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mediaButtonReceiver?.let { registerReceiver(it, filter, Context.RECEIVER_EXPORTED) }
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            mediaButtonReceiver?.let { registerReceiver(it, filter) }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlayingBeforeFocusLoss = false
                if (isPlaying()) pauseMusic()
                Log.d("MusicService", "🔇 Foco perdido permanentemente")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                wasPlayingBeforeFocusLoss = isPlaying()
                if (wasPlayingBeforeFocusLoss) pauseMusic()
                Log.d("MusicService", "⏸️ Foco perdido temporariamente")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                Log.d("MusicService", "🔈 Ducking")
            AudioManager.AUDIOFOCUS_GAIN -> {
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
        if (currentPosition > SEEK_THRESHOLD_MS) {
            seekTo(0)
        } else {
            playPrevious()
        }
        updateMediaSessionState()
    }

    private fun updateMediaSessionState() {
        try {
            val playbackState = if (isPlaying()) PlaybackState.STATE_PLAYING
            else PlaybackState.STATE_PAUSED

            mediaSession?.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_REWIND
                    )
                    .setState(playbackState, getCurrentPosition().toLong(), 1.0f)
                    .build()
            )

            getCurrentMusic()?.let { music ->
                mediaSession?.setMetadata(
                    android.media.MediaMetadata.Builder()
                        .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, music.title)
                        .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, music.artist)
                        .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, music.album)
                        .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, getDuration().toLong())
                        .build()
                )
            }
        } catch (e: Exception) {
            Log.e("MusicService", "❌ Erro ao atualizar Media Session", e)
        }
    }

    private fun togglePlayPause() {
        if (isPlaying()) pauseMusic() else resumeMusic()
    }

    // ========== FOREGROUND / NOTIFICATION ==========

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
        startForeground(notificationId, createNotification(null))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Music Player",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Music playback controls"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        if (isServiceStopping) return
        val notification = createNotification(getCurrentMusic())
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (shouldStartForeground()) {
            startForeground(notificationId, notification)
        } else {
            notificationManager.notify(notificationId, notification)
        }
    }

    private fun createNotification(music: Music?): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            action = "OPEN_EXISTING"
            putExtra("FROM_NOTIFICATION", true)
            putExtra("RESTORE_PLAYBACK", true)
        }
        val openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val seekBackwardIntent = createActionIntent(ACTION_SEEK_BACKWARD, 1)
        val previousIntent    = createActionIntent(ACTION_PREVIOUS, 2)
        val playPauseIntent   = createActionIntent(if (isPlaying()) ACTION_PAUSE else ACTION_PLAY, 3)
        val nextIntent        = createActionIntent(ACTION_NEXT, 4)
        val seekForwardIntent = createActionIntent(ACTION_SEEK_FORWARD, 5)
        val stopIntent        = createActionIntent(ACTION_STOP, 6)

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
            .addAction(if (isPlaying()) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying()) "Pausar" else "Tocar", playPauseIntent)
            .addAction(R.drawable.ic_skip_next, "Próxima", nextIntent)
            .addAction(R.drawable.ic_forward_10, "+10s", seekForwardIntent)
            .addAction(R.drawable.ic_stop1, "Parar", stopIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationBuilder.setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        notificationBuilder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(1, 2, 3)
                .setShowCancelButton(true)
                .setCancelButtonIntent(stopIntent)
        )

        return notificationBuilder.build()
    }

    private fun createActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
        notificationUpdateRunnable?.let { handler.removeCallbacks(it) }
        notificationUpdateRunnable = null
    }

    // ========== PLAYBACK ==========

    private fun restorePlaybackState() {
        val lastMusicPath = prefs.getString(KEY_LAST_MUSIC_PATH, "")
        val wasPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)
        if (lastMusicPath?.isNotEmpty() == true && File(lastMusicPath).exists()) {
            playMusicFile(lastMusicPath)
            if (!wasPlaying) handler.postDelayed({ pauseMusic() }, 1000)
        }
    }

    fun loadMusicFilesFromFolder(folderPath: String) {
        musicList.clear()
        val folder = File(folderPath)
        val musicExtensions = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus")
        if (folder.exists() && folder.isDirectory) {
            folder.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (file.isFile && musicExtensions.contains(file.extension.lowercase())) {
                    musicList.add(Music(
                        id = System.currentTimeMillis() + musicList.size,
                        title = file.nameWithoutExtension,
                        artist = "Artista Desconhecido",
                        album = folder.name,
                        duration = 0,
                        path = file.absolutePath,
                        isFavorite = isFavorite(file.absolutePath)
                    ))
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
        if (index != -1) { currentMusicIndex = index; playMusic(index) }
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

        player = StreamingAudioPlayer().apply {
            // Injeta o engine persistente — IRs são preservados entre músicas
            convolutionEngine = this@MusicService.convolutionEngine

            onPrepared = {
                handler.post {
                    isPrepared = true

                    // Restaura estado dos efeitos do pipeline
                    setupAudioEffects()
                    setHaasDelay(savedHaasDelay)
                    setPitch(savedPitch)
                    setSpeed(savedSpeed)
                    player?.reverbProcessor?.enabled = prefs.getBoolean("reverb_enabled", false)

                    // Restaura estado do reverb
                    player?.reverbProcessor?.apply {
                    setRoomSize(getReverbRoomSize())
                    setWetLevel(getReverbWet())
                    setDamping(getReverbDamping())
                    enabled = prefs.getBoolean("reverb_enabled", false)
                   }
                    
                    // Carrega IRs (assets padrão + externos salvos) se ainda não carregados
                    val engine = player?.convolutionEngine
                    if (engine != null && !engine.hasPrincipalIrs()) {
                        Thread {
                            loadAndRestoreIrs(engine)
                        }.apply { isDaemon = true; start() }
                    }

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

            onCompletion = { handler.post { onTrackCompletion() } }
            onError = { error -> handler.post { Log.e("MusicService", "❌ Erro: $error") } }
        }

        player?.prepare(music.path)
        updateNotification()
        updateMediaSessionState()
        Log.d("MusicService", "▶️ Carregando: ${music.title}")
    }

    fun playCurrentMusic() {
        if (musicList.isNotEmpty()) playMusic(currentMusicIndex)
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
            isServiceStopping = true
            saveState()
            player?.stop()
            player?.release()
            player = null
            isPrepared = false
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
            prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply()
            stopSelf()
            instance = null
        } catch (e: Exception) {
            Log.e("MusicService", "ERRO ao parar", e)
        }
    }

    fun playNext() {
        if (musicList.isNotEmpty()) {
            currentMusicIndex = if (isShuffling) (0 until musicList.size).random()
            else (currentMusicIndex + 1) % musicList.size
            playMusic(currentMusicIndex)
        }
    }

    fun playPrevious() {
        if (musicList.isNotEmpty()) {
            currentMusicIndex = if (currentMusicIndex - 1 < 0) musicList.size - 1
            else currentMusicIndex - 1
            playMusic(currentMusicIndex)
        }
    }

    fun seekTo(position: Int) {
        try {
            if (isPrepared && player != null) {
                player?.seekTo(position.toLong())
                updateMediaSessionState()
            }
        } catch (e: Exception) { Log.e("MusicService", "❌ Erro no seekTo", e) }
    }

    fun getCurrentPosition(): Int = try { player?.getCurrentPositionMs() ?: 0 } catch (e: Exception) { 0 }
    fun getDuration(): Int = try { player?.getDurationMs() ?: 0 } catch (e: Exception) { 0 }

    fun toggleShuffle(): Boolean {
        isShuffling = !isShuffling
        updateNotification(); updateMediaSessionState(); saveState()
        return isShuffling
    }

    fun toggleRepeat(): Int {
        repeatMode = (repeatMode + 1) % 3
        updateNotification(); updateMediaSessionState(); saveState()
        return repeatMode
    }

    fun isShuffling(): Boolean = isShuffling
    fun getRepeatMode(): Int = repeatMode

    private fun onTrackCompletion() {
        when (repeatMode) {
            REPEAT_ONE -> playMusic(currentMusicIndex)
            REPEAT_ALL -> playNext()
            else -> if (currentMusicIndex < musicList.size - 1) playNext() else pauseMusic()
        }
    }

    fun isPlaying(): Boolean = player?.isPlaying() ?: false
    fun getCurrentMusic(): Music? = if (currentMusicIndex in musicList.indices) musicList[currentMusicIndex] else null
    fun getMusicList(): List<Music> = musicList
    fun hasMusic(): Boolean = musicList.isNotEmpty()

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    // ========== FAVORITOS ==========

    private fun getFavoritePaths(): Set<String> =
        prefs.getStringSet(KEY_FAVORITES, mutableSetOf()) ?: mutableSetOf()

    private fun saveFavoritePaths(favorites: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
    }

    fun toggleFavorite(musicPath: String): Boolean {
        val favorites = getFavoritePaths().toMutableSet()
        val isNowFavorite = if (favorites.contains(musicPath)) {
            favorites.remove(musicPath); false
        } else {
            favorites.add(musicPath); true
        }
        saveFavoritePaths(favorites)
        val idx = musicList.indexOfFirst { it.path == musicPath }
        if (idx != -1) musicList[idx] = musicList[idx].copy(isFavorite = isNowFavorite)
        return isNowFavorite
    }

    fun isFavorite(musicPath: String): Boolean = getFavoritePaths().contains(musicPath)
    fun getFavorites(): List<Music> = musicList.filter { getFavoritePaths().contains(it.path) }

    // ========== HAAS ==========

    fun getHaasDelay(): Int = prefs.getInt(KEY_HAAS_DELAY, 0)

    fun setHaasDelay(delayMs: Int) {
        player?.setHaasDelay(delayMs)
        prefs.edit().putInt(KEY_HAAS_DELAY, delayMs).apply()
    }

    // ========== ESTADO ==========

    private val saveStateRunnable = object : Runnable {
        override fun run() {
            if (isPrepared && !isServiceStopping) saveState()
            handler.postDelayed(this, 5000)
        }
    }

    private fun saveState() {
        if (isServiceStopping) return
        prefs.edit().apply {
            putInt(KEY_CURRENT_INDEX, currentMusicIndex)
            putBoolean(KEY_SHUFFLE, isShuffling)
            putInt(KEY_REPEAT, repeatMode)
            putBoolean(KEY_IS_PLAYING, isPlaying())
            putString(KEY_LAST_MUSIC_PATH, getCurrentMusic()?.path ?: "")
            putBoolean(KEY_SERVICE_RUNNING, true)
        }.apply()
    }

    private fun saveCurrentFolder(folderPath: String) {
        prefs.edit().putString(KEY_CURRENT_PATH, folderPath).apply()
    }

    fun getCurrentFolder(): String = prefs.getString(KEY_CURRENT_PATH, "") ?: ""

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

    // ========== LIFECYCLE ==========

    override fun onDestroy() {
        super.onDestroy()
        try {
            handler.removeCallbacks(saveStateRunnable)
            stopNotificationUpdate()
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
            audioManager = null
            mediaButtonReceiver?.let { unregisterReceiver(it) }
            mediaButtonReceiver = null
            equalizer?.release(); equalizer = null
            bassBoost?.release(); bassBoost = null
            if (!isServiceStopping) saveState()
            player?.release()
            player = null
            convolutionEngine = null
            instance = null
            Log.d("MusicService", "✅ Service destruído")
        } catch (e: Exception) {
            Log.e("MusicService", "💥 ERRO onDestroy", e)
            instance = null
        }
    }

    // ========== ISREVERSED (compatibilidade) ==========
    fun isReversed(): Boolean = false
    fun setReverse(enabled: Boolean) {}
}
