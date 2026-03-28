package com.sonicsphere.audio.service

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor de convolução binaurial com 6 slots de IR.
 * Usa overlap-add com FFT complexa correta (Cooley-Tukey radix-2).
 *
 * Os dados FFT são armazenados como pares [Re, Im] intercalados:
 * índice 2k   = parte real do bin k
 * índice 2k+1 = parte imaginária do bin k
 */
class ConvolutionEngine(private val sampleRate: Int) {

    companion object {
        private const val TAG = "ConvolutionEngine"
        const val MAX_IR_SAMPLES = 44100
        private const val BLOCK_SIZE = 512      // hop size — menor = menor latência
        private const val FFT_SIZE = 1024       // BLOCK_SIZE * 2 (potência de 2)

        // Ganhos das reflexões (linear, não dB)
        private const val GAIN_FRONT = 0.20f
        private const val GAIN_TOP   = 0.16f
        private const val GAIN_BACK  = 0.20f
        private const val GAIN_SUB   = 0.13f
    }

    enum class IrSlot { LEFT, RIGHT, FRONT, TOP, BACK, SUB }

    // IR no domínio da frequência (complexo intercalado, tamanho FFT_SIZE*2)
    private val irFreq = mutableMapOf<IrSlot, FloatArray>()
    private val slotsLoaded = mutableSetOf<IrSlot>()

    // Buffers de entrada
    private val inL   = FloatArray(FFT_SIZE)
    private val inR   = FloatArray(FFT_SIZE)
    private val inMid = FloatArray(FFT_SIZE)
    private var inPos = 0

    // Buffers de overlap (tail da convolução)
    private val olL = FloatArray(FFT_SIZE)
    private val olR = FloatArray(FFT_SIZE)

    // Buffer de saída
    private val outBufL = FloatArray(BLOCK_SIZE)
    private val outBufR = FloatArray(BLOCK_SIZE)
    private var outPos = 0
    private var outAvail = 0

    // Filtro sub passa-baixo
    private var subLpfL = 0.0
    private var subLpfR = 0.0
    private val subAlpha: Double = run {
        val rc = 1.0 / (2.0 * PI * 120.0)
        val dt = 1.0 / sampleRate
        dt / (rc + dt)
    }

    @Volatile var enabled = false

    fun isSlotLoaded(slot: IrSlot) = slot in slotsLoaded
    fun hasPrincipalIrs() = IrSlot.LEFT in slotsLoaded && IrSlot.RIGHT in slotsLoaded

    // ========== CARREGAMENTO ==========

    fun loadIr(slot: IrSlot, leftChannel: FloatArray, rightChannel: FloatArray) {
        val len = min(leftChannel.size, MAX_IR_SAMPLES)

        // Para reflexões, usa mid sum; para principais, usa o canal correspondente
        val ir = when (slot) {
            IrSlot.LEFT  -> leftChannel.copyOf(len)
            IrSlot.RIGHT -> rightChannel.copyOf(len)
            else -> FloatArray(len) { i -> (leftChannel[i] + rightChannel[i]) * 0.5f }
        }

        // Psicoacústica por slot
        val processed = when (slot) {
            IrSlot.FRONT -> processFront(ir)
            IrSlot.TOP   -> processTop(ir)
            IrSlot.BACK  -> processBack(ir)
            IrSlot.SUB   -> processSub(ir)
            else -> ir
        }

        // Normaliza
        val peak = processed.maxOfOrNull { abs(it) } ?: 0f
        if (peak > 0f) for (i in processed.indices) processed[i] /= peak

        // Coloca IR no domínio da frequência (zero-padded para FFT_SIZE)
        val timeDomain = FloatArray(FFT_SIZE * 2) // complexo intercalado
        for (i in processed.indices) timeDomain[i * 2] = processed[i]
        fft(timeDomain, false)
        irFreq[slot] = timeDomain

        slotsLoaded.add(slot)
        reset()
        Log.d(TAG, "✅ IR carregado: $slot | ${len} samples")
    }

    fun unloadIr(slot: IrSlot) {
        irFreq.remove(slot)
        slotsLoaded.remove(slot)
        reset()
        Log.d(TAG, "🗑️ IR removido: $slot")
    }

    // ========== PROCESSAMENTO PSICOACÚSTICO ==========

    private fun fadeIn(ir: FloatArray, ms: Double): FloatArray {
        val samples = (ms / 1000.0 * sampleRate).toInt()
        val out = ir.copyOf()
        for (i in 0 until min(samples, out.size)) out[i] *= i.toFloat() / samples
        return out
    }

