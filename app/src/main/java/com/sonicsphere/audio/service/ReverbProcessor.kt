package com.sonicsphere.audio.service

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Reverb sintético de Schroeder — 4 filtros comb paralelos + 2 allpass em série.
 * Parâmetros expostos:
 *   roomSize  0.0–1.0  (tamanho da sala — controla os delay times dos combs)
 *   wet       0.0–1.0  (mix seco/molhado)
 *   damping   0.0–1.0  (absorção de altas frequências — simula material da sala)
 */
class ReverbProcessor(private val sampleRate: Int) {

    // Delay times dos 4 combs (ms) para sala média — escalonados por roomSize
    private val COMB_BASE_MS = floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f)
    // Delay times dos allpass (ms) — fixos
    private val ALLPASS_MS   = floatArrayOf(5.0f, 1.7f)

    private val COMB_GAIN_BASE = 0.84f   // feedback base dos combs

    var roomSize: Float = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); rebuildFilters() }

    var wet: Float = 0.25f
        set(v) { field = v.coerceIn(0f, 1f) }

    var damping: Float = 0.4f
        set(v) { field = v.coerceIn(0f, 1f); rebuildFilters() }

    var enabled = false

    // --- Estado interno ---

    // Comb filters: 2 instâncias (L e R) × 4 combs
    // Cada comb: buffer circular + último valor LPF para damping
    private data class CombState(val buf: FloatArray) {
        var idx = 0
        var lpf = 0f
    }

    // Allpass filters: 2 instâncias (L e R) × 2 allpass
    private data class AllpassState(val buf: FloatArray) { var idx = 0 }

    private var combsL  = Array(4) { CombState(FloatArray(1)) }
    private var combsR  = Array(4) { CombState(FloatArray(1)) }
    private var apL     = Array(2) { AllpassState(FloatArray(1)) }
    private var apR     = Array(2) { AllpassState(FloatArray(1)) }
    private var combGains = FloatArray(4) { COMB_GAIN_BASE }

    init { rebuildFilters() }

    private fun msToSamples(ms: Float) = (ms * sampleRate / 1000f).toInt().coerceAtLeast(1)

    private fun rebuildFilters() {
        // Escala delay dos combs com roomSize (0.5x a 2x)
        val scale = 0.5f + roomSize * 1.5f
        // Gain dos combs aumenta com roomSize (sala maior = decaimento mais longo)
        val gain  = (COMB_GAIN_BASE + roomSize * 0.12f).coerceAtMost(0.97f)

        combGains = FloatArray(4) { gain }

        // Reconstrói apenas se tamanho mudou (preserva fase)
        combsL = Array(4) { i -> CombState(FloatArray(msToSamples(COMB_BASE_MS[i] * scale))) }
        combsR = Array(4) { i ->
            // Ligeiro stereo spread: canal R tem delays um pouco maiores
            CombState(FloatArray(msToSamples(COMB_BASE_MS[i] * scale * 1.007f)))
        }
        apL = Array(2) { i -> AllpassState(FloatArray(msToSamples(ALLPASS_MS[i]))) }
        apR = Array(2) { i -> AllpassState(FloatArray(msToSamples(ALLPASS_MS[i]))) }
    }

    /**
     * Processa buffer estéreo intercalado (L, R, L, R, ...) in-place.
     * Aplica reverb com mix dry/wet.
     */
    fun process(buffer: ShortArray) {
        if (!enabled || buffer.isEmpty()) return
        val frames = buffer.size / 2
        val dampF  = damping
        val dry    = 1f - wet

        for (f in 0 until frames) {
            val dryL = buffer[f * 2].toFloat()     / 32768f
            val dryR = buffer[f * 2 + 1].toFloat() / 32768f

            // Input mono para os combs (mid sum)
            val mid = (dryL + dryR) * 0.5f

            // 4 combs paralelos → somados
            var revL = 0f
            var revR = 0f
            for (c in 0 until 4) {
                revL += processComb(combsL[c], mid, combGains[c], dampF)
                revR += processComb(combsR[c], mid, combGains[c], dampF)
            }

            // 2 allpass em série
            revL = processAllpass(apL[0], revL)
            revL = processAllpass(apL[1], revL)
            revR = processAllpass(apR[0], revR)
            revR = processAllpass(apR[1], revR)

            // Mix dry/wet
            val outL = (dryL * dry + revL * wet).coerceIn(-1f, 1f)
            val outR = (dryR * dry + revR * wet).coerceIn(-1f, 1f)

            buffer[f * 2]     = (outL * 32767f).toInt().toShort()
            buffer[f * 2 + 1] = (outR * 32767f).toInt().toShort()
        }
    }

    private fun processComb(c: CombState, input: Float, gain: Float, damp: Float): Float {
        val out = c.buf[c.idx]
        // LPF para damping (absorção de altas)
        c.lpf = out * (1f - damp) + c.lpf * damp
        c.buf[c.idx] = input + c.lpf * gain
        c.idx = (c.idx + 1) % c.buf.size
        return out
    }

    private fun processAllpass(a: AllpassState, input: Float): Float {
        val buffered = a.buf[a.idx]
        a.buf[a.idx] = input + buffered * 0.5f
        a.idx = (a.idx + 1) % a.buf.size
        return buffered - input
    }

    fun reset() {
        combsL.forEach { it.buf.fill(0f); it.idx = 0; it.lpf = 0f }
        combsR.forEach { it.buf.fill(0f); it.idx = 0; it.lpf = 0f }
        apL.forEach    { it.buf.fill(0f); it.idx = 0 }
        apR.forEach    { it.buf.fill(0f); it.idx = 0 }
    }
}
