package com.sonicsphere.audio.service

/**
 * Reverb sintetico de Schroeder — 4 filtros comb paralelos + 2 allpass em serie.
 * Low cut em 200Hz aplicado na ENTRADA dos combs (nao na saida).
 */
class ReverbProcessor(private val sampleRate: Int) {

    private val COMB_BASE_MS   = floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f)
    private val ALLPASS_MS     = floatArrayOf(5.0f, 1.7f)
    private val COMB_GAIN_BASE = 0.84f

    private var roomSize = 0.25f
    private var wet      = 0.40f
    private var damping  = 0.7f

    var enabled = false

    private data class CombState(val buf: FloatArray) {
        var idx = 0
        var lpf = 0f
    }

    private data class AllpassState(val buf: FloatArray) { var idx = 0 }

    private var combsL = Array(4) { CombState(FloatArray(1)) }
    private var combsR = Array(4) { CombState(FloatArray(1)) }
    private var apL    = Array(2) { AllpassState(FloatArray(1)) }
    private var apR    = Array(2) { AllpassState(FloatArray(1)) }
    private var combGains = FloatArray(4) { COMB_GAIN_BASE }

    // Low cut 200Hz na entrada — HPF de 1a ordem
    private var lcL = 0f
    private var lcR = 0f
    private val lcCoeff: Float = run {
        val rc = 1.0 / (2.0 * Math.PI * 200.0)
        val dt = 1.0 / sampleRate
        (rc / (rc + dt)).toFloat()
    }

    init { buildFilters() }

    private fun msToSamples(ms: Float) = (ms * sampleRate / 1000f).toInt().coerceAtLeast(1)

    private fun buildFilters() {
        val scale = 0.5f + roomSize * 1.5f
        val gain  = (COMB_GAIN_BASE + roomSize * 0.12f).coerceAtMost(0.97f)
        combGains = FloatArray(4) { gain }
        combsL = Array(4) { i -> CombState(FloatArray(msToSamples(COMB_BASE_MS[i] * scale))) }
        combsR = Array(4) { i ->
            CombState(FloatArray(msToSamples(COMB_BASE_MS[i] * scale * 1.007f)))
        }
        apL = Array(2) { i -> AllpassState(FloatArray(msToSamples(ALLPASS_MS[i]))) }
        apR = Array(2) { i -> AllpassState(FloatArray(msToSamples(ALLPASS_MS[i]))) }
    }

    fun process(buffer: ShortArray) {
        if (!enabled || buffer.isEmpty()) return
        val frames = buffer.size / 2
        val dry    = 1f - wet

        for (f in 0 until frames) {
            val dryL = buffer[f * 2].toFloat()     / 32768f
            val dryR = buffer[f * 2 + 1].toFloat() / 32768f

            // Low cut 200Hz na entrada — remove graves antes dos combs
            lcL += lcCoeff * (dryL - lcL)
            lcR += lcCoeff * (dryR - lcR)
            val revInputL = dryL - lcL
            val revInputR = dryR - lcR

            var revL = 0f
            var revR = 0f
            for (c in 0 until 4) {
                revL += processComb(combsL[c], revInputL, combGains[c], damping)
                revR += processComb(combsR[c], revInputR, combGains[c], damping)
            }

            revL = processAllpass(apL[0], revL)
            revL = processAllpass(apL[1], revL)
            revR = processAllpass(apR[0], revR)
            revR = processAllpass(apR[1], revR)

            val outL = (dryL * dry + revL * wet).coerceIn(-1f, 1f)
            val outR = (dryR * dry + revR * wet).coerceIn(-1f, 1f)

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

    // ========== MÉTODOS PÚBLICOS PARA CONFIGURAÇÃO ==========

fun setRoomSize(value: Float) {
    roomSize = value.coerceIn(0.05f, 0.5f)
    buildFilters()  // Reconstrói os buffers com o novo tamanho
}

fun setWetLevel(value: Float) {
    wet = value.coerceIn(0.0f, 0.5f)
}

fun setDamping(value: Float) {
    damping = value.coerceIn(0.5f, 0.95f)
}

fun getRoomSize(): Float = roomSize
fun getWetLevel(): Float = wet
fun getDamping(): Float = damping

// Configuração rápida para áudio 3D (valores otimizados)
fun setFor3DAudio() {
    roomSize = 0.15f
    wet = 0.10f
    damping = 0.7f
    buildFilters()
}

    fun reset() {
        combsL.forEach { it.buf.fill(0f); it.idx = 0; it.lpf = 0f }
        combsR.forEach { it.buf.fill(0f); it.idx = 0; it.lpf = 0f }
        apL.forEach    { it.buf.fill(0f); it.idx = 0 }
        apR.forEach    { it.buf.fill(0f); it.idx = 0 }
        lcL = 0f
        lcR = 0f
    }
}
