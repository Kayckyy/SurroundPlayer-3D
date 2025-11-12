package com.sonicsphere.audio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.sonicsphere.audio.metadata.AlbumArtExtractor
import com.sonicsphere.audio.MainActivity
import com.sonicsphere.audio.metadata.Music
import com.sonicsphere.audio.R
import com.sonicsphere.audio.effects.HaasEffect
import java.io.File

class MusicService : Service() {

    private val binder = MusicBinder()
    private var notificationUpdateRunnable: Runnable? = null
    private var mediaPlayer: MediaPlayer? = null

    // EFEITOS DE ÁUDIO
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var audioDecoder: AudioDecoder? = null
    private var oboeEngine: OboeAudioEngine? = null

    private var currentMusicIndex = 0
    private var musicList: MutableList<Music> = mutableListOf()
    private var isPrepared = false
    private var isShuffling = false
    private var repeatMode = REPEAT_NONE
    private var isServiceStopping = false

    private val channelId = "music_player_channel"
    private val notificationId = 1

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_PLAY = "com.example.musicplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.musicplayer.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.musicplayer.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.musicplayer.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.example.musicplayer.ACTION_STOP"
        const val ACTION_TOGGLE_SHUFFLE = "com.example.musicplayer.ACTION_TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.example.musicplayer.ACTION_TOGGLE_REPEAT"

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
        private const val KEY_LAST_POSITION = "last_position"
        private const val KEY_LAST_MUSIC_PATH = "last_music_path"
        private const val KEY_FAVORITES = "favorite_musics"
        private const val KEY_SERVICE_RUNNING = "service_running"

        // CHAVES PARA EFEITOS DE ÁUDIO
        private const val KEY_EQUALIZER_ENABLED = "equalizer_enabled"
        private const val KEY_EQUALIZER_BANDS = "equalizer_bands_"
        private const val KEY_BASS_BOOST_ENABLED = "bass_boost_enabled"
        private const val KEY_BASS_BOOST_STRENGTH = "bass_boost_strength"
        private const val KEY_HAAS_MODE = "haas_mode"
        private const val KEY_HAAS_DELAY = "haas_delay_ms"
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

        mediaPlayer = MediaPlayer().apply {
            setOnCompletionListener {
                onTrackCompletion()
            }
            setOnErrorListener { _, what, extra ->
                false
            }
        }
        createNotificationChannel()

        if (shouldStartForeground()) {
            startForegroundService()
        }

