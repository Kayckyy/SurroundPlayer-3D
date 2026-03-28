package com.sonicsphere.audio.service

import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Motor de convolução binaurial com 6 slots de IR.
 *
 * Slots principais (L/R):
 *   - Convolução estéreo completa com os IRs
 *   - Sem processamento psicoacústico — fidelidade máxima
 *
 * Slots de reflexão (Frente, Cima, Atrás, Sub):
 *   - Convoluem o sinal Mid (mono sum) com o IR
 *   - Cada slot aplica decorrelação/coloração espectral própria
 *   - Mistura de volta no estéreo com ganho reduzido
 *
 * Algoritmo: Overlap-Add com FFT complexa (pares Re/Im intercalados).
 */
class ConvolutionEngine(private val sampleRate: Int) {

    companion object {
        private const val TAG = "ConvolutionEngine"
        private const val MAX_IR_SAMPLES = 44100
        // FFT_SIZE = número de pontos complexos → array tem FFT_SIZE*2 floats [Re,Im,Re,Im,...]
        private const val FFT_SIZE = 2048
        private const val HOP_SIZE = FFT_SIZE / 4  // amostras reais por bloco

        private const val GAIN_FRONT = 0.20f
        private const val GAIN_TOP   = 0.16f
        private const val GAIN_BACK  = 0.20f
        private const val GAIN_SUB   = 0.13f

        private const val DELAY_FRONT_L = 13
        private const val DELAY_FRONT_R = 31
        private const val DELAY_BACK_L  = 66
        private const val DELAY_BACK_R  = 0
    }

    enum class IrSlot { LEFT, RIGHT, FRONT, TOP, BACK, SUB }

    private val irData       = mutableMapOf<IrSlot, FloatArray>()
    private val irPartitions = mutableMapOf<IrSlot, Array<FloatArray>>()

    private val overlapL     = FloatArray(HOP_SIZE)
    private val overlapR     = FloatArray(HOP_SIZE)
    private val overlapFront = FloatArray(HOP_SIZE)
    private val overlapTop   = FloatArray(HOP_SIZE)
    private val overlapBack  = FloatArray(HOP_SIZE)
    private val overlapSub   = FloatArray(HOP_SIZE)

    private val inputBufferL   = FloatArray(HOP_SIZE)
    private val inputBufferR   = FloatArray(HOP_SIZE)
    private val inputBufferMid = FloatArray(HOP_SIZE)
    private var inputPos = 0

    // Fila circular de saida
    private val OUTPUT_QUEUE = HOP_SIZE * 8
    private val outQueueL  = FloatArray(OUTPUT_QUEUE)
    private val outQueueR  = FloatArray(OUTPUT_QUEUE)
    private var outWritePos  = 0
    private var outReadPos   = 0
    private var outAvailable = 0

    private var subLpfL = 0.0
    private var subLpfR = 0.0
    private val subLpfCoeff: Double

    @Volatile var enabled = false
    private val slotsLoaded = mutableSetOf<IrSlot>()

    // Variaveis mantidas para compatibilidade com reset()
    private var allpassStateL = 0.0
    private var allpassStateR = 0.0
    private val delayLineL = FloatArray(128)
    private val delayLineR = FloatArray(128)
    private var delayIdx = 0

    init {
        val rc = 1.0 / (2.0 * PI * 120.0)
        val dt = 1.0 / sampleRate
        subLpfCoeff = dt / (rc + dt)
    }

    // ========== CARREGAMENTO DE IR ==========

    fun loadIr(slot: IrSlot, leftChannel: FloatArray, rightChannel: FloatArray) {
        val len = min(leftChannel.size, MAX_IR_SAMPLES)

        val ir = when (slot) {
            IrSlot.LEFT  -> leftChannel.copyOf(len)
            IrSlot.RIGHT -> rightChannel.copyOf(len)
            else -> FloatArray(len) { i -> (leftChannel[i] + rightChannel[i]) * 0.5f }
        }

        val processed = when (slot) {
            IrSlot.FRONT -> applyFrontPsychoacoustics(ir)
            IrSlot.TOP   -> applyTopPsychoacoustics(ir)
            IrSlot.BACK  -> applyBackPsychoacoustics(ir)
            IrSlot.SUB   -> applySubFilter(ir)
            else -> ir
        }

        val peak = processed.maxOf { kotlin.math.abs(it) }
        if (peak > 0f) for (i in processed.indices) processed[i] /= peak

        irData[slot] = processed
        irPartitions[slot] = partitionIr(processed)
        slotsLoaded.add(slot)
        Log.d(TAG, "IR carregado: $slot | $len samples")
        resetOverlap(slot)
    }

