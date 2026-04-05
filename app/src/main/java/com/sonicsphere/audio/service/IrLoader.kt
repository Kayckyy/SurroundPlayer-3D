package com.sonicsphere.audio.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Carrega WAVs estéreo 44.1kHz no ConvolutionEngine.
 * Prioridade: arquivo externo (files/ir/) > asset embutido (assets/ir/).
 *
 * NOTA: Usa FileInputStream diretamente para evitar EACCES em Android 10+.
 * A pasta getExternalFilesDir() pertence ao app e não precisa de permissão,
 * mas file.inputStream() pode falhar dependendo do contexto de segurança.
 */
object IrLoader {

    private const val TAG = "IrLoader"
    private const val TARGET_SAMPLE_RATE = 44100
    private const val MAX_SAMPLES = 44100

    private val SLOT_ASSET = mapOf(
        ConvolutionEngine.IrSlot.LEFT  to "left.wav",
        ConvolutionEngine.IrSlot.RIGHT to "right.wav",
    )

    data class WavData(val left: FloatArray, val right: FloatArray, val sampleRate: Int)

    // ========== API PÚBLICA ==========

    fun getIrDirectory(context: Context): File =
        File(context.getExternalFilesDir(null), "ir").also { if (!it.exists()) it.mkdirs() }

    fun listAvailableIrs(context: Context): List<File> =
        getIrDirectory(context)
            .listFiles { f -> f.extension.lowercase() == "wav" }
            ?.sortedBy { it.name } ?: emptyList()

    fun loadDefaults(context: Context, engine: ConvolutionEngine): Boolean {
        val externalDir = getIrDirectory(context)
        var allOk = true

        for ((slot, assetPath) in SLOT_ASSET) {
            val externalFile = File(externalDir, "${slot.name.lowercase()}.wav")
            val wav: WavData? = when {
                externalFile.exists() && externalFile.canRead() -> {
                    Log.d(TAG, "IR externo: ${externalFile.name} → $slot")
                    openFileStream(externalFile)?.use { readWav(it) }
                }
                else -> {
                    Log.d(TAG, "IR asset: $assetPath → $slot")
                    readWavFromAsset(context, assetPath)
                }
            }

            if (wav == null) { Log.e(TAG, "Falha ao ler IR para $slot"); allOk = false; continue }
            if (wav.sampleRate != TARGET_SAMPLE_RATE) {
                Log.e(TAG, "Sample rate inválido para $slot: ${wav.sampleRate}Hz")
                allOk = false; continue
            }

            engine.loadIr(slot, wav.left, wav.right)
            Log.d(TAG, "✅ $slot carregado")
        }
        return allOk
    }

    fun loadIntoSlot(
        file: File,
        slot: ConvolutionEngine.IrSlot,
        engine: ConvolutionEngine,
        onComplete: ((success: Boolean, error: String?) -> Unit)? = null
    ) {
        try {
            if (!file.exists()) {
                onComplete?.invoke(false, "Arquivo não encontrado: ${file.name}")
                return
            }
            if (!file.canRead()) {
                onComplete?.invoke(false, "Sem permissão de leitura: ${file.name}")
                return
            }

            val stream = openFileStream(file)
                ?: return onComplete?.invoke(false, "Não foi possível abrir: ${file.name}") ?: Unit

            val wav = stream.use { readWav(it) }
                ?: return onComplete?.invoke(false, "Falha ao parsear WAV: ${file.name}") ?: Unit

            if (wav.sampleRate != TARGET_SAMPLE_RATE)
                return onComplete?.invoke(false,
                    "Sample rate: ${wav.sampleRate}Hz (esperado 44100Hz)") ?: Unit

            engine.loadIr(slot, wav.left, wav.right)
            onComplete?.invoke(true, null)
            Log.d(TAG, "✅ IR carregado: ${file.name} → $slot")

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Permissão negada: ${file.absolutePath}", e)
            onComplete?.invoke(false, "Permissão negada. Verifique se o arquivo está em " +
                "Android/data/com.sonicsphere.audio/files/ir/")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao carregar IR: ${file.name}", e)
            onComplete?.invoke(false, e.message ?: "Erro desconhecido")
        }
    }

    // ========== LEITURA ==========

    /**
     * Abre um arquivo usando FileInputStream diretamente.
     * Evita o EACCES que ocorre com file.inputStream() em alguns contextos Android 10+.
     */
    private fun openFileStream(file: File): InputStream? {
        return try {
            FileInputStream(file)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException ao abrir ${file.name}: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir ${file.name}: ${e.message}")
            null
        }
    }

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
            buf.int
            if (String(ByteArray(4).also { buf.get(it) }) != "WAVE") return null

            var audioFormat = 0; var numChannels = 0
            var sampleRate  = 0; var bitsPerSample = 0; var dataSize = 0

            while (buf.remaining() >= 8) {
                val id   = String(ByteArray(4).also { buf.get(it) })
                val size = buf.int
                when (id) {
                    "fmt " -> {
                        audioFormat   = buf.short.toInt() and 0xFFFF
                        numChannels   = buf.short.toInt() and 0xFFFF
                        sampleRate    = buf.int
                        buf.int; buf.short
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
            normalizeIr(left, right)
            WavData(left, right, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parsear WAV: ${e.message}")
            null
        }
    }

    private fun normalizeIr(left: FloatArray, right: FloatArray) {
    val peak = maxOf(
        left.maxOfOrNull { kotlin.math.abs(it) } ?: 0f,
        right.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
    )
    if (peak > 0f && peak != 1f) {
        val gain = 1f / peak
        for (i in left.indices) { left[i] *= gain; right[i] *= gain }
        Log.d(TAG, "IR normalizada — peak era $peak, gain aplicado: $gain")
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
