package com.sonicsphere.audio.service

import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
 * Algoritmo: Overlap-Add com FFT de tamanho fixo (potência de 2).
 */
class ConvolutionEngine(private val sampleRate: Int) {

    companion object {
        private const val TAG = "ConvolutionEngine"
        private const val MAX_IR_SAMPLES = 44100       // 1 segundo @ 44.1kHz
        private const val FFT_SIZE = 4096              // bloco de processamento
        private const val HOP_SIZE = FFT_SIZE / 2      // 50% overlap

        // Ganhos padrão das reflexões (linear)
        private const val GAIN_FRONT = 0.20f   // -14 dB
        private const val GAIN_TOP   = 0.16f   // -16 dB
        private const val GAIN_BACK  = 0.20f   // -14 dB
        private const val GAIN_SUB   = 0.13f   // -18 dB

        // Delays assimétricos de decorrelação (em samples @ 44100Hz)
        private const val DELAY_FRONT_L = 13   // ~0.3ms
        private const val DELAY_FRONT_R = 31   // ~0.7ms
        private const val DELAY_BACK_L  = 66   // ~1.5ms
        private const val DELAY_BACK_R  = 0
    }

    enum class IrSlot {
        LEFT,   // Principal esquerdo
        RIGHT,  // Principal direito
        FRONT,  // Reflexão frente
        TOP,    // Reflexão cima
        BACK,   // Reflexão atrás
        SUB     // Reflexão sub-graves
    }

    // IRs carregados por slot (FloatArray mono, já normalizado)
    private val irData = mutableMapOf<IrSlot, FloatArray>()

    // Partições da IR no domínio da frequência (overlap-add particionado)
    private val irPartitions = mutableMapOf<IrSlot, Array<FloatArray>>()

    // Buffers de overlap por canal
    private val overlapL = FloatArray(FFT_SIZE)
    private val overlapR = FloatArray(FFT_SIZE)
    private val overlapFront = FloatArray(FFT_SIZE)
    private val overlapTop   = FloatArray(FFT_SIZE)
    private val overlapBack  = FloatArray(FFT_SIZE)
    private val overlapSub   = FloatArray(FFT_SIZE)

    // Buffer de entrada acumulado
    private val inputBufferL = FloatArray(FFT_SIZE)
    private val inputBufferR = FloatArray(FFT_SIZE)
    private val inputBufferMid = FloatArray(FFT_SIZE)
    private var inputPos = 0

    // Buffers de saída
    private val outputL = FloatArray(FFT_SIZE * 2)
    private val outputR = FloatArray(FFT_SIZE * 2)
    private var outputPos = 0
    private var outputAvailable = 0

    // Delay lines para decorrelação
    private val delayLineL = FloatArray(128)
    private val delayLineR = FloatArray(128)
    private var delayIdx = 0

    // Allpass para decorrelação de fase (frente e cima)
    private var allpassStateL = 0.0
    private var allpassStateR = 0.0

    // Filtro passa-baixa para Sub (~120Hz)
    private var subLpfL = 0.0
    private var subLpfR = 0.0
    private val subLpfCoeff: Double

    @Volatile var enabled = false
    private var slotsLoaded = mutableSetOf<IrSlot>()

    init {
        // Coeficiente LPF para ~120Hz
        val rc = 1.0 / (2.0 * PI * 120.0)
        val dt = 1.0 / sampleRate
        subLpfCoeff = dt / (rc + dt)
    }

    // ========== CARREGAMENTO DE IR ==========