    fun unloadIr(slot: IrSlot) {
        irData.remove(slot)
        irPartitions.remove(slot)
        slotsLoaded.remove(slot)
        resetOverlap(slot)
        Log.d(TAG, "IR removido: $slot")
    }

    fun isSlotLoaded(slot: IrSlot) = slot in slotsLoaded
    fun hasPrincipalIrs() = IrSlot.LEFT in slotsLoaded && IrSlot.RIGHT in slotsLoaded

    // ========== PROCESSAMENTO PSICOACUSTICO ==========

    private fun applyFrontPsychoacoustics(ir: FloatArray): FloatArray {
        val fadeInSamples = (0.002 * sampleRate).toInt()
        val out = ir.copyOf()
        for (i in 0 until min(fadeInSamples, out.size)) out[i] *= (i.toFloat() / fadeInSamples)
        return applyHannWindow(out)
    }

    private fun applyTopPsychoacoustics(ir: FloatArray): FloatArray {
        var out = applyFrontPsychoacoustics(ir)
        val alpha = kotlin.math.exp(-2.0 * PI * 8000.0 / sampleRate)
        val boostGain = 1.6f
        var prev = 0.0
        for (i in out.indices) {
            val x = out[i].toDouble()
            val highFreq = x - alpha * prev
            prev = x
            out[i] = (x + highFreq * (boostGain - 1.0)).toFloat()
        }
        val notchDelay = (sampleRate / 10000.0).toInt().coerceAtLeast(1)
        val notched = out.copyOf()
        for (i in notchDelay until out.size) notched[i] = out[i] * 0.5f - out[i - notchDelay] * 0.5f
        return notched
    }

    private fun applyBackPsychoacoustics(ir: FloatArray): FloatArray {
        val fadeInSamples = (0.003 * sampleRate).toInt()
        val out = ir.copyOf()
        for (i in 0 until min(fadeInSamples, out.size)) out[i] *= (i.toFloat() / fadeInSamples)
        return applyHannWindow(out)
    }

    private fun applySubFilter(ir: FloatArray): FloatArray {
        val out = ir.copyOf()
        var lpf = 0.0
        val rc = 1.0 / (2.0 * PI * 120.0)
        val dt = 1.0 / sampleRate
        val alpha = dt / (rc + dt)
        for (i in out.indices) { lpf += alpha * (out[i] - lpf); out[i] = lpf.toFloat() }
        return out
    }

    private fun applyHannWindow(ir: FloatArray): FloatArray {
        val out = ir.copyOf()
        val fadeStart = (out.size * 0.7).toInt()
        val fadeLen = out.size - fadeStart
        for (i in fadeStart until out.size) {
            val t = (i - fadeStart).toFloat() / fadeLen
            out[i] *= (0.5f * (1f + cos(PI * t))).toFloat()
        }
        return out
    }

    // ========== PARTICIONAMENTO ==========

    /**
     * Cada particao: HOP_SIZE amostras reais → zero-pad para FFT_SIZE pontos
     * → FFT complexa → array de FFT_SIZE*2 floats [Re0,Im0,Re1,Im1,...]
     */
    private fun partitionIr(ir: FloatArray): Array<FloatArray> {
        val numPartitions = (ir.size + HOP_SIZE - 1) / HOP_SIZE
        return Array(numPartitions) { p ->
            val start = p * HOP_SIZE
            val end   = min(start + HOP_SIZE, ir.size)
            // Array intercalado [Re,Im]: tamanho = FFT_SIZE * 2
            val partition = FloatArray(FFT_SIZE * 2)
            for (i in start until end) {
                partition[(i - start) * 2] = ir[i]  // Re; Im ja e 0
            }
            fftComplex(partition, inverse = false)
            partition  // retorna FloatArray, nao Unit
        }
    }

    // ========== PROCESSAMENTO PRINCIPAL ==========

