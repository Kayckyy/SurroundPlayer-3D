s.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var isMetaVisible = false

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
                    if (retryService != null && !isServiceReady) {
                        onServiceReady()
                    }
                }, 800)
            }
        } else {
            updateMusicInfo()
            updateControlStates()
            startSeekbarUpdate()
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

        binding.btnToggleMeta.setOnClickListener {
            isMetaVisible = !isMetaVisible
            binding.layoutMeta.visibility = if (isMetaVisible) View.VISIBLE else View.GONE
            binding.btnToggleMeta.text = if (isMetaVisible) "▴ detalhes" else "▾ detalhes"
        }
    }

    private fun setupSeekbar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.currentTime.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                seekBar?.progress?.let { getMusicService()?.seekTo(it) }
            }
        })
    }

    private fun setupControls() {
        binding.btnPrevious.setOnClickListener {
            getMusicService()?.handlePreviousWithThreshold()
            handler.postDelayed({ updateMusicInfo(); updateControlStates() }, 100)
        }
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnNext.setOnClickListener {
            getMusicService()?.playNext()
            handler.postDelayed({ updateMusicInfo(); updateControlStates() }, 100)
        }
        binding.btnShuffle.setOnClickListener {
            updateShuffleButton(getMusicService()?.toggleShuffle() ?: false)
        }
        binding.btnRepeat.setOnClickListener {
            updateRepeatButton(getMusicService()?.toggleRepeat() ?: MusicService.REPEAT_NONE)
        }
    }

    private fun togglePlayPause() {
        if (getMusicService()?.isPlaying() == true) getMusicService()?.pauseMusic()
        else getMusicService()?.resumeMusic()
        handler.postDelayed({ updatePlayPauseButton() }, 50)
    }

    private fun updateControlStates() {
        updatePlayPauseButton()
        updateShuffleButton(getMusicService()?.isShuffling() ?: false)
        updateRepeatButton(getMusicService()?.getRepeatMode() ?: MusicService.REPEAT_NONE)
    }

    private fun updatePlayPauseButton() {
        binding.btnPlayPause.setImageResource(
            if (getMusicService()?.isPlaying() == true) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateShuffleButton(isShuffling: Boolean) {
        binding.btnShuffle.setColorFilter(
            ContextCompat.getColor(requireContext(),
                if (isShuffling) R.color.spotify_green else R.color.gray)
        )
    }

    private fun updateRepeatButton(repeatMode: Int) {
        binding.btnRepeat.setImageResource(when (repeatMode) {
            MusicService.REPEAT_ALL -> R.drawable.ic_repeat_all
            MusicService.REPEAT_ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat
        })
        binding.btnRepeat.setColorFilter(
            ContextCompat.getColor(requireContext(),
                if (repeatMode != MusicService.REPEAT_NONE) R.color.spotify_green else R.color.gray)
        )
    }

    private fun updateMusicInfo() {
        val currentMusic = getMusicService()?.getCurrentMusic()
        if (currentMusic != null) {
            binding.songTitle.text = currentMusic.title
            binding.artistName.text = currentMusic.artist
            binding.albumName.text = currentMusic.album
            binding.badge3DAudio.visibility =
              if (getMusicService()?.isBinauralEnabled() == true) View.VISIBLE else View.GONE

            if (lastMusicPath != currentMusic.path) {
                lastMusicPath = currentMusic.path
                loadMetadataAndAlbumArt(currentMusic.path)
            }
        } else {
            binding.songTitle.text = "Nenhuma música"
            binding.artistName.text = "Selecione uma música"
            binding.albumName.text = ""
            binding.albumArt.setImageResource(R.drawable.album_placeholder)
            binding.badge3DAudio.visibility = View.GONE
            clearMetadata()
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

                        binding.txtMetaFormat.text = "Formato: ${metadata.mimeType ?: "—"}"
                        binding.txtMetaBitrate.text = "Bitrate: ${metadata.bitrate ?: "—"}"
                        binding.txtMetaSampleRate.text = "Sample rate: ${metadata.sampleRate ?: "—"}"
                        binding.txtMetaChannels.text = "Canais: ${metadata.channels ?: "—"}"
                        binding.txtMetaFileSize.text = "Tamanho: ${
                            metadata.fileSize?.let { AlbumArtExtractor.formatFileSize(it) } ?: "—"
                        }"
                    } else {
                        binding.albumArt.setImageResource(R.drawable.album_placeholder)
                        currentAlbumArt = null
                        clearMetadata()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    binding.albumArt.setImageResource(R.drawable.album_placeholder)
                    currentAlbumArt = null
                    clearMetadata()
                }
            }
        }.start()
    }

    private fun clearMetadata() {
        binding.txtMetaFormat.text = ""
        binding.txtMetaBitrate.text = ""
        binding.txtMetaSampleRate.text = ""
        binding.txtMetaChannels.text = ""
        binding.txtMetaFileSize.text = ""
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
        if (service != null) updatePlayPauseButton()
    }

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
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
