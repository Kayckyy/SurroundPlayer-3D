package com.sonicsphere.audio.service

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Motor de convolução binaural com cross-talk — 4 IRs.
 *
 * Modelo igual ao OmniAudio Python (process_stereo_fixed):
 *   out_L = conv(inL, IR_LL) + conv(inR, IR_LR) * XTALK_GAIN
 *   out_R = conv(inL, IR_RL) * XTALK_GAIN + conv(inR, IR_RR)
 *
 * Os 4 IRs vêm dos 2 WAVs estéreo existentes:
 *   left.wav  → canal L = IR_LL, canal R = IR_RL
 *   right.wav → canal L = IR_LR, canal R = IR_RR
 *
 * Algoritmo: Overlap-Add com FFT complexa (Cooley-Tukey radix-2).
 * Normalização: L1 norm por IR.
 */
class ConvolutionEngine(private val sampleRate: Int) {

    companion object {
        private const val TAG = "ConvolutionEngine"
        const val MAX_IR_SAMPLES = 44100
        private const val BLOCK_SIZE = 256
        private const val FFT_SIZE   = 1024  // >= BLOCK_SIZE + IR_len - 1
        private const val FADE_STEP = 1f / (44100f * 0.08f)  // ~80ms
        

        // Atenuação do cross-talk — igual ao Python (0.6x)
        private const val XTALK_GAIN = 0.3f
    }

    enum class IrSlot { LEFT, RIGHT }

    // 4 IRs no domínio da frequência
    // LL = ouvido esquerdo ← fonte esquerda (direto)
    // RL = ouvido direito  ← fonte esquerda (cross-talk)
    // LR = ouvido esquerdo ← fonte direita  (cross-talk)
    // RR = ouvido direito  ← fonte direita  (direto)
    private var irLL: FloatArray? = null
    private var irRL: FloatArray? = null
    private var irLR: FloatArray? = null
    private var irRR: FloatArray? = null
    private var fadeGain   = 0f
    private var fadeTarget = 0f

    private val slotsLoaded = mutableSetOf<IrSlot>()

    private val inL   = FloatArray(FFT_SIZE)
    private val inR   = FloatArray(FFT_SIZE)
    private var inPos = 0

    // Buffers de overlap separados para cada contribuição
    private val olLL = FloatArray(FFT_SIZE)
    private val olRL = FloatArray(FFT_SIZE)
    private val olLR = FloatArray(FFT_SIZE)
    private val olRR = FloatArray(FFT_SIZE)

    private val outBufL = FloatArray(BLOCK_SIZE)
    private val outBufR = FloatArray(BLOCK_SIZE)
    private var outPos   = 0
    private var outAvail = 0
    private var xtalkLpfL = 0f
    private var xtalkLpfR = 0f
    private val xtalkLpfCoeff: Float = run {
    val rc = 1.0 / (2.0 * Math.PI * 2800.0)
    val dt = 1.0 / 44100.0
    (dt / (rc + dt)).toFloat()
    
    }

    @Volatile var enabled = false
    set(value) {
        field = value
        fadeTarget = if (value) 1f else 0f
    }
    
    fun isSlotLoaded(slot: IrSlot) = slot in slotsLoaded
    fun hasPrincipalIrs() = IrSlot.LEFT in slotsLoaded && IrSlot.RIGHT in slotsLoaded

    // ========== CARREGAMENTO ==========

    /**
     * Carrega o par de IRs de um WAV estéreo.
     * LEFT:  leftChannel = IR_LL, rightChannel = IR_RL
     * RIGHT: leftChannel = IR_LR, rightChannel = IR_RR
     */
    fun loadIr(slot: IrSlot, leftChannel: FloatArray, rightChannel: FloatArray) {
        val lenL = min(leftChannel.size,  MAX_IR_SAMPLES)
        val lenR = min(rightChannel.size, MAX_IR_SAMPLES)

        val irA = leftChannel.copyOf(lenL)
        val irB = rightChannel.copyOf(lenR)

        // Normalização L1 independente por canal
        normalize(irA)
        normalize(irB)

        val freqA = toFreq(irA)
        val freqB = toFreq(irB)

        when (slot) {
            IrSlot.LEFT  -> { irLL = freqA; irRL = freqB }
            IrSlot.RIGHT -> { irLR = freqA; irRR = freqB }
        }

        slotsLoaded.add(slot)
        reset()
        Log.d(TAG, "IR carregado: $slot | ${lenL}/${lenR} samples")
    }

