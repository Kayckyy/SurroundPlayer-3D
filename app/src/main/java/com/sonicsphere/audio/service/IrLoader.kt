package com.sonicsphere.audio.service

import android.util.Log
import kotlin.math.*

/**
 * Gera IRs binaurais sintéticas matematicamente.
 * Modelo: ITD + ILD + Head Shadow LPF no canal contralateral.
 * Sample rate: 48000Hz — float32 puro, sem quantização.
 */
object IrLoader {

    private const val TAG = "IrLoader"
    const val SAMPLE_RATE = 48000
    private const val IR_LENGTH = 512

    private const val HEAD_RADIUS_M = 0.0875
    private const val SOUND_SPEED   = 343.0
    private const val IR_GAIN = 0.35f

    // ========== API PÚBLICA ==========

    fun loadSynthetic(
        engine: ConvolutionEngine,
        azimuthDeg: Float = 90f
    ) {
        val (irL, irR) = generateHrtf(azimuthDeg)
        engine.loadIr(ConvolutionEngine.IrSlot.LEFT,  irL, irR)
        engine.loadIr(ConvolutionEngine.IrSlot.RIGHT, irR, irL)
        Log.d(TAG, "✅ IR sintética carregada | az=${azimuthDeg}°")
    }

    // ========== GERAÇÃO ==========

    private fun generateHrtf(azimuthDeg: Float): Pair<FloatArray, FloatArray> {
        val az = azimuthDeg * PI / 180.0

        // ITD — Woodworth
        val itdSamples = ((HEAD_RADIUS_M / SOUND_SPEED) *
            (az + sin(az)) * SAMPLE_RATE).toInt().coerceIn(0, 48)

        // ILD — atenuação do canal contralateral
        val ildGain = (1.0 - 0.45 * sin(az).absoluteValue).toFloat()
            .coerceIn(0.4f, 1.0f)

        val irL = FloatArray(IR_LENGTH)
        val irR = FloatArray(IR_LENGTH)

        // Canal direto esquerdo
        for (i in 0 until IR_LENGTH) {
            val hann = (0.5 * (1.0 - cos(2.0 * PI * i / (IR_LENGTH - 1)))).toFloat()
            irL[i] = if (i == 0) IR_GAIN else IR_GAIN * hann * exp(-i.toDouble() / (IR_LENGTH * 0.3)).toFloat()
        }

        // Canal contralateral direito — delay ITD + ILD
        val raw = FloatArray(IR_LENGTH)
        for (i in 0 until IR_LENGTH) {
            val hann = (0.5 * (1.0 - cos(2.0 * PI * i / (IR_LENGTH - 1)))).toFloat()
            raw[i] = IR_GAIN * ildGain * hann *
                exp(-i.toDouble() / (IR_LENGTH * 0.3)).toFloat()
        }

        // Aplica ITD
        for (i in 0 until IR_LENGTH) {
            val src = i - itdSamples
            irR[i] = if (src >= 0 && src < IR_LENGTH) raw[src] else 0f
        }

        // HPF 1-polo (~80Hz)
        val hpfCoeff = (2.0f * PI.toFloat() * 40f / SAMPLE_RATE.toFloat()).coerceIn(0f, 1f)
        var hpfStateL = 0f
        var hpfStateR = 0f
        for (i in irL.indices) {
            hpfStateL = hpfCoeff * (hpfStateL + irL[i] - (if (i > 0) irL[i-1] else 0f))
            irL[i] = hpfStateL
        }
        for (i in irR.indices) {
            hpfStateR = hpfCoeff * (hpfStateR + irR[i] - (if (i > 0) irR[i-1] else 0f))
            irR[i] = hpfStateR
        }

        // Head shadow — LPF no contralateral
        val lpfCoeff = 0.6f
        var lpfState = 0f
        for (i in irR.indices) {
            lpfState += lpfCoeff * (irR[i] - lpfState)
            irR[i] = lpfState
        }

        return Pair(irL, irR)
    }
}
