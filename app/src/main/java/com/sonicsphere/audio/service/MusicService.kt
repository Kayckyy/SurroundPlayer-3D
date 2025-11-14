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
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
        const val ACTION_PLAY = "com.sonicsphere.audio.ACTION_PLAY"
        const val ACTION_PAUSE = "com.sonicsphere.audio.ACTION_PAUSE"
        const val ACTION_NEXT = "com.sonicsphere.audio.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.sonicsphere.audio.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.sonicsphere.audio.ACTION_STOP"
        const val ACTION_TOGGLE_SHUFFLE = "com.sonicsphere.audio.ACTION_TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.sonicsphere.audio.ACTION_TOGGLE_REPEAT"

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

        if (shouldStartForeground()) {
            startForegroundService()
        }

        startNotificationUpdate()

        Log.d("MusicService", "✅ Service criado")

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

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(music?.title ?: "SonicSphere")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.album_placeholder))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_skip_previous, "Anterior", previousPendingIntent)
            .addAction(playPauseIcon, if (isPlaying()) "Pausar" else "Tocar", playPausePendingIntent)
            .addAction(R.drawable.ic_skip_next, "Próxima", nextPendingIntent)
            .addAction(shuffleIcon, "Embaralhar", shufflePendingIntent)
            .addAction(repeatIcon, "Repetir", repeatPendingIntent)
            .addAction(R.drawable.ic_stop, "Parar", stopPendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationBuilder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        notificationBuilder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(stopPendingIntent)
        )

        return notificationBuilder.build()
    }

    private fun startNotificationUpdate() {
        stopNotificationUpdate()

        notificationUpdateRunnable = object : Runnable {
            override fun run() {
                if (!isServiceStopping && hasMusic()) {
                    updateNotification()
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

        // Liberar player anterior
        player?.release()

        // Criar novo player
        player = StreamingAudioPlayer().apply {
            // APLICAR HAAS ANTES DE PREPARAR
            val savedHaasDelay = getHaasDelay()
            if (savedHaasDelay > 0) {
                setHaasDelay(savedHaasDelay)
                Log.d("MusicService", "🎧 Haas pré-configurado: ${savedHaasDelay}ms")
            }

            // Callbacks
            onPrepared = {
                handler.post {
                    isPrepared = true

                    // Carregar metadados em background
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
                            }
                        }
                    }.start()

                    // Tocar
                    player?.play()
                    updateNotification()
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

        // Preparar e carregar
        player?.prepare(music.path)
        updateNotification()

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
        saveState()
        Log.d("MusicService", "⏸️ Pausado")
    }

    fun resumeMusic() {
        if (isPrepared) {
            player?.play()
            updateNotification()
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
        // TODO: Implementar seek
        Log.w("MusicService", "Seek não implementado")
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

    // Getters
    fun isPlaying(): Boolean = player?.isPlaying() ?: false
    fun getCurrentPosition(): Int = 0 // TODO
    fun getDuration(): Int = player?.getDuration()?.toInt() ?: 0
    fun getCurrentMusic(): Music? = if (currentMusicIndex in musicList.indices) musicList[currentMusicIndex] else null
    fun getMusicList(): List<Music> = musicList
    fun hasMusic(): Boolean = musicList.isNotEmpty()

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
        // Aplicar no player atual
        player?.setHaasDelay(delayMs)

        // SALVAR PARA PERSISTIR
        prefs.edit().putInt(KEY_HAAS_DELAY, delayMs).apply()

        Log.d("MusicService", "🎧 Haas configurado e salvo: ${delayMs}ms")
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

    // Equalizer/BassBoost - DESABILITADOS (implementar em C++ depois se quiser)
    fun isEqualizerEnabled(): Boolean = false
    fun setEqualizerEnabled(enabled: Boolean) {}
    fun getEqualizerNumberOfBands(): Short? = null
    fun getEqualizerBandLevelRange(): ShortArray? = null
    fun getEqualizerCenterFreq(band: Short): Int? = null
    fun getEqualizerBandLevel(band: Short): Short? = null
    fun setEqualizerBandLevel(band: Short, level: Short) {}
    fun isBassBoostEnabled(): Boolean = false
    fun setBassBoostEnabled(enabled: Boolean) {}
    fun getBassBoostStrength(): Short? = null
    fun setBassBoostStrength(strength: Short) {}
}