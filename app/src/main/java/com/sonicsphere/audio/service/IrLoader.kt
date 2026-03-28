package com.sonicsphere.audio.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Carrega WAVs estéreo 44.1kHz no ConvolutionEngine.
 * Prioridade: arquivo externo (files/ir/) > asset embutido (assets/ir/).
 */
object IrLoader {

    private const val TAG = "IrLoader"
    private const val TARGET_SAMPLE_RATE = 44100
    private const val MAX_SAMPLES = 44100

    private val SLOT_ASSET = mapOf(
        ConvolutionEngine.IrSlot.LEFT  to "ir/left.wav",
        ConvolutionEngine.IrSlot.RIGHT to "ir/right.wav",
    )

    data class WavData(val left: FloatArray, val right: FloatArray, val sampleRate: Int)

    // ========== API PÚBLICA ==========

    fun getIrDirectory(context: Context): File =
        File(context.getExternalFilesDir(null), "ir").also { if (!it.exists()) it.mkdirs() }

    fun listAvailableIrs(context: Context): List<File> =
        getIrDirectory(context)
            .listFiles { f -> f.extension.lowercase() == "wav" }
            ?.sortedBy { it.name } ?: emptyList()

    /**
     * Carrega LEFT e RIGHT automaticamente.
     * Retorna true se ambos os slots principais foram carregados com sucesso.
     */
    fun loadDefaults(context: Context, engine: ConvolutionEngine): Boolean {
        val externalDir = getIrDirectory(context)
        var allOk = true

        for ((slot, assetPath) in SLOT_ASSET) {
            val externalFile = File(externalDir, "${slot.name.lowercase()}.wav")
            val wav: WavData? = when {
                externalFile.exists() -> {
                    Log.d(TAG, "IR externo: ${externalFile.name} → $slot")
                    readWav(externalFile.inputStream())
                }
                else -> {
                    Log.d(TAG, "IR asset: $assetPath → $slot")
                    readWavFromAsset(context, assetPath)
                }
            }

            if (wav == null) {
                Log.e(TAG, "Falha ao ler IR para $slot")
                allOk = false
                continue
            }
            if (wav.sampleRate != TARGET_SAMPLE_RATE) {
                Log.e(TAG, "Sample rate inválido para $slot: ${wav.sampleRate}Hz")
                allOk = false
                continue
            }

            engine.loadIr(slot, wav.left, wav.right)
            Log.d(TAG, "✅ $slot carregado")
        }
        return allOk
    }

    /** Carrega um arquivo externo num slot específico. */
    fun loadIntoSlot(
        file: File,
        slot: ConvolutionEngine.IrSlot,
        engine: ConvolutionEngine,
        onComplete: ((success: Boolean, error: String?) -> Unit)? = null
    ) {
        try {
            val wav = readWav(file.inputStream())
                ?: return onComplete?.invoke(false, "Falha ao ler WAV") ?: Unit
            if (wav.sampleRate != TARGET_SAMPLE_RATE)
                return onComplete?.invoke(false, "Sample rate: ${wav.sampleRate}Hz (esperado 44100Hz)") ?: Unit
            engine.loadIr(slot, wav.left, wav.right)
            onComplete?.invoke(true, null)
        } catch (e: Exception) {
            onComplete?.invoke(false, e.message)
        }
    }

    // ========== LEITURA ==========

    private fun readWavFromAsset(context: Context, path: String): WavData? {
        return try {
            context.assets.open(path).use { readWav(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir asset $path: ${e.message}")
            null
        }
    }

    fun readWav(stream: InputStream): WavData? {
        return try {
            val bytes = stream.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            if (String(ByteArray(4).also { buf.get(it) }) != "RIFF") return null
            buf.int // file size
            if (String(ByteArray(4).also { buf.get(it) }) != "WAVE") return null

            var audioFormat = 0; var numChannels = 0
            var sampleRate  = 0; var bitsPerSample = 0; var dataSize = 0

            while (buf.remaining() >= 8) {
                val id   = String(ByteArray(4).also { buf.get(it) })
                val size = buf.int  // pode ser negativo para chunks >2GB — irrelevante aqui
                when (id) {
                    "fmt " -> {
                        audioFormat   = buf.short.toInt() and 0xFFFF
                        numChannels   = buf.short.toInt() and 0xFFFF
                        sampleRate    = buf.int
                        buf.int; buf.short  // byteRate, blockAlign
                        bitsPerSample = buf.short.toInt() and 0xFFFF
                        val extra = size - 16
                        if (extra > 0 && buf.remaining() >= extra)
                            buf.position(buf.position() + extra)
                    }
                    "data" -> { dataSize = size; break }
                    else   -> {
                        val skip = size.coerceIn(0, buf.remaining())
                        buf.position(buf.position() + skip)
                    }
                }
            }

            if ((audioFormat != 1 && audioFormat != 3) || numChannels < 1) return null

            val bps      = bitsPerSample / 8
            val nSamples = (dataSize / (bps * numChannels)).coerceAtMost(MAX_SAMPLES)
            val left     = FloatArray(nSamples)
            val right    = FloatArray(nSamples)

            for (i in 0 until nSamples) {
                left[i]  = readSample(buf, bitsPerSample, audioFormat)
                right[i] = if (numChannels >= 2) readSample(buf, bitsPerSample, audioFormat)
                           else left[i]
            }
            WavData(left, right, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parsear WAV: ${e.message}")
            null
        }
    }

    private fun readSample(buf: ByteBuffer, bits: Int, fmt: Int): Float = when (bits) {
        16   -> buf.short.toFloat() / 32768f
        24   -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt()
            ((b2 shl 16) or (b1 shl 8) or b0).toFloat() / 8388608f
        }
        32   -> if (fmt == 3) buf.float else buf.int.toFloat() / 2147483648f
        else -> { if (buf.hasRemaining()) buf.get(); 0f }
    }
}
