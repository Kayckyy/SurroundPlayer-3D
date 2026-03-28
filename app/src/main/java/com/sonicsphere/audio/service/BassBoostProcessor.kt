package com.sonicsphere.audio.service

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bass Boost com filtro low-shelf IIR.
 * Frequência de corte configurável e ganho em dB.
 * Ao contrário do BassBoost da API Android, não atenua o resto do espectro.
 */
class BassBoostProcessor(private val sampleRate: Int) {

    companion object {
        const val DEFAULT_CUTOFF_HZ = 200f
        const val DEFAULT_GAIN_DB = 0f
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        const val MIN_CUTOFF_HZ = 40f
        const val MAX_CUTOFF_HZ = 500f
    }

    // Coeficientes biquad low-shelf
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0

    // Estado do filtro por canal (L=0, R=1)
    private val stateL = DoubleArray(4) // x1,x2,y1,y2
    private val stateR = DoubleArray(4)

    var cutoffHz: Float = DEFAULT_CUTOFF_HZ
        private set
    var gainDb: Float = DEFAULT_GAIN_DB
        private set
    private var enabled = false

    init {
        computeCoeffs()
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) resetState()
    }

    fun isEnabled() = enabled

    fun setCutoffHz(hz: Float) {
        cutoffHz = hz.coerceIn(MIN_CUTOFF_HZ, MAX_CUTOFF_HZ)
        computeCoeffs()
    }

    fun setGainDb(db: Float) {
        gainDb = db.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        computeCoeffs()
    }

    private fun computeCoeffs() {
        if (gainDb == 0f) {
            b0 = 1.0; b1 = 0.0; b2 = 0.0
            a1 = 0.0; a2 = 0.0
            return
        }

        // Low-shelf filter (Audio EQ Cookbook)
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        // S = 1 para slope máximo (mais musical)
        val alpha = sinW0 / 2.0 * sqrt((A + 1.0 / A) * (1.0 / 1.0 - 1.0) + 2.0)
        val sqrtA2alpha = 2.0 * sqrt(A) * alpha

        val b0c = A * ((A + 1.0) - (A - 1.0) * cosW0 + sqrtA2alpha)
        val b1c = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)
        val b2c = A * ((A + 1.0) - (A - 1.0) * cosW0 - sqrtA2alpha)
        val a0c = (A + 1.0) + (A - 1.0) * cosW0 + sqrtA2alpha
        val a1c = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0)
        val a2c = (A + 1.0) + (A - 1.0) * cosW0 - sqrtA2alpha

        b0 = b0c / a0c
        b1 = b1c / a0c
        b2 = b2c / a0c
        a1 = a1c / a0c
        a2 = a2c / a0c
    }

    private fun resetState() {
        stateL.fill(0.0)
        stateR.fill(0.0)
    }

    /**
     * Processa buffer estéreo intercalado (L, R, L, R, ...) in-place.
     */
    fun process(buffer: ShortArray) {
        if (!enabled || gainDb == 0f || buffer.isEmpty()) return

        val frameCount = buffer.size / 2

        // Canal Left
        var x1 = stateL[0]; var x2 = stateL[1]
        var y1 = stateL[2]; var y2 = stateL[3]
        for (frame in 0 until frameCount) {
            val idx = frame * 2
            val x0 = buffer[idx].toDouble() / 32768.0
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
            buffer[idx] = (y0 * 32768.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        stateL[0] = x1; stateL[1] = x2; stateL[2] = y1; stateL[3] = y2

        // Canal Right
        var x1r = stateR[0]; var x2r = stateR[1]
        var y1r = stateR[2]; var y2r = stateR[3]
        for (frame in 0 until frameCount) {
            val idx = frame * 2 + 1
            val x0 = buffer[idx].toDouble() / 32768.0
            val y0 = b0 * x0 + b1 * x1r + b2 * x2r - a1 * y1r - a2 * y2r
            x2r = x1r; x1r = x0; y2r = y1r; y1r = y0
            buffer[idx] = (y0 * 32768.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        stateR[0] = x1r; stateR[1] = x2r; stateR[2] = y1r; stateR[3] = y2r
    }
}
