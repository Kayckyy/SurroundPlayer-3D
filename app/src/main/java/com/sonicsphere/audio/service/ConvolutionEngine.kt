package com.sonicsphere.audio.service

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Motor de convolução binaural — slots LEFT e RIGHT.
 * Algoritmo: Overlap-Add com FFT complexa (Cooley-Tukey radix-2).
 *
 * NORMALIZAÇÃO: usa energia RMS do IR, não pico.
 * Um IR normalizado por pico ainda pode ter energia total >> 1.0,
 * causando clipping após convolução. A normalização por RMS garante
 * que a energia da saída seja proporcional à entrada.
 */
class ConvolutionEngine(private val sampleRate: Int) {

    companion object {
        private const val TAG = "ConvolutionEngine"
        const val MAX_IR_SAMPLES = 44100
        private const val BLOCK_SIZE = 512
        private const val FFT_SIZE   = 1024  // BLOCK_SIZE * 2
    }

    enum class IrSlot { LEFT, RIGHT }

    private val irFreq      = mutableMapOf<IrSlot, FloatArray>()
    private val slotsLoaded = mutableSetOf<IrSlot>()

    private val inL   = FloatArray(FFT_SIZE)
    private val inR   = FloatArray(FFT_SIZE)
    private var inPos = 0

    private val olL = FloatArray(FFT_SIZE)
    private val olR = FloatArray(FFT_SIZE)

    private val outBufL = FloatArray(BLOCK_SIZE)
    private val outBufR = FloatArray(BLOCK_SIZE)
    private var outPos   = 0
    private var outAvail = 0

    @Volatile var enabled = false

    /** Gain de saída linear. 1.0 = sem alteração. Use setPostGainDb() para ajustar em dB. */
    @Volatile var postGain = 1.0f

    fun setPostGainDb(db: Float) {
        postGain = 10f.pow(db / 20f)
    }

    fun isSlotLoaded(slot: IrSlot) = slot in slotsLoaded
    fun hasPrincipalIrs() = IrSlot.LEFT in slotsLoaded && IrSlot.RIGHT in slotsLoaded

    // ========== CARREGAMENTO ==========

    fun loadIr(slot: IrSlot, leftChannel: FloatArray, rightChannel: FloatArray) {
        val src = if (slot == IrSlot.LEFT) leftChannel else rightChannel
        val len = min(src.size, MAX_IR_SAMPLES)
        val ir = src.copyOf(len)

        // Normalização pela soma L1 (soma dos valores absolutos).
        // A convolução de x[n] com h[n] tem energia máxima = |x|_max * sum(|h[n]|).
        // Dividindo h pelo seu L1 norm, a saída nunca ultrapassa a amplitude de entrada,
        // independente do conteúdo do IR.
        val l1 = ir.sumOf { abs(it).toDouble() }.toFloat()
        if (l1 > 1e-6f) for (i in ir.indices) ir[i] /= l1

        // Transforma para domínio da frequência
        val freq = FloatArray(FFT_SIZE * 2)
        for (i in ir.indices) freq[i * 2] = ir[i]
        fft(freq, inverse = false)
        irFreq[slot] = freq

        slotsLoaded.add(slot)
        reset()
        Log.d(TAG, "IR carregado: $slot | $len samples | L1=$l1")
    }

    fun unloadIr(slot: IrSlot) {
        irFreq.remove(slot)
        slotsLoaded.remove(slot)
        reset()
        Log.d(TAG, "IR removido: $slot")
    }

    // ========== PROCESSAMENTO ==========

    fun process(buffer: ShortArray) {
        if (!enabled || !hasPrincipalIrs()) return
        val frameCount = buffer.size / 2

        for (frame in 0 until frameCount) {
            inL[inPos] = buffer[frame * 2].toFloat()     / 32768f
            inR[inPos] = buffer[frame * 2 + 1].toFloat() / 32768f
            inPos++

            if (inPos >= BLOCK_SIZE) {
                processBlock()
                inPos = 0
            }

            if (outAvail > 0) {
                // Limiter hard antes de converter — nunca clipa
                buffer[frame * 2]     = (outBufL[outPos].coerceIn(-0.95f, 0.95f) * 32767f).toInt().toShort()
                buffer[frame * 2 + 1] = (outBufR[outPos].coerceIn(-0.95f, 0.95f) * 32767f).toInt().toShort()
                outPos++
                outAvail--
            }
        }
    }

