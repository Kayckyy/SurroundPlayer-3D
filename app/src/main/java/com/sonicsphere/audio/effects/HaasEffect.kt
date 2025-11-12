package com.sonicsphere.audio.effects

import android.media.audiofx.Virtualizer

class HaasEffect(audioSessionId: Int) {

    companion object {
        const val HAAS_OFF = 0
        const val HAAS_SHORT = 500    // 500ms delay
        const val HAAS_MEDIUM = 800   // 800ms delay
        const val HAAS_LONG = 1000     // 1000ms delay
    }

    private var virtualizer: Virtualizer? = null
    private var currentMode = HAAS_OFF

    init {
        try {
            virtualizer = Virtualizer(0, audioSessionId).apply {
                enabled = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setHaasMode(mode: Int) {
        currentMode = mode
        try {
            when (mode) {
                HAAS_OFF -> {
                    virtualizer?.enabled = false
                }
                HAAS_SHORT -> {
                    virtualizer?.enabled = true
                    virtualizer?.setStrength(1000)
                }
                HAAS_MEDIUM -> {
                    virtualizer?.enabled = true
                    virtualizer?.setStrength(1000)
                }
                HAAS_LONG -> {
                    virtualizer?.enabled = true
                    virtualizer?.setStrength(1000)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCurrentMode(): Int = currentMode

    fun isEnabled(): Boolean = virtualizer?.enabled ?: false

    fun release() {
        try {
            virtualizer?.release()
            virtualizer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}