package com.sonicsphere.audio.service

import android.util.Log

class OboeAudioEngine {

    companion object {
        private const val TAG = "OboeAudioEngine"

        init {
            try {
                System.loadLibrary("sonicsphere")
                Log.d(TAG, "✅ Biblioteca nativa carregada")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ Erro ao carregar biblioteca", e)
            }
        }
    }

    private var isInitialized = false
    private var isRunning = false

    init {
        nativeCreate()
        isInitialized = true
        Log.d(TAG, "Engine inicializado")
    }

    fun start(): Boolean {
        if (!isInitialized || isRunning) return isRunning

        val result = nativeStart()
        if (result) {
            isRunning = true
            Log.d(TAG, "✅ Stream iniciado")
        }
        return result
    }

    fun stop() {
        if (!isRunning) return
        nativeStop()
        isRunning = false
        Log.d(TAG, "Stream parado")
    }

    fun loadAudio(pcmData: FloatArray, sampleRate: Int, channelCount: Int): Boolean {
        return nativeLoadAudio(pcmData, sampleRate, channelCount)
    }

    fun play() {
        nativePlay()
    }

    fun pause() {
        nativePause()
    }

    fun setHaasDelay(delayMs: Int) {
        nativeSetHaasDelay(delayMs)
        Log.d(TAG, "Haas: ${delayMs}ms")
    }

    fun enableHaas(enabled: Boolean) {
        nativeEnableHaas(enabled)
    }

    fun destroy() {
        if (isRunning) stop()
        nativeDestroy()
        isInitialized = false
        Log.d(TAG, "Engine destruído")
    }

    // JNI
    private external fun nativeCreate()
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeLoadAudio(pcmData: FloatArray, sampleRate: Int, channelCount: Int): Boolean
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeSetHaasDelay(delayMs: Int)
    private external fun nativeEnableHaas(enabled: Boolean)
    private external fun nativeDestroy()
}