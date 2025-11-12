package com.sonicsphere.audio.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioDecoder {

    companion object {
        private const val TAG = "AudioDecoder"
    }

    fun decodeAudio(filePath: String): DecodedAudio? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(filePath)

            // Encontrar track de áudio
            var trackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                Log.e(TAG, "Nenhum track de áudio encontrado")
                return null
            }

            extractor.selectTrack(trackIndex)

            // Extrair informações
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            Log.d(TAG, "Decodificando: $mime, SR=$sampleRate, CH=$channelCount")

            // Criar codec
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmData = mutableListOf<Float>()
            var isEOS = false

            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10000L

            // Loop de decodificação
            while (!isEOS) {
                // Input
                val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }

                // Output
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!

                    // Converter para Float
                    val samples = convertToFloat(outputBuffer, bufferInfo, format)
                    pcmData.addAll(samples)

                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            Log.d(TAG, "Decodificação concluída: ${pcmData.size} samples")

            return DecodedAudio(
                pcmData = pcmData.toFloatArray(),
                sampleRate = sampleRate,
                channelCount = channelCount
            )

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao decodificar", e)
            return null
        } finally {
            codec?.stop()
            codec?.release()
            extractor.release()
        }
    }

    private fun convertToFloat(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        format: MediaFormat
    ): List<Float> {
        val encoding = try {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } catch (e: Exception) {
            MediaFormat.ENCODING_PCM_16BIT
        }

        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)

        val samples = mutableListOf<Float>()

        when (encoding) {
            MediaFormat.ENCODING_PCM_16BIT -> {
                buffer.order(ByteOrder.nativeOrder())
                while (buffer.hasRemaining()) {
                    val sample = buffer.short.toFloat() / Short.MAX_VALUE
                    samples.add(sample)
                }
            }
            MediaFormat.ENCODING_PCM_FLOAT -> {
                buffer.order(ByteOrder.nativeOrder())
                while (buffer.hasRemaining()) {
                    samples.add(buffer.float)
                }
            }
            else -> {
                Log.w(TAG, "Encoding desconhecido: $encoding, usando 16bit")
                buffer.order(ByteOrder.nativeOrder())
                while (buffer.hasRemaining()) {
                    val sample = buffer.short.toFloat() / Short.MAX_VALUE
                    samples.add(sample)
                }
            }
        }

        return samples
    }

    data class DecodedAudio(
        val pcmData: FloatArray,
        val sampleRate: Int,
        val channelCount: Int
    )
}