    private fun processBlock() {
        val fL = FloatArray(FFT_SIZE * 2).also { for (i in 0 until BLOCK_SIZE) it[i * 2] = inL[i] }
        val fR = FloatArray(FFT_SIZE * 2).also { for (i in 0 until BLOCK_SIZE) it[i * 2] = inR[i] }
        fft(fL, inverse = false)
        fft(fR, inverse = false)

        val resL = FloatArray(FFT_SIZE * 2)
        val resR = FloatArray(FFT_SIZE * 2)
        irFreq[IrSlot.LEFT]?.let  { complexMul(fL, it, resL) }
        irFreq[IrSlot.RIGHT]?.let { complexMul(fR, it, resR) }

        fft(resL, inverse = true)
        fft(resR, inverse = true)

        // Overlap-Add com post-gain
        val g = postGain
        for (i in 0 until BLOCK_SIZE) {
            outBufL[i] = (resL[i * 2] + olL[i]) * g
            outBufR[i] = (resR[i * 2] + olR[i]) * g
        }
        for (i in BLOCK_SIZE until FFT_SIZE) {
            olL[i - BLOCK_SIZE] = resL[i * 2]
            olR[i - BLOCK_SIZE] = resR[i * 2]
        }

        outPos = 0
        outAvail = BLOCK_SIZE
    }

    // ========== FFT COMPLEXA (Cooley-Tukey radix-2, in-place) ==========

    private fun fft(data: FloatArray, inverse: Boolean) {
        val n = data.size / 2
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = data[i*2];   data[i*2]   = data[j*2];   data[j*2]   = tmp
                    tmp = data[i*2+1]; data[i*2+1] = data[j*2+1]; data[j*2+1] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) -1 else 1)
            val wRe = cos(ang); val wIm = sin(ang)
            var pos = 0
            while (pos < n) {
                var curRe = 1.0; var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = data[(pos+k)*2].toDouble();       val uIm = data[(pos+k)*2+1].toDouble()
                    val vRe = data[(pos+k+len/2)*2].toDouble(); val vIm = data[(pos+k+len/2)*2+1].toDouble()
                    val tRe = curRe*vRe - curIm*vIm;           val tIm = curRe*vIm + curIm*vRe
                    data[(pos+k)*2]         = (uRe+tRe).toFloat()
                    data[(pos+k)*2+1]       = (uIm+tIm).toFloat()
                    data[(pos+k+len/2)*2]   = (uRe-tRe).toFloat()
                    data[(pos+k+len/2)*2+1] = (uIm-tIm).toFloat()
                    val newRe = curRe*wRe - curIm*wIm
                    curIm = curRe*wIm + curIm*wRe
                    curRe = newRe
                }
                pos += len
            }
            len = len shl 1
        }
        if (inverse) { val s = 1f / n; for (i in data.indices) data[i] *= s }
    }

    private fun complexMul(a: FloatArray, b: FloatArray, out: FloatArray) {
        val n = a.size / 2
        for (i in 0 until n) {
            val aRe = a[i*2].toDouble(); val aIm = a[i*2+1].toDouble()
            val bRe = b[i*2].toDouble(); val bIm = b[i*2+1].toDouble()
            out[i*2]   = (aRe*bRe - aIm*bIm).toFloat()
            out[i*2+1] = (aRe*bIm + aIm*bRe).toFloat()
        }
    }

    fun reset() {
        inL.fill(0f); inR.fill(0f); inPos = 0
        olL.fill(0f); olR.fill(0f)
        outBufL.fill(0f); outBufR.fill(0f)
        outPos = 0; outAvail = 0
    }
}
