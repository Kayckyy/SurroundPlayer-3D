package com.sonicsphere.audio.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder

class StreamingAudioPlayer {

    companion object {
        private const val TAG = "StreamingAudioPlayer"
        private const val TIMEOUT_US = 10000L
    }

    var onPrepared: (() -> Unit)? = null
    var onCompletion: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile private var isPlayingFlag = false
    private var isPreparedFlag = false
    @Volatile private var isStopped = false

    private var audioTrack: AudioTrack? = null
    private var haasProcessor: HaasProcessor? = null
    private var playbackThread: Thread? = null

    private var filePath: String? = null
    private var sampleRate = 44100
    private var channelCount = 2
    private var durationUs = 0L

    @Volatile private var seekRequestUs: Long = -1L
    @Volatile private var currentPositionUs = 0L

    private var haasDelayMs = 0
    private var pitchSemitones = 0
    private var speedFactor = 1.0f

    fun prepare(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                onError?.invoke("Arquivo não encontrado")
                return
            }

            release()

            this.filePath = filePath
            isStopped = false
            isPreparedFlag = false
            isPlayingFlag = false
            currentPositionUs = 0L
            seekRequestUs = -1L

            // Lê formato do arquivo
            val extractor = MediaExtractor()
            extractor.setDataSource(filePath)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                extractor.release()
                onError?.invoke("Nenhuma faixa de áudio encontrada")
                return
            }
            val format = extractor.getTrackFormat(trackIndex)
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            extractor.release()

            // Cria AudioTrack
            val channelMask = if (channelCount >= 2)
                AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, 4096) * 4

            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            // Inicializa HaasProcessor
            haasProcessor = HaasProcessor(sampleRate).apply {
                if (haasDelayMs > 0) {
                    setDelayMs(haasDelayMs)
                    setEnabled(true)
                }
            }

            isPreparedFlag = true
            onPrepared?.invoke()

            Log.d(TAG, "✅ Preparado: ${file.name} | ${sampleRate}Hz | ${channelCount}ch | ${durationUs/1000}ms")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao preparar", e)
            onError?.invoke("Erro ao preparar: ${e.message}")
        }
    }

    fun play() {
        if (!isPreparedFlag) return
        if (isPlayingFlag && playbackThread?.isAlive == true) return

        isPlayingFlag = true
        isStopped = false

        playbackThread = Thread {
            runPlaybackLoop()
        }.apply {
            name = "AudioPlaybackThread"
            isDaemon = true
            start()
        }

        Log.d(TAG, "▶️ Tocando")
    }

    private fun runPlaybackLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(filePath!!)

            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) { onError?.invoke("Faixa não encontrada"); return }

            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Seek inicial
            if (currentPositionUs > 0) {
                extractor.seekTo(currentPositionUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            audioTrack?.play()

            val info = MediaCodec.BufferInfo()
            var isEOS = false

            while (!isStopped) {

                // Handle seek
                val seekTarget = seekRequestUs
                if (seekTarget >= 0) {
                    seekRequestUs = -1L
                    extractor.seekTo(seekTarget, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    isEOS = false
                    currentPositionUs = seekTarget
                    audioTrack?.flush()
                    audioTrack?.play()
                }

                // Pausado
                if (!isPlayingFlag) {
                    Thread.sleep(30)
                    continue
                }

                // Input ao codec
                if (!isEOS) {
                    val inputIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inputIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inputIdx, 0, sampleSize, pts, 0)
                            currentPositionUs = pts
                            extractor.advance()
                        }
                    }
                }

                // Output do codec
                val outputIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIdx >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputIdx)

                    if (outputBuf != null && info.size > 0 && isPlayingFlag) {
                        outputBuf.position(info.offset)
                        outputBuf.limit(info.offset + info.size)

                        val shortBuf = outputBuf.slice()
                            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val samples = ShortArray(shortBuf.remaining())
                        shortBuf.get(samples)

                        // Pipeline de efeitos
                        haasProcessor?.process(samples)

                        audioTrack?.write(samples, 0, samples.size)
                    }

                    codec.releaseOutputBuffer(outputIdx, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isPlayingFlag = false
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onCompletion?.invoke()
                        }
                        break
                    }
                }
            }

        } catch (e: InterruptedException) {
            Log.d(TAG, "Thread interrompida")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro no playback", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onError?.invoke("Erro: ${e.message}")
            }
        } finally {
            try { codec?.stop(); codec?.release() } catch (e: Exception) { }
            try { extractor?.release() } catch (e: Exception) { }
        }
    }

    fun pause() {
        isPlayingFlag = false
        audioTrack?.pause()
        Log.d(TAG, "⏸️ Pausado")
    }

    fun stop() {
        isStopped = true
        isPlayingFlag = false
        try { audioTrack?.pause(); audioTrack?.flush() } catch (e: Exception) { }
        playbackThread?.interrupt()
        playbackThread = null
        Log.d(TAG, "⏹️ Parado")
    }

    fun release() {
        stop()
        try { audioTrack?.release() } catch (e: Exception) { }
        audioTrack = null
        haasProcessor = null
        isPreparedFlag = false
        filePath = null
        Log.d(TAG, "🗑️ Liberado")
    }

    fun seekTo(positionMs: Long) {
        val posUs = positionMs * 1000L
        seekRequestUs = posUs
        currentPositionUs = posUs

        // Se está pausado, inicia a thread para processar o seek e pausa logo após
        if (!isPlayingFlag && isPreparedFlag) {
            if (playbackThread?.isAlive != true) {
                isPlayingFlag = true
                isStopped = false
                playbackThread = Thread { runPlaybackLoop() }.apply {
                    name = "AudioPlaybackThread"
                    isDaemon = true
                    start()
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isPlayingFlag = false
                audioTrack?.pause()
            }, 300)
        }

        Log.d(TAG, "⏩ Seek: ${positionMs}ms")
    }

    fun isPlaying(): Boolean = isPlayingFlag

    fun getCurrentPositionMs(): Int = (currentPositionUs / 1000L).toInt()

    fun getDurationMs(): Int = (durationUs / 1000L).toInt()

    fun getAudioSessionId(): Int =
        try { audioTrack?.audioSessionId ?: 0 } catch (e: Exception) { 0 }

    // ========== EFEITOS ==========

    fun setHaasDelay(delayMs: Int) {
        haasDelayMs = delayMs.coerceIn(0, 50)
        haasProcessor?.apply {
            if (haasDelayMs == 0) {
                setEnabled(false)
            } else {
                setDelayMs(haasDelayMs)
                setEnabled(true)
            }
        }
        Log.d(TAG, "🎧 Haas: ${haasDelayMs}ms")
    }

    fun setPitch(semitones: Int) {
        pitchSemitones = semitones
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val factor = Math.pow(2.0, semitones / 12.0).toFloat()
                val params = audioTrack?.playbackParams?.also { it.pitch = factor }
                if (params != null) audioTrack?.playbackParams = params
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Pitch: ${e.message}")
        }
    }

    fun setSpeed(speed: Float) {
        speedFactor = speed.coerceIn(0.25f, 2.5f)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val params = audioTrack?.playbackParams?.also { it.speed = speedFactor }
                if (params != null) audioTrack?.playbackParams = params
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Speed: ${e.message}")
        }
    }

    fun setReverse(enabled: Boolean) {
        Log.w(TAG, "Reverse requer decodificação invertida — ainda não implementado")
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }
}