    /**
     * Carrega um IR estéreo WAV no slot especificado.
     * Para slots de reflexão, usa o canal L como referência mono.
     * Para slots principais, usa L para LEFT e R para RIGHT.
     */
    fun loadIr(slot: IrSlot, leftChannel: FloatArray, rightChannel: FloatArray) {
        val len = min(leftChannel.size, MAX_IR_SAMPLES)

        val ir = when (slot) {
            IrSlot.LEFT  -> leftChannel.copyOf(len)
            IrSlot.RIGHT -> rightChannel.copyOf(len)
            else -> {
                // Mid sum dos dois canais para reflexões
                FloatArray(len) { i ->
                    (leftChannel[i] + rightChannel[i]) * 0.5f
                }
            }
        }

        // Aplica processamento psicoacústico específico por slot antes de particionar
        val processed = when (slot) {
            IrSlot.FRONT -> applyFrontPsychoacoustics(ir)
            IrSlot.TOP   -> applyTopPsychoacoustics(ir)
            IrSlot.BACK  -> applyBackPsychoacoustics(ir)
            IrSlot.SUB   -> applySubFilter(ir)
            else -> ir
        }

        // Normaliza
        val peak = processed.maxOf { kotlin.math.abs(it) }
        if (peak > 0f) {
            for (i in processed.indices) processed[i] /= peak
        }

        irData[slot] = processed
        irPartitions[slot] = partitionIr(processed)
        slotsLoaded.add(slot)

        Log.d(TAG, "✅ IR carregado: $slot | ${len} samples")
        resetOverlap(slot)
    }

    fun unloadIr(slot: IrSlot) {
        irData.remove(slot)
        irPartitions.remove(slot)
        slotsLoaded.remove(slot)
        resetOverlap(slot)
        Log.d(TAG, "🗑️ IR removido: $slot")
    }

    fun isSlotLoaded(slot: IrSlot) = slot in slotsLoaded

    fun hasPrincipalIrs() = IrSlot.LEFT in slotsLoaded && IrSlot.RIGHT in slotsLoaded

    // ========== PROCESSAMENTO PSICOACÚSTICO POR SLOT ==========

    /**
     * FRENTE: fade-in de 2ms para suprimir onset direto + janelamento suave.
     * Preserva timbre frontal sem competir com o sinal direto.
     */
    private fun applyFrontPsychoacoustics(ir: FloatArray): FloatArray {
        val fadeInSamples = (0.002 * sampleRate).toInt()
        val out = ir.copyOf()
        for (i in 0 until min(fadeInSamples, out.size)) {
            out[i] *= (i.toFloat() / fadeInSamples)
        }
        return applyHannWindow(out, fadeOut = true)
    }

    /**
     * CIMA: coloração espectral de elevação (boost ~8kHz, notch ~10kHz)
     * + fade-in + rotação de fase 90° num canal via delay de quarto de período.
     * O cérebro associa essa coloração com fontes acima.
     */
    private fun applyTopPsychoacoustics(ir: FloatArray): FloatArray {
        var out = applyFrontPsychoacoustics(ir) // fade-in primeiro

        // Boost em ~8kHz via filtro peaking simplificado (shelving de alta)
        val alpha = exp(-2.0 * PI * 8000.0 / sampleRate)
        val boostGain = 1.6f // ~+4dB
        var prev = 0.0
        for (i in out.indices) {
            val x = out[i].toDouble()
            val highFreq = x - alpha * prev
            prev = x
            out[i] = (x + highFreq * (boostGain - 1.0)).toFloat()
        }

        // Notch em ~10kHz via comb filter de 1 sample (simplificado)
        val notchDelay = (sampleRate / 10000.0).toInt().coerceAtLeast(1)
        val notched = out.copyOf()
        for (i in notchDelay until out.size) {
            notched[i] = out[i] * 0.5f - out[i - notchDelay] * 0.5f
        }

        return notched
    }

    /**
     * ATRÁS: fade-in + inversão de fase num canal + delay ~1.5ms no canal L.
     * O cérebro interpreta a combinação como fonte posterior.
     */
    private fun applyBackPsychoacoustics(ir: FloatArray): FloatArray {
        // A inversão de fase é aplicada durante o mix (não aqui)
        // Aqui só fazemos o fade-in com janela mais longa
        val fadeInSamples = (0.003 * sampleRate).toInt()
        val out = ir.copyOf()
        for (i in 0 until min(fadeInSamples, out.size)) {
            out[i] *= (i.toFloat() / fadeInSamples)
        }
        return applyHannWindow(out, fadeOut = true)
    }

