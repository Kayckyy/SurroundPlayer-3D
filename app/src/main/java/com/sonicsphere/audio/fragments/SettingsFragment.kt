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
    private var isServiceReady = false
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    // Mapa slot → label do status no layout
    private val slotStatusMap by lazy {
        mapOf(
            ConvolutionEngine.IrSlot.LEFT  to binding.textIrLeft,
            ConvolutionEngine.IrSlot.RIGHT to binding.textIrRight,
            ConvolutionEngine.IrSlot.FRONT to binding.textIrFront,
            ConvolutionEngine.IrSlot.TOP   to binding.textIrTop,
            ConvolutionEngine.IrSlot.BACK  to binding.textIrBack,
            ConvolutionEngine.IrSlot.SUB   to binding.textIrSub,
        )
    }

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
        setupBinauralSection()
        startPeriodicUpdate()
    }

    override fun onResume() {
        super.onResume()
        val service = getMusicService()
        if (service != null && !isServiceReady) onServiceReady()
        updateUIFromService()
        refreshIrStatus()
    }

    override fun onPause() {
        super.onPause()
        stopPeriodicUpdate()
    }

    private fun startPeriodicUpdate() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (binding.switchEqualizer.isChecked &&
                    binding.equalizerBandsContainer.childCount == 0) {
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

    // ========== SEÇÃO IR BINAURAL ==========

    private fun setupBinauralSection() {
        binding.switchBinaural.setOnCheckedChangeListener { _, isChecked ->
            binding.binauralSlotsContainer.visibility =
                if (isChecked) View.VISIBLE else View.GONE
            getMusicService()?.setBinauralEnabled(isChecked)
            if (isChecked) refreshIrStatus()
        }

        // Botões carregar
        binding.btnIrLeft.setOnClickListener  { pickIrFile(ConvolutionEngine.IrSlot.LEFT) }
        binding.btnIrRight.setOnClickListener { pickIrFile(ConvolutionEngine.IrSlot.RIGHT) }
        binding.btnIrFront.setOnClickListener { pickIrFile(ConvolutionEngine.IrSlot.FRONT) }
        binding.btnIrTop.setOnClickListener   { pickIrFile(ConvolutionEngine.IrSlot.TOP) }
        binding.btnIrBack.setOnClickListener  { pickIrFile(ConvolutionEngine.IrSlot.BACK) }
        binding.btnIrSub.setOnClickListener   { pickIrFile(ConvolutionEngine.IrSlot.SUB) }

        // Botões limpar
        binding.btnIrLeftClear.setOnClickListener  { clearIrSlot(ConvolutionEngine.IrSlot.LEFT) }
        binding.btnIrRightClear.setOnClickListener { clearIrSlot(ConvolutionEngine.IrSlot.RIGHT) }
        binding.btnIrFrontClear.setOnClickListener { clearIrSlot(ConvolutionEngine.IrSlot.FRONT) }
        binding.btnIrTopClear.setOnClickListener   { clearIrSlot(ConvolutionEngine.IrSlot.TOP) }
        binding.btnIrBackClear.setOnClickListener  { clearIrSlot(ConvolutionEngine.IrSlot.BACK) }
        binding.btnIrSubClear.setOnClickListener   { clearIrSlot(ConvolutionEngine.IrSlot.SUB) }
    }

    /**
     * Mostra dialog com lista de WAVs disponíveis na pasta ir/ e carrega o escolhido.
     */
    private fun pickIrFile(slot: ConvolutionEngine.IrSlot) {
        val ctx = requireContext()
        val irFiles = IrLoader.listAvailableIrs(ctx)

        if (irFiles.isEmpty()) {
            Toast.makeText(
                ctx,
                "Nenhum WAV em Android/data/com.sonicsphere.audio/files/ir/",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val names = irFiles.map { it.name }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Escolher IR — ${slot.name}")
            .setItems(names) { _, which ->
                val file = irFiles[which]
                val engine = getMusicService()?.getConvolutionEngine() ?: run {
                    Toast.makeText(ctx, "Player não iniciado ainda", Toast.LENGTH_SHORT).show()
                    return@setItems
                }

                setSlotStatus(slot, "Carregando…")

                Thread {
                    IrLoader.loadIntoSlot(file, slot, engine) { success, error ->
                        handler.post {
                            if (success) {
                                setSlotStatus(slot, file.name)
                                Toast.makeText(ctx, "✅ ${slot.name}: ${file.name}", Toast.LENGTH_SHORT).show()
                            } else {
                                setSlotStatus(slot, "Erro: $error")
                                Toast.makeText(ctx, "❌ Falha: $error", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun clearIrSlot(slot: ConvolutionEngine.IrSlot) {
        getMusicService()?.getConvolutionEngine()?.unloadIr(slot)
        setSlotStatus(slot, "Não carregado")
        Toast.makeText(requireContext(), "${slot.name} removido", Toast.LENGTH_SHORT).show()
    }

    private fun setSlotStatus(slot: ConvolutionEngine.IrSlot, text: String) {
        slotStatusMap[slot]?.text = text
    }

    private fun refreshIrStatus() {
        val engine = getMusicService()?.getConvolutionEngine() ?: return
        for ((slot, textView) in slotStatusMap) {
            textView.text = if (engine.isSlotLoaded(slot)) "✅ Carregado" else "Não carregado"
        }
    }

    // ========== EQUALIZER ==========

    private fun setupSettings() {
        binding.switchEqualizer.setOnCheckedChangeListener { _, isChecked ->
            getMusicService()?.setEqualizerEnabled(isChecked)
            updateEqualizerVisibility(isChecked)
            if (isChecked) handler.postDelayed({ setupEqualizerBands() }, 500)
        }

        binding.switchBassBoost.setOnCheckedChangeListener { _, isChecked ->
            getMusicService()?.setBassBoostEnabled(isChecked)
            updateBassBoostVisibility(isChecked)
        }

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

        binding.radioGroupHaas.setOnCheckedChangeListener { _, checkedId ->
            val delayMs = when (checkedId) {
                binding.radioHaasShort.id  -> 10
                binding.radioHaasMedium.id -> 30
                binding.radioHaasLong.id   -> 50
                else -> 0
            }
            getMusicService()?.setHaasDelay(delayMs)
        }

        updateUIFromService()
    }

    private fun setupEqualizerBands() {
        val service = getMusicService() ?: return
        val numberOfBands = service.getEqualizerNumberOfBands() ?: return
        val bandLevelRange = service.getEqualizerBandLevelRange() ?: return

        binding.equalizerBandsContainer.removeAllViews()

        for (band in 0 until numberOfBands.toInt()) {
            val bandView = layoutInflater.inflate(
                com.sonicsphere.audio.R.layout.item_equalizer_band,
                binding.equalizerBandsContainer, false
            )
            val bandLabel   = bandView.findViewById<android.widget.TextView>(com.sonicsphere.audio.R.id.bandLabel)
            val bandSeekBar = bandView.findViewById<SeekBar>(com.sonicsphere.audio.R.id.bandSeekBar)
            val bandValue   = bandView.findViewById<android.widget.TextView>(com.sonicsphere.audio.R.id.bandValue)

            val centerFreq = service.getEqualizerCenterFreq(band.toShort()) ?: 0
            bandLabel.text = if (centerFreq >= 1000) "${centerFreq / 1000}kHz" else "${centerFreq}Hz"

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

        val equalizerEnabled = service.isEqualizerEnabled()
        binding.switchEqualizer.isChecked = equalizerEnabled
        updateEqualizerVisibility(equalizerEnabled)
        if (equalizerEnabled && binding.equalizerBandsContainer.childCount == 0) setupEqualizerBands()

        val bassBoostEnabled = service.isBassBoostEnabled()
        binding.switchBassBoost.isChecked = bassBoostEnabled
        updateBassBoostVisibility(bassBoostEnabled)
        val bassStrength = service.getBassBoostStrength()?.toInt() ?: 0
        binding.seekBarBassBoost.progress = bassStrength
        binding.textBassBoostValue.text = "${bassStrength / 10}%"

        val haasDelay = service.getHaasDelay()
        when (haasDelay) {
            10   -> binding.radioHaasShort.isChecked  = true
            30   -> binding.radioHaasMedium.isChecked = true
            50   -> binding.radioHaasLong.isChecked   = true
            else -> binding.radioHaasOff.isChecked    = true
        }

        val binauralEnabled = service.isBinauralEnabled()
        binding.switchBinaural.isChecked = binauralEnabled
        binding.binauralSlotsContainer.visibility =
            if (binauralEnabled) View.VISIBLE else View.GONE
        if (binauralEnabled) refreshIrStatus()
    }

    private fun updateEqualizerVisibility(visible: Boolean) {
        binding.equalizerBandsContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateBassBoostVisibility(visible: Boolean) {
        binding.bassBoostControlsContainer.visibility = if (visible) View.VISIBLE else View.GONE
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
