package com.example.musicplayer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.example.musicplayer.databinding.FragmentNowPlayingBinding

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!
    private var handler = Handler(Looper.getMainLooper())
    private var updateSeekbar: Runnable? = null
    private var isServiceReady = false

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
                    if (retryService != null) {
                        onServiceReady()
                    }
                }, 500)
            }
        }
    }

    fun onServiceReady() {
        isServiceReady = true
        updateMusicInfo()
        updateControlStates()
        startSeekbarUpdate()
    }

    private fun setupUI() {
        binding.albumArt.setImageResource(R.drawable.album_placeholder)
        setupSeekbar()
    }

    private fun setupSeekbar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    getMusicService()?.seekTo(progress)
                    binding.currentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupControls() {
        binding.btnPrevious.setOnClickListener {
            getMusicService()?.playPrevious()
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
            // Implementar favoritos depois
        }
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
    }

    private fun updatePlayPauseButton() {
        val isPlaying = getMusicService()?.isPlaying() == true
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateShuffleButton(isShuffling: Boolean) {
        val color = if (isShuffling) R.color.spotify_green else R.color.gray
        binding.btnShuffle.setColorFilter(requireContext().getColor(color))
    }

    private fun updateRepeatButton(repeatMode: Int) {
        val icon = when (repeatMode) {
            MusicService.REPEAT_ALL -> R.drawable.ic_repeat_all
            MusicService.REPEAT_ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat
        }
        val color = if (repeatMode != MusicService.REPEAT_NONE) R.color.spotify_green else R.color.gray

        binding.btnRepeat.setImageResource(icon)
        binding.btnRepeat.setColorFilter(requireContext().getColor(color))
    }

    private fun updateMusicInfo() {
        val currentMusic = getMusicService()?.getCurrentMusic()
        if (currentMusic != null) {
            binding.songTitle.text = currentMusic.title
            binding.artistName.text = currentMusic.artist
            binding.albumName.text = currentMusic.album
        } else {
            binding.songTitle.text = "Nenhuma música"
            binding.artistName.text = "Selecione uma música"
            binding.albumName.text = ""
        }
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
        val service = getMusicService()
        val currentPosition = service?.getCurrentPosition() ?: 0
        val duration = service?.getDuration() ?: 0

        binding.seekBar.max = duration
        binding.seekBar.progress = currentPosition
        binding.currentTime.text = formatTime(currentPosition)
        binding.totalTime.text = formatTime(duration)

        updatePlayPauseButton()
        updateMusicInfo()
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