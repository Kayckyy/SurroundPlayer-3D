package com.example.musicplayer

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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

class MusicService : Service() {

    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var currentMusicIndex = 0
    private var musicList: MutableList<Music> = mutableListOf()
    private var isPrepared = false
    private var isShuffling = false
    private var repeatMode = REPEAT_NONE

    private val channelId = "music_player_channel"
    private val notificationId = 1

    private lateinit var prefs: SharedPreferences

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

        // Chaves para SharedPreferences
        private const val PREFS_NAME = "music_prefs"
        private const val KEY_CURRENT_PATH = "current_path"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_SHUFFLE = "shuffle_mode"
        private const val KEY_REPEAT = "repeat_mode"
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        mediaPlayer = MediaPlayer().apply {
            setOnCompletionListener {
                onTrackCompletion()
            }
            setOnPreparedListener {
                isPrepared = true
                start()
                updateNotification()
            }
            setOnErrorListener { _, what, extra ->
                false
            }
        }
        createNotificationChannel()
        startForegroundService()

        // Restaurar estado salvo
        restoreState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resumeMusic()
            ACTION_PAUSE -> pauseMusic()
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_STOP -> stopMusic()
            ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
            ACTION_TOGGLE_REPEAT -> toggleRepeat()
        }
        return START_STICKY
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
        val currentMusic = getCurrentMusic()
        val notification = createNotification(currentMusic)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    private fun createNotification(music: Music?): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // CORRIGIDO: Usar ações corretas para shuffle e repeat
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

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(music?.title ?: "Music Player")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.album_placeholder))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_skip_previous, "Anterior", previousPendingIntent)
            .addAction(playPauseIcon, if (isPlaying()) "Pausar" else "Tocar", playPausePendingIntent)
            .addAction(R.drawable.ic_skip_next, "Próxima", nextPendingIntent)
            .addAction(shuffleIcon, "Embaralhar", shufflePendingIntent) // CORRIGIDO
            .addAction(repeatIcon, "Repetir", repeatPendingIntent) // CORRIGIDO
            .addAction(R.drawable.ic_stop, "Parar", stopPendingIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(stopPendingIntent))
            .build()
    }

    // NOVO: Carregar todas as músicas de uma pasta
    fun loadMusicFilesFromFolder(folderPath: String) {
        musicList.clear()
        val folder = File(folderPath)
        val musicExtensions = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac")

        if (folder.exists() && folder.isDirectory) {
            folder.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (file.isFile) {
                    val extension = file.extension.lowercase()
                    if (musicExtensions.contains(extension)) {
                        val music = Music(
                            id = System.currentTimeMillis() + musicList.size,
                            title = file.nameWithoutExtension,
                            artist = "Artista Desconhecido",
                            album = folder.name,
                            duration = 0,
                            path = file.absolutePath
                        )
                        musicList.add(music)
                    }
                }
            }
        }

        // Salvar a pasta atual
        saveCurrentFolder(folderPath)

        if (musicList.isNotEmpty()) {
            currentMusicIndex = 0
            playMusic(0)
        }
    }

    fun playMusicFile(filePath: String) {
        val file = File(filePath)
        val folderPath = file.parent ?: return

        // Carregar todas as músicas da mesma pasta
        loadMusicFilesFromFolder(folderPath)

        // Encontrar o índice da música específica
        val index = musicList.indexOfFirst { it.path == filePath }
        if (index != -1) {
            currentMusicIndex = index
            playMusic(index)
        }
    }

    fun playMusic(index: Int) {
        if (index in musicList.indices) {
            currentMusicIndex = index
            val music = musicList[index]

            mediaPlayer?.reset()
            try {
                mediaPlayer?.setDataSource(music.path)
                mediaPlayer?.prepareAsync()
                saveState() // Salvar estado quando nova música começa
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun playCurrentMusic() {
        if (musicList.isNotEmpty()) {
            playMusic(currentMusicIndex)
        }
    }

    fun pauseMusic() {
        mediaPlayer?.pause()
        updateNotification()
        saveState()
    }

    fun resumeMusic() {
        if (isPrepared) {
            mediaPlayer?.start()
            updateNotification()
        } else if (musicList.isNotEmpty()) {
            playCurrentMusic()
        }
        saveState()
    }

    fun stopMusic() {
        mediaPlayer?.stop()
        mediaPlayer?.reset()
        isPrepared = false
        stopForeground(true)
        stopSelf()
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
        }
    }

    // CONTROLES EXTRAS CORRIGIDOS
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
                // Se não há repeat e é a última música, para
                pauseMusic()
            }
        }
    }

    // GETTERS
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    fun getCurrentMusic(): Music? = if (currentMusicIndex in musicList.indices) musicList[currentMusicIndex] else null
    fun getMusicList(): List<Music> = musicList
    fun hasMusic(): Boolean = musicList.isNotEmpty()

    // SALVAR E RESTAURAR ESTADO
    private fun saveState() {
        prefs.edit().apply {
            putInt(KEY_CURRENT_INDEX, currentMusicIndex)
            putBoolean(KEY_SHUFFLE, isShuffling)
            putInt(KEY_REPEAT, repeatMode)
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

        // Se tinha uma pasta salva, carregar as músicas
        val savedFolder = getCurrentFolder()
        if (savedFolder.isNotEmpty() && File(savedFolder).exists()) {
            loadMusicFilesFromFolder(savedFolder)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        saveState()
        instance = null
        mediaPlayer?.release()
        stopForeground(true)
    }
}