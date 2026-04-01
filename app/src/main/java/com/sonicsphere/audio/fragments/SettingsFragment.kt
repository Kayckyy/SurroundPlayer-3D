package com.sonicsphere.audio.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.sonicsphere.audio.databinding.FragmentSettingsBinding
import com.sonicsphere.audio.service.ConvolutionEngine
import com.sonicsphere.audio.service.IrLoader
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

    // ========== BINAURAL (L/R apenas) ==========

    private fun setupBinaural() {
        binding.switchBinaural.setOnCheckedChangeListener { _, isChecked ->
            getMusicService()?.setBinauralEnabled(isChecked)
            binding.binauralSlotsContainer.visibility =
                if (isChecked) View.VISIBLE else View.GONE
            updateHaasEnabled(!isChecked)
            
            // Atualiza estado do Haas quando o áudio 3D muda
            updateHaasUiState()
            
            // Atualiza estado do switch de reverb também
            binding.switchReverb.isEnabled = isChecked
            if (!isChecked) {
                // Se desligar áudio 3D, desliga reverb também
                binding.switchReverb.isChecked = false
            }
            
            if (isChecked) refreshIrStatus()
        }

        binding.btnIrLeft.setOnClickListener  { pickIrFile(ConvolutionEngine.IrSlot.LEFT) }
        binding.btnIrRight.setOnClickListener { pickIrFile(ConvolutionEngine.IrSlot.RIGHT) }

        binding.btnIrLeftClear.setOnClickListener  { resetIrToDefault(ConvolutionEngine.IrSlot.LEFT) }
        binding.btnIrRightClear.setOnClickListener { resetIrToDefault(ConvolutionEngine.IrSlot.RIGHT) }
        
    private fun updateHaasEnabled(enabled: Boolean) {
    val alpha = if (enabled) 1.0f else 0.38f
    binding.radioGroupHaas.isEnabled = enabled
    binding.radioHaasOff.isEnabled    = enabled
    binding.radioHaasShort.isEnabled  = enabled
    binding.radioHaasMedium.isEnabled = enabled
    binding.radioHaasLong.isEnabled   = enabled
    // Ofusca visualmente o container inteiro
    binding.radioGroupHaas.alpha = alpha
    // Se ativou o binaural, força Haas off
    if (!enabled) {
        getMusicService()?.setHaasDelay(0)
        binding.radioHaasOff.isChecked = true
    }
    }

    // ========== HAAS (Especialização Estéreo) ==========

    private fun setupHaas() {
        binding.radioGroupHaas.setOnCheckedChangeListener { _, id ->
            // Só permite mudar se áudio 3D estiver desativado
            if (getMusicService()?.isBinauralEnabled() == false) {
                val ms = when (id) {
                    binding.radioHaasShort.id  -> 10
                    binding.radioHaasMedium.id -> 30
                    binding.radioHaasLong.id   -> 50
                    else -> 0
                }
                getMusicService()?.setHaasDelay(ms)
            } else {
                // Se áudio 3D estiver ativo, reverte a seleção
                syncHaasFromService()
                Toast.makeText(requireContext(), 
                    "Desative o Áudio 3D para ajustar a Especialização Estéreo", 
                    Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateHaasUiState() {
        val is3dEnabled = getMusicService()?.isBinauralEnabled() ?: false
        binding.radioGroupHaas.isEnabled = !is3dEnabled
        
        // Desabilita visualmente os radios
        binding.radioHaasOff.isEnabled = !is3dEnabled
        binding.radioHaasShort.isEnabled = !is3dEnabled
        binding.radioHaasMedium.isEnabled = !is3dEnabled
        binding.radioHaasLong.isEnabled = !is3dEnabled
    }
    
    private fun syncHaasFromService() {
        when (getMusicService()?.getHaasDelay()) {
            10   -> binding.radioHaasShort.isChecked  = true
            30   -> binding.radioHaasMedium.isChecked = true
            50   -> binding.radioHaasLong.isChecked   = true
            else -> binding.radioHaasOff.isChecked    = true
        }
    }
    }
    private fun pickIrFile(slot: ConvolutionEngine.IrSlot) {
        val ctx = requireContext()
        val files = IrLoader.listAvailableIrs(ctx)
        if (files.isEmpty()) {
            Toast.makeText(ctx, "Nenhum WAV em Android/data/com.sonicsphere.audio/files/ir/",
                Toast.LENGTH_LONG).show()
            return
        }
        val engine = getMusicService()?.getConvolutionEngine() ?: run {
            Toast.makeText(ctx, "Player nao iniciado", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("IR para ${slot.name}")
            .setItems(files.map { it.name }.toTypedArray()) { _, i ->
                setIrStatus(slot, "Carregando...")
                Thread {
                    IrLoader.loadIntoSlot(files[i], slot, engine) { ok, err ->
                        handler.post {
                            if (ok) setIrStatus(slot, files[i].name)
                            else    setIrStatus(slot, "Erro: $err")
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun resetIrToDefault(slot: ConvolutionEngine.IrSlot) {
        val engine = getMusicService()?.getConvolutionEngine() ?: return
        // Captura context antes da thread — requireContext() dentro de Thread causa crash
        val ctx = context ?: return
        Thread {
            val wav = IrLoader.readWav(
                ctx.assets.open("${slot.name.lowercase()}.wav")
            ) ?: return@Thread
            engine.loadIr(slot, wav.left, wav.right)
            getMusicService()?.unloadConvolutionIr(slot)
            handler.post { setIrStatus(slot, "Padrao (asset)") }
        }.start()
    }

    private fun setIrStatus(slot: ConvolutionEngine.IrSlot, text: String) {
        when (slot) {
            ConvolutionEngine.IrSlot.LEFT  -> binding.textIrLeft.text  = text
            ConvolutionEngine.IrSlot.RIGHT -> binding.textIrRight.text = text
        }
    }

    private fun refreshIrStatus() {
        val engine = getMusicService()?.getConvolutionEngine() ?: return
        setIrStatus(ConvolutionEngine.IrSlot.LEFT,
            if (engine.isSlotLoaded(ConvolutionEngine.IrSlot.LEFT)) "Carregado" else "Padrao (asset)")
        setIrStatus(ConvolutionEngine.IrSlot.RIGHT,
            if (engine.isSlotLoaded(ConvolutionEngine.IrSlot.RIGHT)) "Carregado" else "Padrao (asset)")
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
        val is3dEnabled = s.isBinauralEnabled()
        binding.switchBinaural.isChecked = is3dEnabled
        binding.binauralSlotsContainer.visibility =
            if (is3dEnabled) View.VISIBLE else View.GONE
        if (is3dEnabled) refreshIrStatus()
        
        // Haas - atualiza estado da UI
        updateHaasUiState()
        syncHaasFromService()
        updateHaasEnabled(!s.isBinauralEnabled())
    }

    // ========== UTILITÁRIOS ==========

    private fun seekListener(onFromUser: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) onFromUser(p)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

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
