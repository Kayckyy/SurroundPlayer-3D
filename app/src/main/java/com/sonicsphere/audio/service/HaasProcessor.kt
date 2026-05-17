package com.sonicsphere.audio.service

import android.util.Log
import kotlin.math.min

/**
 * Processador Haas Effect em Kotlin puro
 * Aplica delay no canal direito para criar espacialização
 */
class HaasProcessor(private val sampleRate: Int) {

    companion object {
        private const val TAG = "HaasProcessor"
        private const val MAX_DELAY_MS = 100 // REDUZIDO PARA 100ms
    }

    private var enabled = false
    private var delayMs = 0
    private var delaySamples = 0

    // Buffers de delay (circular buffer)
    private var delayBufferLeft: FloatArray = FloatArray(0)
    private var delayBufferRight: FloatArray = FloatArray(0)
    private var bufferIndex = 0

    init {
        updateDelayBuffers()
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        Log.d(TAG, "Haas ${if (enabled) "ON" else "OFF"}")
    }

    fun setDelayMs(delayMs: Int) {
        if (delayMs < 0 || delayMs > MAX_DELAY_MS) return

        this.delayMs = delayMs
        this.delaySamples = (delayMs * sampleRate) / 1000
        updateDelayBuffers()

        Log.d(TAG, "Delay: ${delayMs}ms (${delaySamples} samples)")
    }

    private fun updateDelayBuffers() {
        val maxSamples = (MAX_DELAY_MS * sampleRate) / 1000
        delayBufferLeft = FloatArray(maxSamples)
        delayBufferRight = FloatArray(maxSamples)
        bufferIndex = 0
    }

    /**
     * Processa buffer estéreo intercalado (L, R, L, R, ...)
     * Aplica delay no canal direito
     */
    fun process(buffer: ShortArray) {
    if (!enabled || delaySamples == 0 || buffer.isEmpty()) return

    // Threshold de "sideness" — abaixo disso considera mid puro
    // 0.02 = ~2% de diferença entre L e R
    val sideThreshold = 0.02f

    var i = 0
    while (i < buffer.size - 1) {
        val leftSample  = buffer[i].toFloat()     / Short.MAX_VALUE
        val rightSample = buffer[i + 1].toFloat() / Short.MAX_VALUE

        val delayedLeft  = delayBufferLeft[bufferIndex]
        val delayedRight = delayBufferRight[bufferIndex]

        delayBufferLeft[bufferIndex]  = leftSample
        delayBufferRight[bufferIndex] = rightSample

        bufferIndex = (bufferIndex + 1) % delaySamples

        // Se a diferença L/R for insignificante, é mid puro — bypass do ITD
        val side = kotlin.math.abs(leftSample - rightSample)
        if (side < sideThreshold) {
            // Deixa o sample intocado (bass, vocal central)
            buffer[i]     = (leftSample  * Short.MAX_VALUE).toInt().toShort()
            buffer[i + 1] = (rightSample * Short.MAX_VALUE).toInt().toShort()
        } else {
            // Tem conteúdo side — aplica Haas normalmente
            buffer[i]     = (leftSample  * Short.MAX_VALUE).toInt().toShort()
            buffer[i + 1] = (delayedRight * Short.MAX_VALUE).toInt().toShort()
        }

        i += 2
    }
    }
    fun isEnabled(): Boolean = enabled
    fun getDelayMs(): Int = delayMs
}