    fun unloadIr(slot: IrSlot) {
        when (slot) {
            IrSlot.LEFT  -> { irLL = null; irRL = null }
            IrSlot.RIGHT -> { irLR = null; irRR = null }
        }
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
                buffer[frame * 2]     = (outBufL[outPos].coerceIn(-0.95f, 0.95f) * 32767f).toInt().toShort()
                buffer[frame * 2 + 1] = (outBufR[outPos].coerceIn(-0.95f, 0.95f) * 32767f).toInt().toShort()
                outPos++
                outAvail--
            }
        }
    }

    private fun processBlock() {
        // FFT dos blocos de entrada
        val fL = toComplexBlock(inL)
        val fR = toComplexBlock(inR)
        fft(fL, inverse = false)
        fft(fR, inverse = false)

        // 4 convoluções independentes
        val convLL = FloatArray(FFT_SIZE * 2).also { irLL?.let { ir -> complexMul(fL, ir, it) } }
        val convRL = FloatArray(FFT_SIZE * 2).also { irRL?.let { ir -> complexMul(fL, ir, it) } }
        val convLR = FloatArray(FFT_SIZE * 2).also { irLR?.let { ir -> complexMul(fR, ir, it) } }
        val convRR = FloatArray(FFT_SIZE * 2).also { irRR?.let { ir -> complexMul(fR, ir, it) } }

        // IFFT de cada contribuição
        fft(convLL, inverse = true)
        fft(convRL, inverse = true)
        fft(convLR, inverse = true)
        fft(convRR, inverse = true)

        // Overlap-Add + mix com cross-talk:
        // outL = conv(inL, IR_LL) + conv(inR, IR_LR) * XTALK
        // outR = conv(inL, IR_RL) * XTALK + conv(inR, IR_RR)
        for (i in 0 until BLOCK_SIZE) {
    // Fade suave ao ligar/desligar (evita susto de volume)
    if (fadeGain < fadeTarget) fadeGain = minOf(fadeGain + FADE_STEP, fadeTarget)
    else if (fadeGain > fadeTarget) fadeGain = maxOf(fadeGain - FADE_STEP, fadeTarget)

    xtalkLpfL += xtalkLpfCoeff * ((convLR[i*2] + olLR[i]) - xtalkLpfL)
    xtalkLpfR += xtalkLpfCoeff * ((convRL[i*2] + olRL[i]) - xtalkLpfR)
    val outL = (convLL[i*2] + olLL[i]) + xtalkLpfL * XTALK_GAIN
    val outR = xtalkLpfR * XTALK_GAIN + (convRR[i*2] + olRR[i])
    outBufL[i] = outL * fadeGain
    outBufR[i] = outR * fadeGain
        }
        // Salva tails
        for (i in BLOCK_SIZE until FFT_SIZE) {
            olLL[i - BLOCK_SIZE] = convLL[i*2]
            olRL[i - BLOCK_SIZE] = convRL[i*2]
            olLR[i - BLOCK_SIZE] = convLR[i*2]
            olRR[i - BLOCK_SIZE] = convRR[i*2]
        }

        outPos = 0
        outAvail = BLOCK_SIZE
    }

    // ========== UTILITÁRIOS ==========

    private fun normalize(ir: FloatArray) {
        val l1 = ir.sumOf { abs(it).toDouble() }.toFloat()
        if (l1 > 1e-6f) for (i in ir.indices) ir[i] /= l1
    }

    private fun toFreq(ir: FloatArray): FloatArray {
        val freq = FloatArray(FFT_SIZE * 2)
        for (i in ir.indices) freq[i * 2] = ir[i]
        fft(freq, inverse = false)
        return freq
    }

    private fun toComplexBlock(real: FloatArray): FloatArray {
        val out = FloatArray(FFT_SIZE * 2)
        for (i in 0 until BLOCK_SIZE) out[i * 2] = real[i]
        return out
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
        olLL.fill(0f); olRL.fill(0f)
        olLR.fill(0f); olRR.fill(0f)
        outBufL.fill(0f); outBufR.fill(0f)
        outPos = 0; outAvail = 0
        xtalkLpfL = 0f
        xtalkLpfR = 0f
    }
}
