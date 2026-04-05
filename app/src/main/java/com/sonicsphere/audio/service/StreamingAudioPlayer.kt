package com.sonicsphere.audio.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StreamingAudioPlayer {

    companion object {
        private const val TAG = "StreamingAudioPlayer"
        private const val TIMEOUT_US = 5000L
        private const val BUFFER_FACTOR = 2
    }

    var onPrepared: (() -> Unit)? = null
    var onCompletion: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val generation = AtomicInteger(0)

    @Volatile private var isPlayingFlag = false
    @Volatile private var isStopped = false
    private var isPreparedFlag = false

    private var audioTrack: AudioTrack? = null

    // Pipeline de efeitos — EQ e BassBoost são recriados por música
    var haasProcessor: HaasProcessor? = null
        private set
    var equalizerProcessor: EqualizerProcessor? = null
        private set
    var bassBoostProcessor: BassBoostProcessor? = null
        private set
    
    // ConvolutionEngine é INJETADO externamente — persiste entre músicas
    var convolutionEngine: ConvolutionEngine? = null

    @Volatile private var playbackThread: Thread? = null
    private val threadLock = Any()

    private var filePath: String? = null
    private var sampleRate = 44100
    private var channelCount = 2
    private var durationUs = 0L

    private val seekRequestUs = AtomicLong(-1L)
    @Volatile private var currentPositionUs = 0L

    private var haasDelayMs = 0
    private var pitchSemitones = 0
    private var speedFactor = 1.0f

    fun prepare(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            onError?.invoke("Arquivo não encontrado")
            return
        }

        generation.incrementAndGet()
        releaseInternal() // Não toca no convolutionEngine

        this.filePath = filePath
        isStopped = false
        isPreparedFlag = false
        isPlayingFlag = false
        currentPositionUs = 0L
        seekRequestUs.set(-1L)

        Thread {
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(filePath)
                val trackIndex = findAudioTrack(extractor)
                if (trackIndex < 0) {
                    extractor.release()
                    postError("Nenhuma faixa de áudio encontrada")
                    return@Thread
                }
                val format = extractor.getTrackFormat(trackIndex)
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION) else 0L
                extractor.release()

                val channelMask = if (channelCount >= 2)
                    AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                val bufSize = maxOf(minBuf, 2048) * BUFFER_FACTOR

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
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                haasProcessor = HaasProcessor(sampleRate).apply {
                    if (haasDelayMs > 0) { setDelayMs(haasDelayMs); setEnabled(true) }
                }
                equalizerProcessor = EqualizerProcessor(sampleRate)
                bassBoostProcessor = BassBoostProcessor(sampleRate)

                // Reseta apenas os buffers internos do convolution (não os IRs)
                convolutionEngine?.reset()

                isPreparedFlag = true
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onPrepared?.invoke()
                }

                Log.d(TAG, "✅ Preparado: ${file.name} | ${sampleRate}Hz | ${channelCount}ch | ${durationUs/1000}ms")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Erro ao preparar", e)
                postError("Erro ao preparar: ${e.message}")
            }
        }.apply { isDaemon = true; name = "PrepareThread"; start() }
    }

    fun play() {
        if (!isPreparedFlag) return

        isPlayingFlag = true
        isStopped = false

        synchronized(threadLock) {
            val existing = playbackThread
            if (existing != null && existing.isAlive) {
                audioTrack?.play()
                Log.d(TAG, "▶️ Retomado (thread existente gen=${generation.get()})")
                return
            }

            val myGeneration = generation.get()
            playbackThread = Thread {
                runPlaybackLoop(myGeneration)
            }.apply {
                name = "AudioPlayback-gen$myGeneration"
                isDaemon = true
                start()
            }
            Log.d(TAG, "▶️ Nova thread (gen=$myGeneration)")
        }
    }

    private fun runPlaybackLoop(myGeneration: Int) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(filePath!!)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) { postError("Faixa não encontrada"); return }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            if (currentPositionUs > 0) {
                extractor.seekTo(currentPositionUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            audioTrack?.play()

            val info = MediaCodec.BufferInfo()
            var isEOS = false

            while (!isStopped && generation.get() == myGeneration) {

                val seekTarget = seekRequestUs.getAndSet(-1L)
                if (seekTarget >= 0) {
                    extractor.seekTo(seekTarget, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    isEOS = false
                    currentPositionUs = seekTarget
                    audioTrack?.flush()
                    audioTrack?.play()
                    convolutionEngine?.reset()
                }

                if (!isPlayingFlag) {
                    Thread.sleep(20)
                    continue
                }

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

                val outputIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIdx >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputIdx)

                    if (outputBuf != null && info.size > 0 && isPlayingFlag
                        && generation.get() == myGeneration) {

                        outputBuf.position(info.offset)
                        outputBuf.limit(info.offset + info.size)

                        val shortBuf = outputBuf.slice()
                            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val samples = ShortArray(shortBuf.remaining())
                        shortBuf.get(samples)

                        // Pipeline — ordem importa
                        equalizerProcessor?.process(samples)   // 2. EQ
                        bassBoostProcessor?.process(samples)   // 1. Grave
                        if (convolutionEngine?.enabled == true) {
                            applyGain(samples, 1.7f)  // compensa a perda da convolução
                             convolutionEngine?.process(samples)
                        } else {
                            
                            applyGain(samples, 0.7f)  // reduz um pouco o direto pra igualar
                        }
                        haasProcessor?.process(samples)        // 4. Haas
                        
                        audioTrack?.write(samples, 0, samples.size)
                    }

                    codec.releaseOutputBuffer(outputIdx, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isPlayingFlag = false
                        if (generation.get() == myGeneration) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onCompletion?.invoke()
                            }
                        }
                        break
                    }
                }
            }

        } catch (e: InterruptedException) {
            Log.d(TAG, "Thread gen=$myGeneration interrompida")
        } catch (e: Exception) {
            if (generation.get() == myGeneration) {
                Log.e(TAG, "❌ Erro no playback gen=$myGeneration", e)
                postError("Erro: ${e.message}")
            }
        } finally {
            try { codec?.stop(); codec?.release() } catch (e: Exception) { }
            try { extractor?.release() } catch (e: Exception) { }
            synchronized(threadLock) {
                if (playbackThread == Thread.currentThread()) playbackThread = null
            }
            Log.d(TAG, "🔚 Loop gen=$myGeneration encerrado")
        }
    }

    private fun applyGain(samples: ShortArray, factor: Float) {
    for (i in samples.indices) {
        samples[i] = (samples[i] * factor).toInt().coerceIn(-32768, 32767).toShort()
    }
    }

    private fun softClip(x: Float): Float {
    val threshold = 24000f // ~73% de 32767
    val sign = if (x >= 0) 1f else -1f
    val abs = kotlin.math.abs(x)
    return if (abs <= threshold) x
    else sign * (threshold + (32767f - threshold) * kotlin.math.tanh((abs - threshold) / (32767f - threshold)))
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
        synchronized(threadLock) {
            playbackThread?.interrupt()
            playbackThread = null
        }
        Log.d(TAG, "⏹️ Parado")
    }

    // Libera tudo EXCETO o convolutionEngine (que é gerenciado pelo MusicService)
    private fun releaseInternal() {
        stop()
        try { audioTrack?.release() } catch (e: Exception) { }
        audioTrack = null
        haasProcessor = null
        equalizerProcessor = null
        bassBoostProcessor = null
        isPreparedFlag = false
        filePath = null
    }

    // Chamado apenas quando o MusicService é destruído
    fun release() {
        releaseInternal()
        convolutionEngine = null
        Log.d(TAG, "🗑️ Liberado (total)")
    }

    fun seekTo(positionMs: Long) {
        val posUs = positionMs * 1000L
        seekRequestUs.set(posUs)
        currentPositionUs = posUs

        if (!isPlayingFlag && isPreparedFlag) {
            synchronized(threadLock) {
                val existing = playbackThread
                if (existing == null || !existing.isAlive) {
                    isStopped = false
                    isPlayingFlag = true
                    val myGeneration = generation.get()
                    playbackThread = Thread { runPlaybackLoop(myGeneration) }.apply {
                        name = "AudioPlayback-seek-gen$myGeneration"
                        isDaemon = true
                        start()
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        isPlayingFlag = false
                        audioTrack?.pause()
                    }, 300)
                }
            }
        }

        Log.d(TAG, "⏩ Seek: ${positionMs}ms")
    }

    fun isPlaying(): Boolean = isPlayingFlag
    fun getCurrentPositionMs(): Int = (currentPositionUs / 1000L).toInt()
    fun getDurationMs(): Int = (durationUs / 1000L).toInt()
    fun getAudioSessionId(): Int =
        try { audioTrack?.audioSessionId ?: 0 } catch (e: Exception) { 0 }

    fun setHaasDelay(delayMs: Int) {
        haasDelayMs = delayMs.coerceIn(0, 50)
        haasProcessor?.apply {
            if (haasDelayMs == 0) setEnabled(false)
            else { setDelayMs(haasDelayMs); setEnabled(true) }
        }
    }

    fun setPitch(semitones: Int) {
        pitchSemitones = semitones
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val factor = Math.pow(2.0, semitones / 12.0).toFloat()
                val params = audioTrack?.playbackParams?.also { it.pitch = factor }
                if (params != null) audioTrack?.playbackParams = params
            }
        } catch (e: Exception) { Log.e(TAG, "❌ Pitch: ${e.message}") }
    }

    fun setSpeed(speed: Float) {
        speedFactor = speed.coerceIn(0.25f, 2.5f)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val params = audioTrack?.playbackParams?.also { it.speed = speedFactor }
                if (params != null) audioTrack?.playbackParams = params
            }
        } catch (e: Exception) { Log.e(TAG, "❌ Speed: ${e.message}") }
    }

    fun setReverse(enabled: Boolean) {
        Log.w(TAG, "Reverse requer decodificação invertida — não implementado")
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun postError(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onError?.invoke(msg)
        }
    }
}
