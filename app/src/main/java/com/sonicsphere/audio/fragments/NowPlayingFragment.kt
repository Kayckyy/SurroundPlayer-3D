package com.sonicsphere.audio.fragments

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sonicsphere.audio.metadata.AlbumArtExtractor
import com.sonicsphere.audio.MainActivity
import com.sonicsphere.audio.service.MusicService
import com.sonicsphere.audio.R
import com.sonicsphere.audio.databinding.FragmentNowPlayingBinding

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!
    private var handler = Handler(Looper.getMainLooper())
    private var updateSeekbar: Runnable? = null
    private var isServiceReady = false
    private var currentAlbumArt: Bitmap? = null
    private var lastMusicPath: String? = null
    private var isSeeking = false
    private var isAdjustingPitch = false
    private var isAdjustingSpeed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupControls()
        setupAdvancedControls()
        verifyHaasState()
    }

    override fun onResume() {
        super.onResume()

        if (!isServiceReady) {
            val service = getMusicService()
            if (service != null) {
                onServiceReady()
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    val retryService = getMusicService()
                    if (retryService != null && !isServiceReady) {
                        onServiceReady()
                    }
                }, 800)
            }
        } else {
            updateMusicInfo()
            updateControlStates()
            updateAdvancedControlStates()
            startSeekbarUpdate()
            verifyHaasState()
        }
    }

    fun onServiceReady() {
        isServiceReady = true
        updateMusicInfo()
        updateControlStates()
        updateAdvancedControlStates()
        startSeekbarUpdate()
        verifyHaasState()
    }

    private fun setupUI() {
        binding.albumArt.setImageResource(R.drawable.album_placeholder)
        setupSeekbar()
    }

    private fun setupSeekbar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.currentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                seekBar?.progress?.let { progress ->
                    getMusicService()?.seekTo(progress)
                }
            }
        })
    }

    private fun setupControls() {
        binding.btnPrevious.setOnClickListener {
            getMusicService()?.handlePreviousWithThreshold()
            handler.postDelayed({
                updateMusicInfo()
                updateControlStates()
            }, 100)
        }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        binding.btnNext.setOnClickListener {
            getMusicService()?.playNext()
            handler.postDelayed({
                updateMusicInfo()
                updateControlStates()
            }, 100)
        }

        binding.btnShuffle.setOnClickListener {
            val isShuffling = getMusicService()?.toggleShuffle() ?: false
            updateShuffleButton(isShuffling)
        }

        binding.btnRepeat.setOnClickListener {
            val repeatMode = getMusicService()?.toggleRepeat() ?: MusicService.REPEAT_NONE
            updateRepeatButton(repeatMode)
        }

        binding.btnFavorite.setOnClickListener {
            val currentMusic = getMusicService()?.getCurrentMusic()
            currentMusic?.let { music ->
                val isNowFavorite = getMusicService()?.toggleFavorite(music.path) ?: false
                updateFavoriteButton(isNowFavorite)
            }
        }
    }

    private fun setupAdvancedControls() {
        // Botão Reverse
        binding.btnReverse.setOnClickListener {
            val currentReverse = getMusicService()?.isReversed() ?: false
            val newReverse = !currentReverse
            getMusicService()?.setReverse(newReverse)
            updateReverseButton(newReverse)
        }

        // SeekBar de Pitch (-12 a +12 semitons)
        binding.seekBarPitch.max = 24 // -12 a +12 = 24 posições
        binding.seekBarPitch.progress = 12 // 0 semitons no centro

        binding.seekBarPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val semitones = progress - 12 // -12 a +12
                    binding.txtPitch.text = String.format("%+d semitons", semitones)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isAdjustingPitch = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isAdjustingPitch = false
                val semitones = (seekBar?.progress ?: 12) - 12
                getMusicService()?.setPitch(semitones)
                Log.d("NowPlayingFragment", "🎵 Pitch definido: $semitones semitons")
            }
        })

        // SeekBar de Speed (25% a 250% = 0.25x a 2.5x)
        binding.seekBarSpeed.max = 225 // 0 a 225 = 0.25x a 2.5x
        binding.seekBarSpeed.progress = 75 // 1.0x no centro

        binding.seekBarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val speedFactor = (progress + 25) / 100f // 0.25 a 2.5
                    binding.txtSpeed.text = String.format("%.2fx", speedFactor)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isAdjustingSpeed = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isAdjustingSpeed = false
                val speedFactor = ((seekBar?.progress ?: 75) + 25) / 100f
                getMusicService()?.setSpeed(speedFactor)
                Log.d("NowPlayingFragment", "⚡ Velocidade definida: ${speedFactor}x")
            }
        })

        // Botões de reset
        binding.btnResetPitch.setOnClickListener {
            binding.seekBarPitch.progress = 12
            getMusicService()?.setPitch(0)
            binding.txtPitch.text = "0 semitons"
        }

        binding.btnResetSpeed.setOnClickListener {
            binding.seekBarSpeed.progress = 75
            getMusicService()?.setSpeed(1.0f)
            binding.txtSpeed.text = "1.00x"
        }
    }

    private fun updateAdvancedControlStates() {
        val service = getMusicService() ?: return

        // Atualizar Reverse
        val isReversed = service.isReversed()
        updateReverseButton(isReversed)

        // Atualizar Pitch
        val pitch = service.getPitch()
        binding.seekBarPitch.progress = pitch + 12
        binding.txtPitch.text = String.format("%+d semitons", pitch)

        // Atualizar Speed
        val speed = service.getSpeed()
        val speedProgress = ((speed * 100) - 25).toInt()
        binding.seekBarSpeed.progress = speedProgress.coerceIn(0, 225)
        binding.txtSpeed.text = String.format("%.2fx", speed)
    }

    private fun updateReverseButton(isReversed: Boolean) {
        val colorRes = if (isReversed) R.color.spotify_green else R.color.gray
        val color = ContextCompat.getColor(requireContext(), colorRes)
        binding.btnReverse.setColorFilter(color)
    }

    private fun verifyHaasState() {
        val service = getMusicService()
        val currentHaasDelay = service?.getHaasDelay() ?: 0
        Log.d("NowPlayingFragment", "🎧 Haas verificado (sempre ativo): ${currentHaasDelay}ms")
    }

    private fun togglePlayPause() {
        if (getMusicService()?.isPlaying() == true) {
            getMusicService()?.pauseMusic()
        } else {
            getMusicService()?.resumeMusic()
        }
        handler.postDelayed({
            updatePlayPauseButton()
        }, 50)
    }

    private fun updateControlStates() {
        updatePlayPauseButton()
        updateShuffleButton(getMusicService()?.isShuffling() ?: false)
        updateRepeatButton(getMusicService()?.getRepeatMode() ?: MusicService.REPEAT_NONE)

        val currentMusic = getMusicService()?.getCurrentMusic()
        val isFavorite = currentMusic?.let { getMusicService()?.isFavorite(it.path) } ?: false
        updateFavoriteButton(isFavorite)
    }

    private fun updatePlayPauseButton() {
        val isPlaying = getMusicService()?.isPlaying() == true
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateShuffleButton(isShuffling: Boolean) {
        val colorRes = if (isShuffling) R.color.spotify_green else R.color.gray
        val color = ContextCompat.getColor(requireContext(), colorRes)
        binding.btnShuffle.setColorFilter(color)
    }

    private fun updateRepeatButton(repeatMode: Int) {
        val icon = when (repeatMode) {
            MusicService.REPEAT_ALL -> R.drawable.ic_repeat_all
            MusicService.REPEAT_ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat
        }

        val colorRes = if (repeatMode != MusicService.REPEAT_NONE) R.color.spotify_green else R.color.gray
        val color = ContextCompat.getColor(requireContext(), colorRes)

        binding.btnRepeat.setImageResource(icon)
        binding.btnRepeat.setColorFilter(color)
    }

    private fun updateFavoriteButton(isFavorite: Boolean) {
        val icon = if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
        val color = if (isFavorite) {
            ContextCompat.getColor(requireContext(), R.color.spotify_green)
        } else {
            ContextCompat.getColor(requireContext(), R.color.gray)
        }

        binding.btnFavorite.setImageResource(icon)
        binding.btnFavorite.setColorFilter(color)
    }

    private fun updateMusicInfo() {
        val currentMusic = getMusicService()?.getCurrentMusic()
        if (currentMusic != null) {
            binding.songTitle.text = currentMusic.title
            binding.artistName.text = currentMusic.artist
            binding.albumName.text = currentMusic.album

            if (lastMusicPath != currentMusic.path) {
                lastMusicPath = currentMusic.path
                loadMetadataAndAlbumArt(currentMusic.path)
            }

            val isFavorite = getMusicService()?.isFavorite(currentMusic.path) ?: false
            updateFavoriteButton(isFavorite)
        } else {
            binding.songTitle.text = "Nenhuma música"
            binding.artistName.text = "Selecione uma música"
            binding.albumName.text = ""
            binding.albumArt.setImageResource(R.drawable.album_placeholder)
            updateFavoriteButton(false)
            lastMusicPath = null
        }
    }

    private fun loadMetadataAndAlbumArt(musicPath: String) {
        Thread {
            try {
                val metadata = AlbumArtExtractor.getMetadata(musicPath)
                activity?.runOnUiThread {
                    if (metadata != null) {
                        binding.songTitle.text = metadata.title ?: binding.songTitle.text
                        binding.artistName.text = metadata.artist ?: binding.artistName.text
                        binding.albumName.text = metadata.album ?: binding.albumName.text

                        if (metadata.albumArt != null) {
                            binding.albumArt.setImageBitmap(metadata.albumArt)
                            currentAlbumArt = metadata.albumArt
                        } else {
                            binding.albumArt.setImageResource(R.drawable.album_placeholder)
                            currentAlbumArt = null
                        }
                    } else {
                        binding.albumArt.setImageResource(R.drawable.album_placeholder)
                        currentAlbumArt = null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    binding.albumArt.setImageResource(R.drawable.album_placeholder)
                    currentAlbumArt = null
                }
            }
        }.start()
    }

    private fun startSeekbarUpdate() {
        updateSeekbar = object : Runnable {
            override fun run() {
                updateSeekbarProgress()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateSeekbar!!)
    }

    private fun stopSeekbarUpdate() {
        updateSeekbar?.let { handler.removeCallbacks(it) }
    }

    private fun updateSeekbarProgress() {
    if (isSeeking) return

    val service = getMusicService()
    val currentPosition = service?.getCurrentPosition() ?: 0
    val duration = service?.getDuration() ?: 0

    if (duration > 0) {
        binding.seekBar.max = duration
        binding.seekBar.progress = currentPosition
        binding.currentTime.text = formatTime(currentPosition)
        binding.totalTime.text = formatTime(duration)
    }

    if (service != null) {
        updatePlayPauseButton()
    }
}

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun getMusicService(): MusicService? {
        return (requireActivity() as? MainActivity)?.getMusicService() ?: MusicService.getInstance()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSeekbarUpdate()
        _binding = null
    }
}