    fun process(buffer: ShortArray) {
        if (!enabled || !hasPrincipalIrs()) return
        val frameCount = buffer.size / 2

        for (frame in 0 until frameCount) {
            val idxL = frame * 2
            val idxR = frame * 2 + 1

            val sampleL   = buffer[idxL].toFloat() / 32768f
            val sampleR   = buffer[idxR].toFloat() / 32768f
            val sampleMid = (sampleL + sampleR) * 0.5f

            inputBufferL[inputPos]   = sampleL
            inputBufferR[inputPos]   = sampleR
            inputBufferMid[inputPos] = sampleMid
            inputPos++

            if (inputPos >= HOP_SIZE) {
                processBlock()
                inputPos = 0
            }

            if (outAvailable > 0) {
                buffer[idxL] = (outQueueL[outReadPos].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                buffer[idxR] = (outQueueR[outReadPos].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                outReadPos = (outReadPos + 1) % OUTPUT_QUEUE
                outAvailable--
            }
        }
    }

    private fun processBlock() {
        // Monta blocos intercalados [Re,Im=0,...] para FFT
        val blockL   = FloatArray(FFT_SIZE * 2)
        val blockR   = FloatArray(FFT_SIZE * 2)
        val blockMid = FloatArray(FFT_SIZE * 2)
        for (i in 0 until HOP_SIZE) {
            blockL[i * 2]   = inputBufferL[i]
            blockR[i * 2]   = inputBufferR[i]
            blockMid[i * 2] = inputBufferMid[i]
        }
        fftComplex(blockL,   inverse = false)
        fftComplex(blockR,   inverse = false)
        fftComplex(blockMid, inverse = false)

        val resultL = FloatArray(FFT_SIZE * 2)
        val resultR = FloatArray(FFT_SIZE * 2)

        // Convolucao principal
        irPartitions[IrSlot.LEFT]?.get(0)?.let  { complexMultiplyAdd(blockL, it, resultL) }
        irPartitions[IrSlot.RIGHT]?.get(0)?.let { complexMultiplyAdd(blockR, it, resultR) }

        // Funcao auxiliar: convolve blockMid com IR do slot e retorna amostras reais
        fun convolveReflection(slot: IrSlot): FloatArray? {
            if (slot !in slotsLoaded) return null
            val reflF = FloatArray(FFT_SIZE * 2)
            irPartitions[slot]?.get(0)?.let { complexMultiplyAdd(blockMid, it, reflF) }
            val refl = FloatArray(HOP_SIZE)
            ifftComplex(reflF, refl)
            return refl
        }

        convolveReflection(IrSlot.FRONT)?.let { refl ->
            applyAsymmetricDelay(refl, resultL, resultR, DELAY_FRONT_L, DELAY_FRONT_R, GAIN_FRONT, invertR = false)
        }

        convolveReflection(IrSlot.TOP)?.let { refl ->
            val decorr = applyAllpassDecorrelation(refl)
            for (i in 0 until HOP_SIZE) {
                resultL[i * 2] += refl[i] * GAIN_TOP
                resultR[i * 2] += decorr[i] * GAIN_TOP
            }
        }

        convolveReflection(IrSlot.BACK)?.let { refl ->
            applyAsymmetricDelay(refl, resultL, resultR, DELAY_BACK_L, DELAY_BACK_R, GAIN_BACK, invertR = true)
        }

        convolveReflection(IrSlot.SUB)?.let { refl ->
            for (i in 0 until HOP_SIZE) {
                subLpfL += subLpfCoeff * (refl[i] - subLpfL)
                subLpfR += subLpfCoeff * (refl[i] - subLpfR)
                resultL[i * 2] += subLpfL.toFloat() * GAIN_SUB
                resultR[i * 2] += subLpfR.toFloat() * GAIN_SUB
            }
        }

        // IFFT do resultado principal
        val outL = FloatArray(HOP_SIZE)
        val outR = FloatArray(HOP_SIZE)
        ifftComplex(resultL, outL)
        ifftComplex(resultR, outR)

        // Overlap-Add
        val finalL = FloatArray(HOP_SIZE)
        val finalR = FloatArray(HOP_SIZE)
        for (i in 0 until HOP_SIZE) {
            finalL[i] = outL[i] + overlapL[i]
            finalR[i] = outR[i] + overlapR[i]
        }
        overlapL.fill(0f)
        overlapR.fill(0f)

        // Escreve na fila circular
        for (i in 0 until HOP_SIZE) {
            outQueueL[outWritePos] = finalL[i]
            outQueueR[outWritePos] = finalR[i]
            outWritePos = (outWritePos + 1) % OUTPUT_QUEUE
            outAvailable++
        }
    }

    // ========== DECORRELACAO ==========

    private fun applyAsymmetricDelay(
        refl: FloatArray, outL: FloatArray, outR: FloatArray,
        delayL: Int, delayR: Int, gain: Float, invertR: Boolean
    ) {
        for (i in refl.indices) {
            val srcL = if (i >= delayL) refl[i - delayL] else 0f
            val srcR = if (i >= delayR) refl[i - delayR] else 0f
            outL[i * 2] += srcL * gain
            outR[i * 2] += srcR * gain * (if (invertR) -1f else 1f)
        }
    }

    private fun applyAllpassDecorrelation(input: FloatArray): FloatArray {
        val g = 0.7
        val out = FloatArray(input.size)
        var xPrev = 0.0; var yPrev = 0.0
        for (i in input.indices) {
            val x = input[i].toDouble()
            val y = -g * x + xPrev + g * yPrev
            out[i] = y.toFloat()
            xPrev = x; yPrev = y
        }
        return out
    }

    // ========== FFT COMPLEXA (Cooley-Tukey radix-2, in-place) ==========
    // data: array de floats intercalados [Re0, Im0, Re1, Im1, ...]
    // tamanho do array = N * 2, onde N = numero de pontos complexos (deve ser potencia de 2)

    private fun fftComplex(data: FloatArray, inverse: Boolean) {
        val n = data.size / 2  // numero de pontos complexos

        // Bit-reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = data[i * 2];     data[i * 2]     = data[j * 2];     data[j * 2]     = tmp
                    tmp = data[i * 2 + 1]; data[i * 2 + 1] = data[j * 2 + 1]; data[j * 2 + 1] = tmp
            }
        }

        // Butterfly
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) -1 else 1)
            val wRe = cos(ang)
            val wIm = sin(ang)
            var pos = 0
            while (pos < n) {
                var curRe = 1.0; var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = data[(pos + k) * 2].toDouble()
                    val uIm = data[(pos + k) * 2 + 1].toDouble()
                    val vRe = data[(pos + k + len / 2) * 2].toDouble()
                    val vIm = data[(pos + k + len / 2) * 2 + 1].toDouble()

                    val tRe = curRe * vRe - curIm * vIm
                    val tIm = curRe * vIm + curIm * vRe

                    data[(pos + k) * 2]             = (uRe + tRe).toFloat()
                    data[(pos + k) * 2 + 1]         = (uIm + tIm).toFloat()
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

    // IFFT -> extrai so a parte real para out[]
    private fun ifftComplex(freq: FloatArray, out: FloatArray) {
        val tmp = freq.copyOf()
        fftComplex(tmp, inverse = true)
        val count = min(out.size, tmp.size / 2)
        for (i in 0 until count) out[i] = tmp[i * 2]
    }

    // Multiplicacao complexa correta: (aRe + aIm*j) * (bRe + bIm*j)
    private fun complexMultiplyAdd(a: FloatArray, b: FloatArray, out: FloatArray) {
        val n = a.size / 2
        for (i in 0 until n) {
            val aRe = a[i * 2].toDouble();     val aIm = a[i * 2 + 1].toDouble()
            val bRe = b[i * 2].toDouble();     val bIm = b[i * 2 + 1].toDouble()
            out[i * 2]     += (aRe * bRe - aIm * bIm).toFloat()
            out[i * 2 + 1] += (aRe * bIm + aIm * bRe).toFloat()
        }
    }

    // ========== UTILITARIOS ==========

    private fun resetOverlap(slot: IrSlot) {
        when (slot) {
            IrSlot.LEFT, IrSlot.RIGHT -> { overlapL.fill(0f); overlapR.fill(0f) }
            IrSlot.FRONT -> overlapFront.fill(0f)
            IrSlot.TOP   -> overlapTop.fill(0f)
            IrSlot.BACK  -> overlapBack.fill(0f)
            IrSlot.SUB   -> overlapSub.fill(0f)
        }
        outQueueL.fill(0f); outQueueR.fill(0f)
        outWritePos = 0; outReadPos = 0; outAvailable = 0
        inputPos = 0
        inputBufferL.fill(0f); inputBufferR.fill(0f); inputBufferMid.fill(0f)
    }

    fun reset() {
        overlapL.fill(0f); overlapR.fill(0f)
        overlapFront.fill(0f); overlapTop.fill(0f)
        overlapBack.fill(0f); overlapSub.fill(0f)
        outQueueL.fill(0f); outQueueR.fill(0f)
        outWritePos = 0; outReadPos = 0; outAvailable = 0
        inputPos = 0
        inputBufferL.fill(0f); inputBufferR.fill(0f); inputBufferMid.fill(0f)
        subLpfL = 0.0; subLpfR = 0.0
        allpassStateL = 0.0; allpassStateR = 0.0
        delayLineL.fill(0f); delayLineR.fill(0f); delayIdx = 0
    }
}
