package com.sonicsphere.audio.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Carrega arquivos WAV estéreo 44.1kHz da pasta IR e alimenta o ConvolutionEngine.
 * Pasta: Android/data/com.sonicsphere.audio/files/ir/
 */
object IrLoader {

    private const val TAG = "IrLoader"
    private const val TARGET_SAMPLE_RATE = 44100
    private const val MAX_SAMPLES = 44100 // 1 segundo

    data class WavData(
        val left: FloatArray,
        val right: FloatArray,
        val sampleRate: Int,
        val numSamples: Int
    )

    fun getIrDirectory(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "ir")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listAvailableIrs(context: Context): List<File> {
        return getIrDirectory(context)
            .listFiles { f -> f.extension.lowercase() == "wav" }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Carrega um WAV e injeta no ConvolutionEngine no slot especificado.
     * Executar em background thread.
     */
    fun loadIntoSlot(
        file: File,
        slot: ConvolutionEngine.IrSlot,
        engine: ConvolutionEngine,
        onComplete: ((success: Boolean, error: String?) -> Unit)? = null
    ) {
        try {
            val wav = readWav(file)
            if (wav == null) {
                onComplete?.invoke(false, "Falha ao ler WAV: ${file.name}")
                return
            }

            if (wav.sampleRate != TARGET_SAMPLE_RATE) {
                onComplete?.invoke(false,
                    "Sample rate inválido: ${wav.sampleRate}Hz (esperado ${TARGET_SAMPLE_RATE}Hz)")
                return
            }

            engine.loadIr(slot, wav.left, wav.right)
            Log.d(TAG, "✅ IR carregado: ${file.name} → $slot")
            onComplete?.invoke(true, null)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao carregar IR: ${file.name}", e)
            onComplete?.invoke(false, e.message)
        }
    }

    /**
     * Lê um arquivo WAV estéreo PCM 16-bit ou 24-bit.
     * Retorna null se o formato não for suportado.
     */
    fun readWav(file: File): WavData? {
        return try {
            val raf = RandomAccessFile(file, "r")
            raf.use {
                // RIFF header
                val riff = ByteArray(4).also { raf.read(it) }
                if (String(riff) != "RIFF") {
                    Log.e(TAG, "Não é um arquivo RIFF: ${file.name}")
                    return null
                }

                raf.skipBytes(4) // chunk size

                val wave = ByteArray(4).also { raf.read(it) }
                if (String(wave) != "WAVE") {
                    Log.e(TAG, "Não é um arquivo WAVE: ${file.name}")
                    return null
                }

                // Procura chunk fmt
                var audioFormat = 0
                var numChannels = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var dataSize = 0L

                while (raf.filePointer < raf.length() - 8) {
                    val chunkId = ByteArray(4).also { raf.read(it) }
                    val chunkSizeBuf = ByteArray(4).also { raf.read(it) }
                    val chunkSize = ByteBuffer.wrap(chunkSizeBuf)
                        .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

                    when (String(chunkId)) {
                        "fmt " -> {
                            val fmt = ByteArray(chunkSize.toInt()).also { raf.read(it) }
                            val buf = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                            audioFormat   = buf.short.toInt() and 0xFFFF
                            numChannels   = buf.short.toInt() and 0xFFFF
                            sampleRate    = buf.int
                            buf.int // byteRate
                            buf.short // blockAlign
                            bitsPerSample = buf.short.toInt() and 0xFFFF
                        }
                        "data" -> {
                            dataSize = chunkSize
                            break
                        }
                        else -> raf.skipBytes(chunkSize.toInt())
                    }
                }

                if (audioFormat != 1 && audioFormat != 3) {
                    Log.e(TAG, "Formato não suportado (somente PCM): audioFormat=$audioFormat")
                    return null
                }

                if (numChannels < 1) {
                    Log.e(TAG, "Número de canais inválido: $numChannels")
                    return null
                }

                val bytesPerSample = bitsPerSample / 8
                val totalSamples = (dataSize / (bytesPerSample * numChannels)).toInt()
                    .coerceAtMost(MAX_SAMPLES)

                val leftChannel  = FloatArray(totalSamples)
                val rightChannel = FloatArray(totalSamples)

                val frameBytes = bytesPerSample * numChannels
                val frameBuffer = ByteArray(frameBytes)

                for (i in 0 until totalSamples) {
                    raf.read(frameBuffer)
                    val buf = ByteBuffer.wrap(frameBuffer).order(ByteOrder.LITTLE_ENDIAN)

                    leftChannel[i] = when (bitsPerSample) {
                        16 -> buf.short.toFloat() / 32768f
                        24 -> read24bit(buf) / 8388608f
                        32 -> if (audioFormat == 3) buf.float else buf.int.toFloat() / 2147483648f
                        else -> 0f
                    }

                    rightChannel[i] = if (numChannels >= 2) {
                        when (bitsPerSample) {
                            16 -> buf.short.toFloat() / 32768f
                            24 -> read24bit(buf) / 8388608f
                            32 -> if (audioFormat == 3) buf.float else buf.int.toFloat() / 2147483648f
                            else -> 0f
                        }
                    } else {
                        leftChannel[i] // Mono — duplica para R
                    }
                }

                WavData(leftChannel, rightChannel, sampleRate, totalSamples)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao ler WAV: ${file.name}", e)
            null
        }
    }

    private fun read24bit(buf: ByteBuffer): Float {
        val b0 = buf.get().toInt() and 0xFF
        val b1 = buf.get().toInt() and 0xFF
        val b2 = buf.get().toInt()
        val value = (b2 shl 16) or (b1 shl 8) or b0
        return value.toFloat()
    }
}