        startNotificationUpdate()
        audioDecoder = AudioDecoder()
        oboeEngine = OboeAudioEngine()
        oboeEngine?.start()
        restoreState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY -> resumeMusic()
                ACTION_PAUSE -> pauseMusic()
                ACTION_NEXT -> playNext()
                ACTION_PREVIOUS -> playPrevious()
                ACTION_STOP -> {
                    stopMusicCompletely()
                    return START_NOT_STICKY
                }
                ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
                ACTION_TOGGLE_REPEAT -> toggleRepeat()
            }
        }

        if (intent?.action == null && shouldRestorePlayback()) {
            restorePlaybackState()
        }

        return START_NOT_STICKY
    }

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

        val previousIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_PREVIOUS
        }
        val previousPendingIntent = PendingIntent.getService(
            this, 1, previousIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = Intent(this, MusicService::class.java).apply {
            action = if (isPlaying()) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 2, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_NEXT
        }
        val nextPendingIntent = PendingIntent.getService(
            this, 3, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shuffleIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_TOGGLE_SHUFFLE
        }
        val shufflePendingIntent = PendingIntent.getService(
            this, 4, shuffleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val repeatIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_TOGGLE_REPEAT
        }
        val repeatPendingIntent = PendingIntent.getService(
            this, 5, repeatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 6, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
        val shuffleIcon = if (isShuffling) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle
        val repeatIcon = when (repeatMode) {
            REPEAT_ALL -> R.drawable.ic_repeat_all
            REPEAT_ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat
        }

        val contentText = if (isPlaying()) {
            "Tocando: ${music?.artist ?: "Artista desconhecido"}"
        } else {
            "Pausado"
        }

        // Calcular progresso da música
        val duration = getDuration()
        val position = getCurrentPosition()
        val progress = if (duration > 0) {
            ((position.toFloat() / duration.toFloat()) * 100).toInt()
        } else {
            0
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(music?.title ?: "Music Player")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.album_placeholder))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // ADICIONAR BARRA DE PROGRESSO
            .setProgress(100, progress, false)
            .addAction(R.drawable.ic_skip_previous, "Anterior", previousPendingIntent)
            .addAction(playPauseIcon, if (isPlaying()) "Pausar" else "Tocar", playPausePendingIntent)
            .addAction(R.drawable.ic_skip_next, "Próxima", nextPendingIntent)
            .addAction(shuffleIcon, "Embaralhar", shufflePendingIntent)
            .addAction(repeatIcon, "Repetir", repeatPendingIntent)
            .addAction(R.drawable.ic_stop, "Parar", stopPendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationBuilder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        notificationBuilder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setShowCancelButton(true)
            .setCancelButtonIntent(stopPendingIntent))

        return notificationBuilder.build()
    }

    private fun startNotificationUpdate() {
        stopNotificationUpdate() // Para qualquer atualização anterior

        notificationUpdateRunnable = object : Runnable {
            override fun run() {
                if (!isServiceStopping && hasMusic()) {
                    updateNotification()
                    handler.postDelayed(this, 1000) // Atualizar a cada 1 segundo
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
        val lastPosition = prefs.getInt(KEY_LAST_POSITION, 0)
        val wasPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)

        if (lastMusicPath?.isNotEmpty() == true) {
            val file = File(lastMusicPath)
            if (file.exists()) {
                playMusicFile(lastMusicPath)

                handler.postDelayed({
                    if (lastPosition > 0 && lastPosition < (getDuration() - 5000)) {
                        seekTo(lastPosition)
                    }
                    if (wasPlaying) {
                        mediaPlayer?.start()
                    }
                }, 1000)
            }
        }
    }

    private fun restorePlaybackPosition() {
        val lastPosition = prefs.getInt(KEY_LAST_POSITION, 0)
        val lastMusicPath = prefs.getString(KEY_LAST_MUSIC_PATH, "")
        val currentMusicPath = getCurrentMusic()?.path

        if (lastMusicPath == currentMusicPath && lastPosition > 0 && lastPosition < (getDuration() - 5000)) {
            handler.postDelayed({
                mediaPlayer?.seekTo(lastPosition)
            }, 100)
        }

        mediaPlayer?.start()
        updateNotification()
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

        // Decodificar e carregar no Oboe
        Thread {
            val decoded = audioDecoder?.decodeAudio(music.path)
            if (decoded != null) {
                oboeEngine?.loadAudio(decoded.pcmData, decoded.sampleRate, decoded.channelCount)
                oboeEngine?.play()

                // Aplicar Haas
                val haasDelay = getHaasDelay()
                if (haasDelay > 0) {
                    oboeEngine?.setHaasDelay(haasDelay)
                    oboeEngine?.enableHaas(true)
                }
            }
        }.start()

        updateNotification()
        saveState()
    }

    fun playCurrentMusic() {
        if (musicList.isNotEmpty()) {
            playMusic(currentMusicIndex)
        }
    }

    fun pauseMusic() {
        oboeEngine?.pause()
        updateNotification()
        saveState()
    }

    fun resumeMusic() {
        if (isPrepared) {
            oboeEngine?.start()
            updateNotification()
        } else if (musicList.isNotEmpty()) {
            playCurrentMusic()
        }
        saveState()
    }

    fun stopMusicCompletely() {
        isServiceStopping = true
        saveState()

        mediaPlayer?.stop()
        mediaPlayer?.reset()
        isPrepared = false

        releaseAudioEffects()

        stopForeground(true)
        stopSelf()

        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply()
        instance = null
    }

    fun stopMusic() {
        stopMusicCompletely()
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
        if (isPrepared) {
            mediaPlayer?.seekTo(position)
            saveState()
        }
    }

    fun toggleShuffle(): Boolean {
        isShuffling = !isShuffling
        updateNotification()
        saveState()
        return isShuffling
    }

    fun toggleRepeat(): Int {
        repeatMode = (repeatMode + 1) % 3
        updateNotification()
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

    // ==================== FUNÇÕES DE EFEITOS DE ÁUDIO ====================

    private fun initializeAudioEffects() {
        try {
            releaseAudioEffects()

            val audioSessionId = mediaPlayer?.audioSessionId ?: return

            // Criar Equalizer
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = prefs.getBoolean(KEY_EQUALIZER_ENABLED, false)

                val numberOfBands = this.numberOfBands
                for (band in 0 until numberOfBands.toInt()) {
                    val savedLevel = prefs.getInt("${KEY_EQUALIZER_BANDS}$band", 0)
                    try {
                        setBandLevel(band.toShort(), savedLevel.toShort())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // Criar Bass Boost
            bassBoost = BassBoost(0, audioSessionId).apply {
                val savedEnabled = prefs.getBoolean(KEY_BASS_BOOST_ENABLED, false)
                val savedStrength = prefs.getInt(KEY_BASS_BOOST_STRENGTH, 0)
                val limitedStrength = savedStrength.coerceIn(0, 500)
                setStrength(limitedStrength.toShort())
                enabled = savedEnabled
            }


        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseAudioEffects() {
        try {
            equalizer?.release()
            equalizer = null

            bassBoost?.release()
            bassBoost = null


        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Funções públicas para controlar o Equalizer
    fun isEqualizerEnabled(): Boolean = equalizer?.enabled ?: false

    fun setEqualizerEnabled(enabled: Boolean) {
        try {
            equalizer?.enabled = enabled
            prefs.edit().putBoolean(KEY_EQUALIZER_ENABLED, enabled).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getEqualizerNumberOfBands(): Short? = equalizer?.numberOfBands

    fun getEqualizerBandLevelRange(): ShortArray? = equalizer?.bandLevelRange

    fun getEqualizerCenterFreq(band: Short): Int? = equalizer?.getCenterFreq(band)

    fun getEqualizerBandLevel(band: Short): Short? = equalizer?.getBandLevel(band)

    fun setEqualizerBandLevel(band: Short, level: Short) {
        try {
            equalizer?.setBandLevel(band, level)
            prefs.edit().putInt("${KEY_EQUALIZER_BANDS}${band.toInt()}", level.toInt()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Funções públicas para controlar o Bass Boost
    fun isBassBoostEnabled(): Boolean = bassBoost?.enabled ?: false

    fun setBassBoostEnabled(enabled: Boolean) {
        try {
            bassBoost?.enabled = enabled
            prefs.edit().putBoolean(KEY_BASS_BOOST_ENABLED, enabled).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getBassBoostStrength(): Short? = bassBoost?.roundedStrength

    fun setBassBoostStrength(strength: Short) {
        try {
            val limitedStrength = strength.toInt().coerceIn(0, 500).toShort()
            bassBoost?.setStrength(limitedStrength)
            prefs.edit().putInt(KEY_BASS_BOOST_STRENGTH, limitedStrength.toInt()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== FIM DAS FUNÇÕES DE EFEITOS ====================

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    fun getCurrentMusic(): Music? = if (currentMusicIndex in musicList.indices) musicList[currentMusicIndex] else null
    fun getMusicList(): List<Music> = musicList
    fun hasMusic(): Boolean = musicList.isNotEmpty()

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
            putInt(KEY_LAST_POSITION, getCurrentPosition())
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

    fun getHaasDelay(): Int {
        return prefs.getInt(KEY_HAAS_DELAY, 0)
    }

    fun setHaasDelay(delayMs: Int) {
        try {
            // TODO: Chamar OboeAudioEngine quando integrar
            oboeEngine?.setHaasDelay(delayMs)
            prefs.edit().putInt(KEY_HAAS_DELAY, delayMs).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        if (!isServiceStopping) {
            saveState()
        }
        handler.removeCallbacks(saveStateRunnable)
        stopNotificationUpdate() // ADICIONAR ESTA LINHA
        releaseAudioEffects()
        oboeEngine?.destroy()
        instance = null
        mediaPlayer?.release()
    }
}