    /**
     * SUB: passa-baixo agressivo (~120Hz). Sub não tem localização direcional,
     * então é mono puro sem decorrelação — só adiciona peso e profundidade.
     */
    private fun applySubFilter(ir: FloatArray): FloatArray {
        val out = ir.copyOf()
        var lpf = 0.0
        val rc = 1.0 / (2.0 * PI * 120.0)
        val dt = 1.0 / sampleRate
        val alpha = dt / (rc + dt)
        for (i in out.indices) {
            lpf += alpha * (out[i] - lpf)
            out[i] = lpf.toFloat()
        }
        return out
    }

    private fun applyHannWindow(ir: FloatArray, fadeOut: Boolean): FloatArray {
        if (!fadeOut) return ir
        val out = ir.copyOf()
        val fadeStart = (out.size * 0.7).toInt()
        val fadeLen = out.size - fadeStart
        for (i in fadeStart until out.size) {
            val t = (i - fadeStart).toFloat() / fadeLen
            out[i] *= (0.5f * (1f + cos(PI * t))).toFloat()
        }
        return out
    }

    // ========== PARTICIONAMENTO DA IR (Overlap-Add) ==========

    private fun partitionIr(ir: FloatArray): Array<FloatArray> {
        val numPartitions = (ir.size + HOP_SIZE - 1) / HOP_SIZE
        return Array(numPartitions) { p ->
            val start = p * HOP_SIZE
            val end = min(start + HOP_SIZE, ir.size)
            val partition = FloatArray(FFT_SIZE)
            for (i in start until end) {
                partition[i - start] = ir[i]
            }
            // FFT da partição (armazenada como real/imag intercalado)
            fftReal(partition)
            partition
        }
    }

    // ========== PROCESSAMENTO PRINCIPAL ==========

    /**
     * Processa buffer estéreo intercalado (L, R, L, R, ...) in-place.
     * Aplica convolução principal + reflexões misturadas.
     */
    fun process(buffer: ShortArray) {
        if (!enabled || !hasPrincipalIrs()) return

        val frameCount = buffer.size / 2

        for (frame in 0 until frameCount) {
            val idxL = frame * 2
            val idxR = frame * 2 + 1

            val sampleL = buffer[idxL].toFloat() / 32768f
            val sampleR = buffer[idxR].toFloat() / 32768f
            val sampleMid = (sampleL + sampleR) * 0.5f

            inputBufferL[inputPos] = sampleL
            inputBufferR[inputPos] = sampleR
            inputBufferMid[inputPos] = sampleMid
            inputPos++

            if (inputPos >= HOP_SIZE) {
                processBlock()
                inputPos = 0
            }

            // Saída
            if (outputAvailable > 0) {
                val outL = outputL[outputPos].coerceIn(-1f, 1f)
                val outR = outputR[outputPos].coerceIn(-1f, 1f)
                buffer[idxL] = (outL * 32767f).toInt().toShort()
                buffer[idxR] = (outR * 32767f).toInt().toShort()
                outputPos++
                outputAvailable--
            }
        }
    }

