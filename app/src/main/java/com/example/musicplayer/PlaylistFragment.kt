package com.example.musicplayer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.databinding.FragmentPlaylistBinding
import com.example.musicplayer.databinding.ItemMusicBinding

class PlaylistFragment : Fragment() {

    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val musicList = listOf(
            Music(1, "Song 1", "Artist 1", "Album 1", 180000, ""),
            Music(2, "Song 2", "Artist 2", "Album 2", 200000, ""),
            Music(3, "Song 3", "Artist 3", "Album 3", 220000, "")
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = MusicAdapter(musicList)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class MusicAdapter(private val musicList: List<Music>) :
        RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

        inner class MusicViewHolder(val binding: ItemMusicBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
            val binding = ItemMusicBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return MusicViewHolder(binding)
        }

        override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
            val music = musicList[position]
            holder.binding.apply {
                songTitle.text = music.title
                artistName.text = music.artist
                duration.text = formatTime(music.duration.toInt())

                root.setOnClickListener {
                    // Play music
                }
            }
        }

        override fun getItemCount(): Int = musicList.size

        private fun formatTime(milliseconds: Int): String {
            val minutes = (milliseconds / 1000) / 60
            val seconds = (milliseconds / 1000) % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
    }
}