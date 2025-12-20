package com.sonicsphere.audio.service

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File

class StreamingAudioPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var isPlayingFlag = false

    // Callbacks
    var onPrepared: (() -> Unit)? = null
    var onCompletion: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // Efeitos de áudio
    private var haasDelayMs = 0
    private var pitchSemitones = 0
    private var speedFactor = 1.0f
    private var isReversed = false

    fun prepare(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                onError?.invoke("Arquivo não encontrado")
                return
            }

            release()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )

                setDataSource(filePath)

                setOnPreparedListener {
                    Log.d("StreamingAudioPlayer", "✅ Preparado: ${file.name}")
                    onPrepared?.invoke()
                }

                setOnCompletionListener {
                    isPlayingFlag = false
                    onCompletion?.invoke()
                    Log.d("StreamingAudioPlayer", "✅ Completado")
                }

                setOnErrorListener { _, what, extra ->
                    Log.e("StreamingAudioPlayer", "❌ Erro: what=$what, extra=$extra")
                    onError?.invoke("Erro na reprodução: $what")
                    true
                }

                prepareAsync()
            }

        } catch (e: Exception) {
            Log.e("StreamingAudioPlayer", "❌ Erro ao preparar", e)
            onError?.invoke("Erro ao preparar: ${e.message}")
        }
    }

    fun play() {
        try {
            mediaPlayer?.let { mp ->
                if (!mp.isPlaying) {
                    mp.start()
                    isPlayingFlag = true
                    Log.d("StreamingAudioPlayer", "▶️ Tocando")
                }
            } ?: run {
                onError?.invoke("MediaPlayer não inicializado")
            }
        } catch (e: Exception) {
            Log.e("StreamingAudioPlayer", "❌ Erro ao tocar", e)
            onError?.invoke("Erro ao tocar: ${e.message}")
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                    isPlayingFlag = false
                    Log.d("StreamingAudioPlayer", "⏸️ Pausado")
                }
            }
        } catch (e: Exception) {
            Log.e("StreamingAudioPlayer", "❌ Erro ao pausar", e)
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                isPlayingFlag = false
                Log.d("StreamingAudioPlayer", "⏹️ Parado")
            }
        } catch (e: Exception) {
            Log.e("StreamingAudioPlayer", "❌ Erro ao parar", e)
        }
    }

    fun release() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.release()
            }
            mediaPlayer = null
            isPlayingFlag = false
            Log.d("StreamingAudioPlayer", "🗑️ Liberado")
        } catch (e: Exception) {
            Log.e("StreamingAudioPlayer", "❌ Erro ao liberar", e)
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.let { mp ->
                mp.seekTo(positionMs.toInt())
                Log.d("StreamingAudioPlayer", "⏩ Seek: ${positionMs}ms")
            }
        } catch (e: Exception) {
            Log.e("StreamingAudioPlayer", "❌ Erro ao fazer seek", e)
        }
    }

    // Getters
    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun getCurrentPositionMs(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun getDurationMs(): Int {
        return try {
            mediaPlayer?.duration ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun getAudioSessionId(): Int {
        return try {
            mediaPlayer?.audioSessionId ?: 0
        } catch (e: Exception) {
            0
        }
    }

    // Setters de efeitos
    fun setHaasDelay(delayMs: Int) {
        haasDelayMs = delayMs.coerceIn(0, 50)
        Log.d("StreamingAudioPlayer", "🎧 Haas: ${haasDelayMs}ms (requer processamento customizado)")
    }

    fun setPitch(semitones: Int) {
        pitchSemitones = semitones.coerceIn(-12, 12)

        // Android 23+ suporta ajuste de pitch via PlaybackParams
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                mediaPlayer?.let { mp ->
                    val pitchFactor = Math.pow(2.0, semitones / 12.0).toFloat()
                    val params = mp.playbackParams
                    params.pitch = pitchFactor
                    mp.playbackParams = params
                    Log.d("StreamingAudioPlayer", "🎵 Pitch: $semitones semitons (fator: $pitchFactor)")
                }
            } else {
                Log.w("StreamingAudioPlayer", "⚠️ Pitch requer Android 6.0+")
            }
        } catch (e: Exception) {
            Log.e("StreamingAudioPlayer", "❌ Erro ao aplicar pitch", e)
        }
    }

    fun setSpeed(speedFactor: Float) {
        this.speedFactor = speedFactor.coerceIn(0.25f, 2.5f)

        // Android 23+ suporta ajuste de velocidade via PlaybackParams
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                mediaPlayer?.let { mp ->
                    val params = mp.playbackParams
                    params.speed = this.speedFactor
                    mp.playbackParams = params
                    Log.d("StreamingAudioPlayer", "⚡ Speed: ${this.speedFactor}x")
                }
            } else {
                Log.w("StreamingAudioPlayer", "⚠️ Speed requer Android 6.0+")
            }
        } catch (e: Exception) {
            Log.e("StreamingAudioPlayer", "❌ Erro ao aplicar speed", e)
        }
    }

    fun setReverse(enabled: Boolean) {
        isReversed = enabled
        Log.d("StreamingAudioPlayer", "🔄 Reverse: $enabled (não suportado no MediaPlayer)")
        if (enabled) {
            Log.w("StreamingAudioPlayer", "⚠️ Reverse requer processamento customizado")
        }
    }
}