    private fun processBlock() {
        // Prepara blocos de entrada com zero-padding
        val blockL   = FloatArray(FFT_SIZE).also { inputBufferL.copyInto(it, 0, 0, HOP_SIZE) }
        val blockR   = FloatArray(FFT_SIZE).also { inputBufferR.copyInto(it, 0, 0, HOP_SIZE) }
        val blockMid = FloatArray(FFT_SIZE).also { inputBufferMid.copyInto(it, 0, 0, HOP_SIZE) }

        // FFT dos blocos de entrada
        fftReal(blockL)
        fftReal(blockR)
        fftReal(blockMid)

        // Resultado acumulado
        val resultL = FloatArray(FFT_SIZE)
        val resultR = FloatArray(FFT_SIZE)

        // --- Convolução principal L e R ---
        irPartitions[IrSlot.LEFT]?.get(0)?.let { irF ->
            complexMultiplyAdd(blockL, irF, resultL)
        }
        irPartitions[IrSlot.RIGHT]?.get(0)?.let { irF ->
            complexMultiplyAdd(blockR, irF, resultR)
        }

        // --- Reflexões ---
        if (IrSlot.FRONT in slotsLoaded) {
            val reflF = FloatArray(FFT_SIZE)
            irPartitions[IrSlot.FRONT]?.get(0)?.let { irF ->
                complexMultiplyAdd(blockMid, irF, reflF)
            }
            val refl = FloatArray(FFT_SIZE)
            ifftReal(reflF, refl)

            // Decorrelação: delay assimétrico L/R
            applyAsymmetricDelay(refl, resultL, resultR,
                DELAY_FRONT_L, DELAY_FRONT_R, GAIN_FRONT, invertR = false)
        }

        if (IrSlot.TOP in slotsLoaded) {
            val reflF = FloatArray(FFT_SIZE)
            irPartitions[IrSlot.TOP]?.get(0)?.let { irF ->
                complexMultiplyAdd(blockMid, irF, reflF)
            }
            val refl = FloatArray(FFT_SIZE)
            ifftReal(reflF, refl)

            // Decorrelação: allpass de fase diferente por canal
            val reflDecorr = applyAllpassDecorrelation(refl)
            for (i in refl.indices) {
                resultL[i] += refl[i] * GAIN_TOP
                resultR[i] += reflDecorr[i] * GAIN_TOP
            }
        }

        if (IrSlot.BACK in slotsLoaded) {
            val reflF = FloatArray(FFT_SIZE)
            irPartitions[IrSlot.BACK]?.get(0)?.let { irF ->
                complexMultiplyAdd(blockMid, irF, reflF)
            }
            val refl = FloatArray(FFT_SIZE)
            ifftReal(reflF, refl)

            // Decorrelação: delay L + inversão de fase R
            applyAsymmetricDelay(refl, resultL, resultR,
                DELAY_BACK_L, DELAY_BACK_R, GAIN_BACK, invertR = true)
        }

        if (IrSlot.SUB in slotsLoaded) {
            val reflF = FloatArray(FFT_SIZE)
            irPartitions[IrSlot.SUB]?.get(0)?.let { irF ->
                complexMultiplyAdd(blockMid, irF, reflF)
            }
            val refl = FloatArray(FFT_SIZE)
            ifftReal(reflF, refl)

            // Sub: passa-baixo + mono
            for (i in refl.indices) {
                subLpfL += subLpfCoeff * (refl[i] - subLpfL)
                subLpfR += subLpfCoeff * (refl[i] - subLpfR)
                resultL[i] += subLpfL.toFloat() * GAIN_SUB
                resultR[i] += subLpfR.toFloat() * GAIN_SUB
            }
        }

        // IFFT do resultado principal
        val outL = FloatArray(FFT_SIZE)
        val outR = FloatArray(FFT_SIZE)
        ifftReal(resultL, outL)
        ifftReal(resultR, outR)

        // Overlap-add
        for (i in 0 until HOP_SIZE) {
            outputL[i] = outL[i] + overlapL[i]
            outputR[i] = outR[i] + overlapR[i]
        }
        for (i in HOP_SIZE until FFT_SIZE) {
            overlapL[i - HOP_SIZE] = outL[i]
            overlapR[i - HOP_SIZE] = outR[i]
        }

        outputPos = 0
        outputAvailable = HOP_SIZE
    }

    // ========== DECORRELAÇÃO ==========

    private fun applyAsymmetricDelay(
        refl: FloatArray,
        outL: FloatArray, outR: FloatArray,
        delayL: Int, delayR: Int,
        gain: Float, invertR: Boolean
    ) {
        for (i in refl.indices) {
            val srcL = if (i >= delayL) refl[i - delayL] else 0f
            val srcR = if (i >= delayR) refl[i - delayR] else 0f
            outL[i] += srcL * gain
            outR[i] += srcR * gain * (if (invertR) -1f else 1f)
        }
    }

