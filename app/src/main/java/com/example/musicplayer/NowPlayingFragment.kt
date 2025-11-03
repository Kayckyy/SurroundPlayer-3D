package com.example.musicplayer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.example.musicplayer.databinding.FragmentNowPlayingBinding

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!
    private var handler = Handler(Looper.getMainLooper())
    private var updateSeekbar: Runnable? = null
    private var isPlaying = false
    private var isFavorite = false

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

    private fun setupUI() {
        // Usar um placeholder local
        binding.albumArt.setImageResource(R.drawable.album_placeholder)

        binding.songTitle.text = "Song Title"
        binding.artistName.text = "Artist Name"
        binding.albumName.text = "Album Name"

        setupSeekbar()
    }

    private fun setupSeekbar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Update playback position
                    binding.currentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Set initial values
        binding.seekBar.max = 100
        binding.seekBar.progress = 0
        binding.currentTime.text = "0:00"
        binding.totalTime.text = "3:30"
    }

    private fun setupControls() {
        binding.btnPrevious.setOnClickListener {
            // Previous song
        }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        binding.btnNext.setOnClickListener {
            // Next song
        }

        binding.btnRepeat.setOnClickListener {
            toggleRepeat()
        }

        binding.btnShuffle.setOnClickListener {
            toggleShuffle()
        }

        binding.btnFavorite.setOnClickListener {
            toggleFavorite()
        }
    }

    private fun togglePlayPause() {
        isPlaying = !isPlaying
        if (isPlaying) {
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            startSeekbarUpdate()
        } else {
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            stopSeekbarUpdate()
        }
    }

    private fun toggleRepeat() {
        // Implement repeat logic
    }

    private fun toggleShuffle() {
        // Implement shuffle logic
    }

    private fun toggleFavorite() {
        isFavorite = !isFavorite
        if (isFavorite) {
            binding.btnFavorite.setImageResource(android.R.drawable.btn_star_big_on)
        } else {
            binding.btnFavorite.setImageResource(android.R.drawable.btn_star_big_off)
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
        val currentProgress = binding.seekBar.progress
        if (currentProgress < binding.seekBar.max) {
            binding.seekBar.progress = currentProgress + 1
            binding.currentTime.text = formatTime(binding.seekBar.progress)
        }
    }

    private fun formatTime(progress: Int): String {
        val totalSeconds = (progress * 210 / 100) // Simulate 3:30 song
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSeekbarUpdate()
        _binding = null
    }
}