    private fun hannFadeOut(ir: FloatArray): FloatArray {
        val out = ir.copyOf()
        val start = (out.size * 0.7).toInt()
        for (i in start until out.size) {
            val t = (i - start).toFloat() / (out.size - start)
            out[i] *= (0.5f * (1f + cos(PI * t))).toFloat()
        }
        return out
    }

    private fun processFront(ir: FloatArray): FloatArray {
        return hannFadeOut(fadeIn(ir, 2.0))
    }

    private fun processTop(ir: FloatArray): FloatArray {
        var out = hannFadeOut(fadeIn(ir, 2.0))
        // Shelving de alta para coloração de elevação (~+4dB acima de 8kHz)
        val alpha = exp(-2.0 * PI * 8000.0 / sampleRate)
        var prev = 0.0
        for (i in out.indices) {
            val x = out[i].toDouble()
            val hf = x - alpha * prev
            prev = x
            out[i] = (x + hf * 0.6).toFloat()
        }
        // Notch suave em ~10kHz via comb
        val d = (sampleRate / 10000.0).toInt().coerceAtLeast(1)
        val notched = out.copyOf()
        for (i in d until out.size) notched[i] = out[i] * 0.6f - out[i - d] * 0.4f
        return notched
    }

    private fun processBack(ir: FloatArray): FloatArray {
        // Fade-in um pouco mais longo + a inversão de fase é feita no mix
        return hannFadeOut(fadeIn(ir, 3.0))
    }

    private fun processSub(ir: FloatArray): FloatArray {
        val out = ir.copyOf()
        var lpf = 0.0
        val rc = 1.0 / (2.0 * PI * 120.0)
        val dt = 1.0 / sampleRate
        val a = dt / (rc + dt)
        for (i in out.indices) { lpf += a * (out[i] - lpf); out[i] = lpf.toFloat() }
        return out
    }

    // ========== PROCESSAMENTO PRINCIPAL ==========

