package com.sonicsphere.audio.fragments

import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.sonicsphere.audio.databinding.FragmentSettingsBinding
import com.sonicsphere.audio.service.MusicService

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupEqualizer()
        setupBassBoost()
        setupBinaural()
        setupHaas()
        startPeriodicUpdate()
    }

    override fun onResume() {
        super.onResume()
        syncAllFromService()
    }

    override fun onPause() {
        super.onPause()
        stopPeriodicUpdate()
    }

    // ========== EQUALIZER ==========

    private fun setupEqualizer() {
        binding.switchEqualizer.setOnCheckedChangeListener { _, isChecked ->
            getMusicService()?.setEqualizerEnabled(isChecked)
            binding.equalizerBandsContainer.visibility =
                if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) handler.postDelayed({ setupEqualizerBands() }, 300)
        }
    }

    private fun setupEqualizerBands() {
        val service = getMusicService() ?: return
        val bands = service.getEqualizerNumberOfBands() ?: return
        val range = service.getEqualizerBandLevelRange() ?: return
        binding.equalizerBandsContainer.removeAllViews()

        for (band in 0 until bands.toInt()) {
            val v = layoutInflater.inflate(
                com.sonicsphere.audio.R.layout.item_equalizer_band,
                binding.equalizerBandsContainer, false)
            val label   = v.findViewById<android.widget.TextView>(com.sonicsphere.audio.R.id.bandLabel)
            val seekBar = v.findViewById<SeekBar>(com.sonicsphere.audio.R.id.bandSeekBar)
            val value   = v.findViewById<android.widget.TextView>(com.sonicsphere.audio.R.id.bandValue)

            val freq = service.getEqualizerCenterFreq(band.toShort()) ?: 0
            label.text = if (freq >= 1000) "${freq/1000}kHz" else "${freq}Hz"

            val min = range[0].toInt(); val max = range[1].toInt()
            seekBar.max = max - min
            val cur = service.getEqualizerBandLevel(band.toShort())?.toInt() ?: 0
            seekBar.progress = cur - min
            value.text = "${cur / 100}dB"

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val level = (p + min).toShort()
                    service.setEqualizerBandLevel(band.toShort(), level)
                    value.text = "${level / 100}dB"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            binding.equalizerBandsContainer.addView(v)
        }
    }

    // ========== BASS BOOST ==========

    private fun setupBassBoost() {
        binding.switchBassBoost.setOnCheckedChangeListener { _, isChecked ->
            getMusicService()?.setBassBoostEnabled(isChecked)
            binding.bassBoostControlsContainer.visibility =
                if (isChecked) View.VISIBLE else View.GONE
        }
        binding.seekBarBassBoost.max = 500
        binding.seekBarBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                getMusicService()?.setBassBoostStrength(p.toShort())
                binding.textBassBoostValue.text = "${p / 10}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ========== BINAURAL ==========

    private fun setupBinaural() {
        binding.switchBinaural.setOnCheckedChangeListener { _, isChecked ->
    val service = getMusicService()
    if (service != null) {
        service.setBinauralEnabled(isChecked)
    } else {
        Toast.makeText(requireContext(), "Inicie uma música primeiro", Toast.LENGTH_SHORT).show()
        binding.switchBinaural.isChecked = !isChecked
    }
        }
    }

    // ========== HAAS ==========

    private fun setupHaas() {
        binding.radioGroupHaas.setOnCheckedChangeListener { _, id ->
            val ms = when (id) {
                binding.radioHaasShort.id  -> 10
                binding.radioHaasMedium.id -> 30
                binding.radioHaasLong.id   -> 50
                else -> 0
            }
            getMusicService()?.setHaasDelay(ms)
        }
    }

    // ========== SYNC COM SERVICE ==========

    private fun syncAllFromService() {
        val s = getMusicService() ?: return

        // Equalizer
        binding.switchEqualizer.isChecked = s.isEqualizerEnabled()
        binding.equalizerBandsContainer.visibility =
            if (s.isEqualizerEnabled()) View.VISIBLE else View.GONE
        if (s.isEqualizerEnabled() && binding.equalizerBandsContainer.childCount == 0)
            setupEqualizerBands()

        // Bass Boost
        binding.switchBassBoost.isChecked = s.isBassBoostEnabled()
        binding.bassBoostControlsContainer.visibility =
            if (s.isBassBoostEnabled()) View.VISIBLE else View.GONE
        val strength = s.getBassBoostStrength()?.toInt() ?: 0
        binding.seekBarBassBoost.progress = strength
        binding.textBassBoostValue.text = "${strength / 10}%"

        // Binaural
        binding.switchBinaural.isChecked = s.isBinauralEnabled()

        // Haas
        when (s.getHaasDelay()) {
            10   -> binding.radioHaasShort.isChecked  = true
            30   -> binding.radioHaasMedium.isChecked = true
            50   -> binding.radioHaasLong.isChecked   = true
            else -> binding.radioHaasOff.isChecked    = true
        }
    }

    // ========== UTILITÁRIOS ==========

    private fun startPeriodicUpdate() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (binding.switchEqualizer.isChecked &&
                    binding.equalizerBandsContainer.childCount == 0) setupEqualizerBands()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun stopPeriodicUpdate() {
        updateRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun getMusicService(): MusicService? =
        (requireActivity() as? com.sonicsphere.audio.MainActivity)?.getMusicService()
            ?: MusicService.getInstance()

    override fun onDestroyView() {
        super.onDestroyView()
        stopPeriodicUpdate()
        _binding = null
    }
}
