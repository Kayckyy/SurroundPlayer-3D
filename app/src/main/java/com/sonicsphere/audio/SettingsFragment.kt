package com.sonicsphere.audio

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.sonicsphere.audio.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var isServiceReady = false
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSettings()
        startPeriodicUpdate()
    }

    override fun onResume() {
        super.onResume()
        val service = getMusicService()
        if (service != null && !isServiceReady) {
            onServiceReady()
        }
        updateUIFromService()
    }

    override fun onPause() {
        super.onPause()
        stopPeriodicUpdate()
    }

    private fun startPeriodicUpdate() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (binding.switchEqualizer.isChecked && binding.equalizerBandsContainer.childCount == 0) {
                    setupEqualizerBands()
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun stopPeriodicUpdate() {
        updateRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun onServiceReady() {
        isServiceReady = true
        updateUIFromService()
    }

    private fun setupSettings() {
        // Equalizer Switch
        binding.switchEqualizer.setOnCheckedChangeListener { _, isChecked ->
            getMusicService()?.setEqualizerEnabled(isChecked)
            updateEqualizerVisibility(isChecked)
            if (isChecked) {
                handler.postDelayed({
                    setupEqualizerBands()
                }, 500)
            }
        }

        // Bass Boost Switch
        binding.switchBassBoost.setOnCheckedChangeListener { _, isChecked ->
            getMusicService()?.setBassBoostEnabled(isChecked)
            updateBassBoostVisibility(isChecked)
        }

        // Bass Boost Strength SeekBar
        binding.seekBarBassBoost.max = 500
        binding.seekBarBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    getMusicService()?.setBassBoostStrength(progress.toShort())
                    binding.textBassBoostValue.text = "${progress / 10}%"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Haas Effect (3D Simples)
        binding.radioGroupHaas.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioHaasShort -> HaasEffect.HAAS_SHORT
                R.id.radioHaasMedium -> HaasEffect.HAAS_MEDIUM
                R.id.radioHaasLong -> HaasEffect.HAAS_LONG
                else -> HaasEffect.HAAS_OFF
            }
            getMusicService()?.setHaasMode(mode)
        }

        updateUIFromService()
    }

    private fun setupEqualizerBands() {
        val service = getMusicService() ?: return

        val numberOfBands = service.getEqualizerNumberOfBands()
        if (numberOfBands == null) {
            return
        }

        val bandLevelRange = service.getEqualizerBandLevelRange() ?: return

        binding.equalizerBandsContainer.removeAllViews()

        for (band in 0 until numberOfBands.toInt()) {
            val bandView = layoutInflater.inflate(R.layout.item_equalizer_band, binding.equalizerBandsContainer, false)

            val bandLabel = bandView.findViewById<android.widget.TextView>(R.id.bandLabel)
            val bandSeekBar = bandView.findViewById<SeekBar>(R.id.bandSeekBar)
            val bandValue = bandView.findViewById<android.widget.TextView>(R.id.bandValue)

            val centerFreq = service.getEqualizerCenterFreq(band.toShort()) ?: 0
            val freqText = if (centerFreq >= 1000) {
                "${centerFreq / 1000}kHz"
            } else {
                "${centerFreq}Hz"
            }
            bandLabel.text = freqText

            val minLevel = bandLevelRange[0].toInt()
            val maxLevel = bandLevelRange[1].toInt()
            bandSeekBar.max = maxLevel - minLevel

            val currentLevel = service.getEqualizerBandLevel(band.toShort())?.toInt() ?: 0
            bandSeekBar.progress = currentLevel - minLevel

            bandValue.text = "${currentLevel / 100}dB"

            bandSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val level = (progress + minLevel).toShort()
                        service.setEqualizerBandLevel(band.toShort(), level)
                        bandValue.text = "${level / 100}dB"
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            binding.equalizerBandsContainer.addView(bandView)
        }
    }

    private fun updateUIFromService() {
        val service = getMusicService() ?: return

        // Atualizar Equalizer
        val equalizerEnabled = service.isEqualizerEnabled()
        binding.switchEqualizer.isChecked = equalizerEnabled
        updateEqualizerVisibility(equalizerEnabled)

        if (equalizerEnabled && binding.equalizerBandsContainer.childCount == 0) {
            setupEqualizerBands()
        }

        // Atualizar Bass Boost
        val bassBoostEnabled = service.isBassBoostEnabled()
        binding.switchBassBoost.isChecked = bassBoostEnabled
        updateBassBoostVisibility(bassBoostEnabled)

        val bassStrength = service.getBassBoostStrength()?.toInt() ?: 0
        binding.seekBarBassBoost.progress = bassStrength
        binding.textBassBoostValue.text = "${bassStrength / 10}%"

        // Atualizar Haas Effect
        val haasMode = service.getHaasMode()
        when (haasMode) {
            HaasEffect.HAAS_SHORT -> binding.radioHaasShort.isChecked = true
            HaasEffect.HAAS_MEDIUM -> binding.radioHaasMedium.isChecked = true
            HaasEffect.HAAS_LONG -> binding.radioHaasLong.isChecked = true
            else -> binding.radioHaasOff.isChecked = true
        }
    }

    private fun updateEqualizerVisibility(visible: Boolean) {
        binding.equalizerBandsContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateBassBoostVisibility(visible: Boolean) {
        binding.bassBoostControlsContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun getMusicService(): MusicService? {
        return (requireActivity() as? MainActivity)?.getMusicService() ?: MusicService.getInstance()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPeriodicUpdate()
        _binding = null
    }
}