    private fun applyAllpassDecorrelation(input: FloatArray): FloatArray {
        // Allpass de primeira ordem: y[n] = -g*x[n] + x[n-1] + g*y[n-1]
        val g = 0.7
        val out = FloatArray(input.size)
        var xPrev = 0.0
        var yPrev = 0.0
        for (i in input.indices) {
            val x = input[i].toDouble()
            val y = -g * x + xPrev + g * yPrev
            out[i] = y.toFloat()
            xPrev = x; yPrev = y
        }
        return out
    }

    // ========== FFT (Cooley-Tukey radix-2, in-place) ==========
    // Armazena como [Re0, Im0, Re1, Im1, ...] mas simplificado para real

    private fun fftReal(data: FloatArray) {
        val n = data.size
        // Bit-reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tmp = data[i]; data[i] = data[j]; data[j] = tmp }
        }
        // Butterfly
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val wReal = cos(-2.0 * PI / len)
            val wImag = sin(-2.0 * PI / len)
            var pos = 0
            while (pos < n) {
                var curReal = 1.0; var curImag = 0.0
                for (k in 0 until halfLen) {
                    val uReal = data[pos + k].toDouble()
                    val vReal = data[pos + k + halfLen] * curReal
                    data[pos + k] = (uReal + vReal).toFloat()
                    data[pos + k + halfLen] = (uReal - vReal).toFloat()
                    val newReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = newReal
                }
                pos += len
            }
            len = len shl 1
        }
    }

    private fun ifftReal(freq: FloatArray, out: FloatArray) {
        val data = freq.copyOf()
        // Conjuga (para IFFT de sinal real, simplesmente inverte)
        for (i in 1 until data.size / 2) {
            val tmp = data[i]; data[i] = data[data.size - i]; data[data.size - i] = tmp
        }
        fftReal(data)
        val scale = 1f / data.size
        for (i in data.indices) out[i] = data[i] * scale
    }

    private fun complexMultiplyAdd(a: FloatArray, b: FloatArray, out: FloatArray) {
        val half = a.size / 2
        for (i in 0 until half) {
            val j = a.size - 1 - i
            // Multiplicação complexa simplificada para espectro real
            out[i] += a[i] * b[i]
            if (j != i) out[j] += a[j] * b[j]
        }
    }

    // ========== UTILITÁRIOS ==========

    private fun exp(x: Double) = kotlin.math.exp(x)

    private fun resetOverlap(slot: IrSlot) {
        when (slot) {
            IrSlot.LEFT, IrSlot.RIGHT -> {
                overlapL.fill(0f); overlapR.fill(0f)
            }
            IrSlot.FRONT -> overlapFront.fill(0f)
            IrSlot.TOP   -> overlapTop.fill(0f)
            IrSlot.BACK  -> overlapBack.fill(0f)
            IrSlot.SUB   -> overlapSub.fill(0f)
        }
        outputL.fill(0f); outputR.fill(0f)
        outputPos = 0; outputAvailable = 0
        inputPos = 0
        inputBufferL.fill(0f); inputBufferR.fill(0f); inputBufferMid.fill(0f)
    }

    fun reset() {
        overlapL.fill(0f); overlapR.fill(0f)
        overlapFront.fill(0f); overlapTop.fill(0f)
        overlapBack.fill(0f); overlapSub.fill(0f)
        outputL.fill(0f); outputR.fill(0f)
        outputPos = 0; outputAvailable = 0
        inputPos = 0
        inputBufferL.fill(0f); inputBufferR.fill(0f); inputBufferMid.fill(0f)
        subLpfL = 0.0; subLpfR = 0.0
        allpassStateL = 0.0; allpassStateR = 0.0
        delayLineL.fill(0f); delayLineR.fill(0f); delayIdx = 0
    }
}
