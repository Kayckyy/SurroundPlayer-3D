package com.sonicsphere.audio.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Player de áudio com decodificação em streaming
 * Processa buffers pequenos para não travar
 */
class StreamingAudioPlayer {

    companion object {
        private const val TAG = "StreamingPlayer"
        private const val BUFFER_SIZE = 8192
    }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var haasProcessor: HaasProcessor? = null

    private var decoderThread: Thread? = null
    private var isPlaying = false
    private var isPaused = false
    private var isReleased = false

    private var sampleRate = 44100
    private var channelCount = 2
    private var durationMs = 0L

    // GUARDAR CONFIGURAÇÃO DE HAAS
    private var pendingHaasDelay = 0

    // Callbacks
    var onPrepared: (() -> Unit)? = null
    var onCompletion: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Carrega e prepara o arquivo de áudio
     */
    fun prepare(filePath: String) {
        Thread {
            try {
                Log.d(TAG, "📂 Preparando: $filePath")

                extractor = MediaExtractor().apply {
                    setDataSource(filePath)
                }

                var trackIndex = -1
                var format: MediaFormat? = null

                for (i in 0 until extractor!!.trackCount) {
                    val trackFormat = extractor!!.getTrackFormat(i)
                    val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue

                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        format = trackFormat
                        break
                    }
                }

                if (trackIndex < 0 || format == null) {
                    onError?.invoke("Nenhum track de áudio encontrado")
                    return@Thread
                }

                extractor!!.selectTrack(trackIndex)

                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val mime = format.getString(MediaFormat.KEY_MIME)!!

                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
                }

                Log.d(TAG, "🎵 $mime, SR=$sampleRate, CH=$channelCount, Duração=${durationMs}ms")

                codec = MediaCodec.createDecoderByType(mime)
                codec!!.configure(format, null, null, 0)
                codec!!.start()

                val channelConfig = if (channelCount == 2) {
                    AudioFormat.CHANNEL_OUT_STEREO
                } else {
                    AudioFormat.CHANNEL_OUT_MONO
                }

                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                // Criar Haas Processor
                haasProcessor = HaasProcessor(sampleRate)

                // APLICAR HAAS PENDENTE (se houver)
                if (pendingHaasDelay > 0) {
                    haasProcessor?.setDelayMs(pendingHaasDelay)
                    haasProcessor?.setEnabled(true)
                    Log.d(TAG, "🎧 Haas aplicado: ${pendingHaasDelay}ms")
                }

                Log.d(TAG, "✅ Preparado!")
                onPrepared?.invoke()

            } catch (e: Exception) {
                Log.e(TAG, "💥 Erro ao preparar", e)
                onError?.invoke("Erro: ${e.message}")
            }
        }.start()
    }

    fun play() {
        if (isPlaying && !isPaused) return

        if (isPaused) {
            isPaused = false
            audioTrack?.play()
            Log.d(TAG, "▶️ Retomado")
            return
        }

        isPlaying = true
        isPaused = false
        audioTrack?.play()

        decoderThread = Thread {
            decode()
        }.apply {
            name = "AudioDecoderThread"
            priority = Thread.MAX_PRIORITY
            start()
        }

        Log.d(TAG, "▶️ Tocando")
    }

    fun pause() {
        if (!isPlaying || isPaused) return

        isPaused = true
        audioTrack?.pause()
        Log.d(TAG, "⏸️ Pausado")
    }

    fun stop() {
        isPlaying = false
        isPaused = false

        audioTrack?.stop()
        decoderThread?.interrupt()
        decoderThread = null

        Log.d(TAG, "⏹️ Parado")
    }

    fun release() {
        if (isReleased) return

        stop()

        try {
            codec?.stop()
            codec?.release()
            codec = null

            audioTrack?.release()
            audioTrack = null

            extractor?.release()
            extractor = null

            haasProcessor = null

            isReleased = true

            Log.d(TAG, "🔴 Liberado")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao liberar", e)
        }
    }

    private fun decode() {
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10000L
        var isEOS = false

        try {
            while (isPlaying && !isEOS && !Thread.interrupted()) {
                while (isPaused && isPlaying) {
                    Thread.sleep(100)
                }

                if (!isPlaying) break

                val inputIndex = codec!!.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = codec!!.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor!!.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        codec!!.queueInputBuffer(
                            inputIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        isEOS = true
                    } else {
                        val presentationTimeUs = extractor!!.sampleTime
                        codec!!.queueInputBuffer(
                            inputIndex, 0, sampleSize,
                            presentationTimeUs, 0
                        )
                        extractor!!.advance()
                    }
                }

                val outputIndex = codec!!.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputIndex >= 0) {
                    val outputBuffer = codec!!.getOutputBuffer(outputIndex)!!

                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val pcmData = ShortArray(bufferInfo.size / 2)
                    outputBuffer.order(ByteOrder.nativeOrder())
                    outputBuffer.asShortBuffer().get(pcmData)

                    // Aplicar Haas Effect
                    haasProcessor?.process(pcmData)

                    audioTrack?.write(pcmData, 0, pcmData.size)

                    codec!!.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isEOS = true
                    }
                }
            }

            if (isEOS) {
                Log.d(TAG, "✅ Reprodução completa")
                onCompletion?.invoke()
            }

        } catch (e: InterruptedException) {
            Log.d(TAG, "Thread interrompida")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Erro na decodificação", e)
            onError?.invoke("Erro: ${e.message}")
        }
    }

    // Getters
    fun isPlaying(): Boolean = isPlaying && !isPaused
    fun getDuration(): Long = durationMs
    fun getSampleRate(): Int = sampleRate
    fun getChannelCount(): Int = channelCount

    // Haas Effect
    fun setHaasDelay(delayMs: Int) {
        pendingHaasDelay = delayMs // GUARDAR PARA PRÓXIMA MÚSICA
        haasProcessor?.setDelayMs(delayMs)
        haasProcessor?.setEnabled(delayMs > 0)
    }

    fun getHaasDelay(): Int = pendingHaasDelay
}