    fun process(buffer: ShortArray) {
        if (!enabled || !hasPrincipalIrs()) return

        val frameCount = buffer.size / 2
        for (frame in 0 until frameCount) {
            val sL = buffer[frame * 2].toFloat() / 32768f
            val sR = buffer[frame * 2 + 1].toFloat() / 32768f

            inL[inPos]   = sL
            inR[inPos]   = sR
            inMid[inPos] = (sL + sR) * 0.5f
            inPos++

            if (inPos >= BLOCK_SIZE) {
                processBlock()
                inPos = 0
            }

            if (outAvail > 0) {
                buffer[frame * 2]     = (outBufL[outPos].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                buffer[frame * 2 + 1] = (outBufR[outPos].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                outPos++
                outAvail--
            }
        }
    }

    private fun processBlock() {
        // Prepara blocos complexos (zero-padded para FFT_SIZE)
        val fL   = toComplex(inL)
        val fR   = toComplex(inR)
        val fMid = toComplex(inMid)

        fft(fL,   false)
        fft(fR,   false)
        fft(fMid, false)

        // --- Convolução principal: domínio da frequência → IFFT → tempo ---
        val freqL = FloatArray(FFT_SIZE * 2)
        val freqR = FloatArray(FFT_SIZE * 2)
        irFreq[IrSlot.LEFT]?.let  { complexMulAdd(fL, it, freqL) }
        irFreq[IrSlot.RIGHT]?.let { complexMulAdd(fR, it, freqR) }

        // IFFT dos principais — agora resL/resR estão no domínio do tempo
        val resL = toTime(freqL)
        val resR = toTime(freqR)

        // --- Reflexões: cada uma convoluída separadamente, resultado somado no tempo ---
        irFreq[IrSlot.FRONT]?.let { irF ->
            val spec = FloatArray(FFT_SIZE * 2)
            complexMulAdd(fMid, irF, spec)
            val t = toTime(spec)
            // Delay assimétrico L=0.3ms / R=0.7ms para decorrelação
            for (i in t.indices) {
                resL[i] += (if (i >= 13) t[i - 13] else 0f) * GAIN_FRONT
                resR[i] += (if (i >= 31) t[i - 31] else 0f) * GAIN_FRONT
            }
        }

        irFreq[IrSlot.TOP]?.let { irF ->
            val spec = FloatArray(FFT_SIZE * 2)
            complexMulAdd(fMid, irF, spec)
            val t = toTime(spec)
            val tDecorr = allpass(t)
            for (i in t.indices) {
                resL[i] += t[i] * GAIN_TOP
                resR[i] += tDecorr[i] * GAIN_TOP
            }
        }

        irFreq[IrSlot.BACK]?.let { irF ->
            val spec = FloatArray(FFT_SIZE * 2)
            complexMulAdd(fMid, irF, spec)
            val t = toTime(spec)
            // Delay L=1.5ms + inversão de fase R
            for (i in t.indices) {
                resL[i] += (if (i >= 66) t[i - 66] else 0f) * GAIN_BACK
                resR[i] += -t[i] * GAIN_BACK
            }
        }

        irFreq[IrSlot.SUB]?.let { irF ->
            val spec = FloatArray(FFT_SIZE * 2)
            complexMulAdd(fMid, irF, spec)
            val t = toTime(spec)
            for (i in t.indices) {
                subLpfL += subAlpha * (t[i] - subLpfL)
                subLpfR += subAlpha * (t[i] - subLpfR)
                resL[i] += subLpfL.toFloat() * GAIN_SUB
                resR[i] += subLpfR.toFloat() * GAIN_SUB
            }
        }

        // Overlap-add — resL/resR já estão no tempo, tamanho FFT_SIZE
        for (i in 0 until BLOCK_SIZE) {
            outBufL[i] = resL[i] + olL[i]
            outBufR[i] = resR[i] + olR[i]
        }
        for (i in BLOCK_SIZE until FFT_SIZE) {
            olL[i - BLOCK_SIZE] = resL[i]
            olR[i - BLOCK_SIZE] = resR[i]
        }

        outPos = 0
        outAvail = BLOCK_SIZE
    }

    // ========== FFT COMPLEXA (Cooley-Tukey radix-2) ==========
    // Dados como [Re0, Im0, Re1, Im1, ...], tamanho = N*2

    private fun fft(data: FloatArray, inverse: Boolean) {
        val n = data.size / 2

        // Bit-reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = data[i * 2];   data[i * 2]   = data[j * 2];   data[j * 2]   = tmp
                    tmp = data[i * 2+1]; data[i * 2+1] = data[j * 2+1]; data[j * 2+1] = tmp
            }
        }

        // Butterfly
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) -1 else 1)
            val wRe = cos(ang); val wIm = sin(ang)
            var pos = 0
            while (pos < n) {
                var curRe = 1.0; var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = data[(pos + k) * 2].toDouble()
                    val uIm = data[(pos + k) * 2 + 1].toDouble()
                    val vRe = data[(pos + k + len/2) * 2].toDouble()
                    val vIm = data[(pos + k + len/2) * 2 + 1].toDouble()
                    val tRe = curRe * vRe - curIm * vIm
                    val tIm = curRe * vIm + curIm * vRe
                    data[(pos + k) * 2]         = (uRe + tRe).toFloat()
                    data[(pos + k) * 2 + 1]     = (uIm + tIm).toFloat()
                    data[(pos + k + len/2) * 2]     = (uRe - tRe).toFloat()
                    data[(pos + k + len/2) * 2 + 1] = (uIm - tIm).toFloat()
                    val newRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newRe
                }
                pos += len
            }
            len = len shl 1
        }

        if (inverse) {
            val scale = 1f / n
            for (i in data.indices) data[i] *= scale
        }
    }

    private fun toComplex(real: FloatArray): FloatArray {
        val out = FloatArray(FFT_SIZE * 2)
        for (i in real.indices) out[i * 2] = real[i]
        return out
    }

    private fun toTime(freq: FloatArray): FloatArray {
        val tmp = freq.copyOf()
        fft(tmp, true)
        return FloatArray(FFT_SIZE) { tmp[it * 2] }
    }

    private fun complexMulAdd(a: FloatArray, b: FloatArray, out: FloatArray) {
        val n = a.size / 2
        for (i in 0 until n) {
            val aRe = a[i * 2].toDouble(); val aIm = a[i * 2 + 1].toDouble()
            val bRe = b[i * 2].toDouble(); val bIm = b[i * 2 + 1].toDouble()
            out[i * 2]     += (aRe * bRe - aIm * bIm).toFloat()
            out[i * 2 + 1] += (aRe * bIm + aIm * bRe).toFloat()
        }
    }

    private fun allpass(input: FloatArray): FloatArray {
        val g = 0.7
        val out = FloatArray(input.size)
        var xp = 0.0; var yp = 0.0
        for (i in input.indices) {
            val x = input[i].toDouble()
            val y = -g * x + xp + g * yp
            out[i] = y.toFloat()
            xp = x; yp = y
        }
        return out
    }

    fun reset() {
        inL.fill(0f); inR.fill(0f); inMid.fill(0f); inPos = 0
        olL.fill(0f); olR.fill(0f)
        outBufL.fill(0f); outBufR.fill(0f)
        outPos = 0; outAvail = 0
        subLpfL = 0.0; subLpfR = 0.0
    }
}
