package com.sonicsphere.audio.service

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Equalizador de 8 bandas com filtros biquad peaking IIR.
 * Cada banda é um filtro paramétrico com frequência central e ganho em dB.
 * Ganho 0dB = sem alteração. Intervalo recomendado: -12dB a +12dB.
 */
class EqualizerProcessor(private val sampleRate: Int) {

    companion object {
        val BAND_FREQUENCIES = intArrayOf(60, 170, 310, 600, 1000, 3000, 6000, 14000)
        const val BAND_COUNT = 8
        private const val DEFAULT_Q = 1.4f  // Q padrão para bandas peaking
    }

    // Coeficientes biquad para cada banda (b0, b1, b2, a1, a2)
    private val coeffs = Array(BAND_COUNT) { DoubleArray(5) }

    // Estado do filtro por banda, por canal (L=0, R=1)
    private val state = Array(BAND_COUNT) { Array(2) { DoubleArray(4) } } // x1,x2,y1,y2

    private val gainDb = FloatArray(BAND_COUNT) { 0f }
    private var enabled = false

    init {
        for (i in 0 until BAND_COUNT) {
            computeCoeffs(i)
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) resetState()
    }

    fun isEnabled() = enabled

    fun setBandGainDb(band: Int, db: Float) {
        if (band !in 0 until BAND_COUNT) return
        gainDb[band] = db.coerceIn(-15f, 15f)
        computeCoeffs(band)
    }

    fun getBandGainDb(band: Int): Float {
        if (band !in 0 until BAND_COUNT) return 0f
        return gainDb[band]
    }

    fun getBandFrequency(band: Int): Int {
        if (band !in 0 until BAND_COUNT) return 0
        return BAND_FREQUENCIES[band]
    }

    private fun computeCoeffs(band: Int) {
        val freq = BAND_FREQUENCIES[band].toDouble()
        val db = gainDb[band].toDouble()
        val q = DEFAULT_Q.toDouble()

        if (db == 0.0) {
            // Passthrough — identidade
            coeffs[band][0] = 1.0
            coeffs[band][1] = 0.0
            coeffs[band][2] = 0.0
            coeffs[band][3] = 0.0
            coeffs[band][4] = 0.0
            return
        }

        // Filtro peaking EQ (Audio EQ Cookbook — Robert Bristow-Johnson)
        val A = 10.0.pow(db / 40.0)
        val w0 = 2.0 * PI * freq / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)

        val b0 = 1.0 + alpha * A
        val b1 = -2.0 * cosW0
        val b2 = 1.0 - alpha * A
        val a0 = 1.0 + alpha / A
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha / A

        coeffs[band][0] = b0 / a0
        coeffs[band][1] = b1 / a0
        coeffs[band][2] = b2 / a0
        coeffs[band][3] = a1 / a0
        coeffs[band][4] = a2 / a0
    }

    private fun resetState() {
        for (band in 0 until BAND_COUNT) {
            for (ch in 0..1) {
                state[band][ch].fill(0.0)
            }
        }
    }

    /**
     * Processa buffer estéreo intercalado (L, R, L, R, ...) in-place.
     */
    fun process(buffer: ShortArray) {
        if (!enabled || buffer.isEmpty()) return

        val frameCount = buffer.size / 2

        for (band in 0 until BAND_COUNT) {
            if (gainDb[band] == 0f) continue

            val c = coeffs[band]
            val b0 = c[0]; val b1 = c[1]; val b2 = c[2]
            val a1 = c[3]; val a2 = c[4]

            for (ch in 0..1) {
                val s = state[band][ch]
                var x1 = s[0]; var x2 = s[1]
                var y1 = s[2]; var y2 = s[3]

                for (frame in 0 until frameCount) {
                    val idx = frame * 2 + ch
                    val x0 = buffer[idx].toDouble() / 32768.0

                    val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

                    x2 = x1; x1 = x0
                    y2 = y1; y1 = y0

                    buffer[idx] = (y0 * 32768.0).toInt()
                        .coerceIn(-32768, 32767).toShort()
                }

                s[0] = x1; s[1] = x2; s[2] = y1; s[3] = y2
            }
        }
    }
}
