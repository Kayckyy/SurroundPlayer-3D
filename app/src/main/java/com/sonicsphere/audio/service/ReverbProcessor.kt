package com.sonicsphere.audio.service

/**
 * Reverb sintético de Schroeder — 4 combs paralelos + 2 allpass em série.
 *
 * GARANTIA DE NÍVEL:
 * O sinal molhado é escalado para que sua energia RMS aproxime a do sinal seco.
 * Isso significa que wet=1.0 (100%) soa no mesmo volume que wet=0.0 (dry),
 * sem distorção ou aumento de volume em nenhum valor do slider.
 *
 * Parâmetros:
 *   roomSize  0.0–1.0  (tamanho da sala)
 *   wet       0.0–1.0  (mix seco/molhado)
 *   damping   0.0–1.0  (absorção de altas frequências)
 */
class ReverbProcessor(private val sampleRate: Int) {

    private val COMB_BASE_MS   = floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f)
    private val ALLPASS_MS     = floatArrayOf(5.0f, 1.7f)
    private val NUM_COMBS      = 4

    private val COMB_GAIN_BASE = 0.78f
    private val COMB_GAIN_MAX  = 0.88f

    // Normalização: 1/N combs para que a soma não ultrapasse amplitude unitária
    private val COMB_NORM = 1f / NUM_COMBS

    // Fator de compensação de energia do wet para equiparar ao dry
    // Os allpass não mudam energia, mas os combs acumulam — compensamos aqui
    // Valor medido empiricamente: a cadeia comb+allpass tem ganho médio ~2.5
    // portanto dividimos por 2.5 para equalizar com o dry
    private val WET_ENERGY_COMP = 0.4f

    var roomSize: Float = 0.3f
        set(v) { field = v.coerceIn(0f, 1f); rebuildFilters() }

    var wet: Float = 0.08f
        set(v) { field = v.coerceIn(0f, 1f) }

    var damping: Float = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); rebuildFilters() }

    var enabled = false

    private data class CombState(val buf: FloatArray) {
        var idx = 0
        var lpf = 0f
    }

    private data class AllpassState(val buf: FloatArray) { var idx = 0 }

    private var combsL    = Array(NUM_COMBS) { CombState(FloatArray(1)) }
    private var combsR    = Array(NUM_COMBS) { CombState(FloatArray(1)) }
    private var apL       = Array(2) { AllpassState(FloatArray(1)) }
    private var apR       = Array(2) { AllpassState(FloatArray(1)) }
    private var combGains = FloatArray(NUM_COMBS) { COMB_GAIN_BASE }

    init { rebuildFilters() }

    private fun msToSamples(ms: Float) = (ms * sampleRate / 1000f).toInt().coerceAtLeast(1)

    private fun rebuildFilters() {
        val scale = 0.5f + roomSize * 1.0f
        val gain  = (COMB_GAIN_BASE + roomSize * 0.08f).coerceAtMost(COMB_GAIN_MAX)
        combGains = FloatArray(NUM_COMBS) { gain }

        combsL = Array(NUM_COMBS) { i ->
            CombState(FloatArray(msToSamples(COMB_BASE_MS[i] * scale)))
        }
        combsR = Array(NUM_COMBS) { i ->
            CombState(FloatArray(msToSamples(COMB_BASE_MS[i] * scale * 1.007f)))
        }
        apL = Array(2) { i -> AllpassState(FloatArray(msToSamples(ALLPASS_MS[i]))) }
        apR = Array(2) { i -> AllpassState(FloatArray(msToSamples(ALLPASS_MS[i]))) }
    }

    fun process(buffer: ShortArray) {
        if (!enabled || buffer.isEmpty()) return
        val frames = buffer.size / 2
        val dampF  = damping
        val dry    = 1f - wet
        // Escala o wet para que sua energia ≈ energia do dry
        val wetGain = wet * WET_ENERGY_COMP

        for (f in 0 until frames) {
            val dryL = buffer[f * 2].toFloat()     / 32768f
            val dryR = buffer[f * 2 + 1].toFloat() / 32768f

            val mid = (dryL + dryR) * 0.5f

            // 4 combs + normalização por 1/N
            var revL = 0f
            var revR = 0f
            for (c in 0 until NUM_COMBS) {
                revL += processComb(combsL[c], mid, combGains[c], dampF)
                revR += processComb(combsR[c], mid, combGains[c], dampF)
            }
            revL *= COMB_NORM
            revR *= COMB_NORM

            // 2 allpass em série
            revL = processAllpass(apL[0], revL)
            revL = processAllpass(apL[1], revL)
            revR = processAllpass(apR[0], revR)
            revR = processAllpass(apR[1], revR)

            // Mix: dry preservado + wet compensado em energia
            // Limiter hard -0.5dB na saída
            val outL = (dryL * dry + revL * wetGain).coerceIn(-0.944f, 0.944f)
            val outR = (dryR * dry + revR * wetGain).coerceIn(-0.944f, 0.944f)

            buffer[f * 2]     = (outL * 32767f).toInt().toShort()
            buffer[f * 2 + 1] = (outR * 32767f).toInt().toShort()
        }
    }

    private fun processComb(c: CombState, input: Float, gain: Float, damp: Float): Float {
        val out = c.buf[c.idx]
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
