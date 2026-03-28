package com.sonicsphere.audio.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Carrega arquivos WAV estéreo 44.1kHz e alimenta o ConvolutionEngine.
 *
 * Fontes suportadas (em ordem de prioridade):
 *  1. Pasta externa: Android/data/com.sonicsphere.audio/files/ir/
 *  2. Assets embutidos no APK: assets/ir/{left,right,front,top,back,sub}.wav
 *
 * Mapeamento de nome de arquivo → slot:
 *   left.wav  → LEFT    right.wav → RIGHT
 *   front.wav → FRONT   top.wav   → TOP
 *   back.wav  → BACK    sub.wav   → SUB
 */
object IrLoader {

    private const val TAG = "IrLoader"
    private const val TARGET_SAMPLE_RATE = 44100
    private const val MAX_SAMPLES = 44100

    // Nomes dos assets padrão por slot
    private val ASSET_NAMES = mapOf(
        ConvolutionEngine.IrSlot.LEFT  to "ir/left.wav",
        ConvolutionEngine.IrSlot.RIGHT to "ir/right.wav",
        ConvolutionEngine.IrSlot.FRONT to "ir/front.wav",
        ConvolutionEngine.IrSlot.TOP   to "ir/top.wav",
        ConvolutionEngine.IrSlot.BACK  to "ir/back.wav",
        ConvolutionEngine.IrSlot.SUB   to "ir/sub.wav",
    )

    // Nomes de arquivo externo reconhecidos por slot
    private val FILE_NAME_MAP = mapOf(
        "left"  to ConvolutionEngine.IrSlot.LEFT,
        "right" to ConvolutionEngine.IrSlot.RIGHT,
        "front" to ConvolutionEngine.IrSlot.FRONT,
        "top"   to ConvolutionEngine.IrSlot.TOP,
        "back"  to ConvolutionEngine.IrSlot.BACK,
        "sub"   to ConvolutionEngine.IrSlot.SUB,
    )

    data class WavData(
        val left: FloatArray,
        val right: FloatArray,
        val sampleRate: Int,
        val numSamples: Int
    )

    // ========== API PÚBLICA ==========

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
     * Carrega todos os 6 slots automaticamente.
     * Para cada slot, tenta primeiro o arquivo externo; se não existir, usa o asset.
     * Executar em background thread.
     */
    fun loadAllDefaults(
        context: Context,
        engine: ConvolutionEngine,
        onProgress: ((slot: ConvolutionEngine.IrSlot, success: Boolean, error: String?) -> Unit)? = null
    ) {
        val externalDir = getIrDirectory(context)

        for ((slot, assetName) in ASSET_NAMES) {
            // Tenta arquivo externo primeiro (nome = slot em lowercase)
            val slotName = slot.name.lowercase()
            val externalFile = File(externalDir, "$slotName.wav")

            val wav: WavData? = if (externalFile.exists()) {
                Log.d(TAG, "Usando IR externo: ${externalFile.name} → $slot")
                readWav(externalFile)
            } else {
                Log.d(TAG, "Usando IR padrão (asset): $assetName → $slot")
                readWavFromAsset(context, assetName)
            }

            if (wav == null) {
                onProgress?.invoke(slot, false, "Falha ao ler WAV")
                continue
            }

            if (wav.sampleRate != TARGET_SAMPLE_RATE) {
                onProgress?.invoke(slot, false,
                    "Sample rate inválido: ${wav.sampleRate}Hz (esperado ${TARGET_SAMPLE_RATE}Hz)")
                continue
            }

            engine.loadIr(slot, wav.left, wav.right)
            onProgress?.invoke(slot, true, null)
        }
    }

    /**
     * Carrega um arquivo externo específico em um slot.
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
            Log.d(TAG, "IR carregado: ${file.name} → $slot")
            onComplete?.invoke(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar IR: ${file.name}", e)
            onComplete?.invoke(false, e.message)
        }
    }

    /**
     * Detecta o slot pelo nome do arquivo (ex: "front.wav" → FRONT).
     * Retorna null se o nome não for reconhecido.
     */
    fun slotFromFileName(file: File): ConvolutionEngine.IrSlot? {
        return FILE_NAME_MAP[file.nameWithoutExtension.lowercase()]
    }

    // ========== LEITURA ==========

    private fun readWavFromAsset(context: Context, assetPath: String): WavData? {
        return try {
            context.assets.open(assetPath).use { stream ->
                readWavFromStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler asset: $assetPath", e)
            null
        }
    }

    fun readWav(file: File): WavData? {
        return try {
            file.inputStream().use { readWavFromStream(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler WAV: ${file.name}", e)
            null
        }
    }

    private fun readWavFromStream(stream: InputStream): WavData? {
        val bytes = stream.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        val riff = ByteArray(4).also { buf.get(it) }
        if (String(riff) != "RIFF") return null
        buf.int // chunk size
        val wave = ByteArray(4).also { buf.get(it) }
        if (String(wave) != "WAVE") return null

        var audioFormat = 0
        var numChannels = 0
        var sampleRate  = 0
        var bitsPerSample = 0
        var dataSize = 0

        // Scan chunks
        while (buf.remaining() >= 8) {
            val chunkId   = ByteArray(4).also { buf.get(it) }
            val chunkSize = buf.int

            when (String(chunkId)) {
                "fmt " -> {
                    audioFormat   = buf.short.toInt() and 0xFFFF
                    numChannels   = buf.short.toInt() and 0xFFFF
                    sampleRate    = buf.int
                    buf.int   // byteRate
                    buf.short // blockAlign
                    bitsPerSample = buf.short.toInt() and 0xFFFF
                    // Pula bytes extras do fmt se houver
                    val extra = chunkSize - 16
                    if (extra > 0) buf.position(buf.position() + extra)
                }
                "data" -> {
                    dataSize = chunkSize
                    break
                }
                else -> {
                    val skip = chunkSize.coerceAtMost(buf.remaining())
                    buf.position(buf.position() + skip)
                }
            }
        }

        if (audioFormat != 1 && audioFormat != 3) return null
        if (numChannels < 1 || bitsPerSample == 0) return null

        val bytesPerSample = bitsPerSample / 8
        val totalSamples   = (dataSize / (bytesPerSample * numChannels))
            .coerceAtMost(MAX_SAMPLES)

        val leftChannel  = FloatArray(totalSamples)
        val rightChannel = FloatArray(totalSamples)

        for (i in 0 until totalSamples) {
            leftChannel[i] = readSample(buf, bitsPerSample, audioFormat)
            rightChannel[i] = if (numChannels >= 2)
                readSample(buf, bitsPerSample, audioFormat)
            else
                leftChannel[i]
        }

        return WavData(leftChannel, rightChannel, sampleRate, totalSamples)
    }

    private fun readSample(buf: ByteBuffer, bits: Int, format: Int): Float {
        return when (bits) {
            16   -> buf.short.toFloat() / 32768f
            24   -> {
                val b0 = buf.get().toInt() and 0xFF
                val b1 = buf.get().toInt() and 0xFF
                val b2 = buf.get().toInt()
                ((b2 shl 16) or (b1 shl 8) or b0).toFloat() / 8388608f
            }
            32   -> if (format == 3) buf.float else buf.int.toFloat() / 2147483648f
            else -> { buf.get(); 0f }
        }
    }
}
