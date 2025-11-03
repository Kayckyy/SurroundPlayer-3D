package com.example.musicplayer

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.databinding.FragmentPlaylistBinding
import com.example.musicplayer.databinding.ItemFileBinding
import java.io.File

class FileExplorerFragment : Fragment() {

    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!
    private var currentPath: String = Environment.getExternalStorageDirectory().absolutePath
    private val musicExtensions = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac")
    private var isServiceReady = false

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

        // Tentar carregar a última pasta usada
        val lastFolder = getMusicService()?.getCurrentFolder()
        if (!lastFolder.isNullOrEmpty() && File(lastFolder).exists()) {
            currentPath = lastFolder
        }

        loadDirectory(currentPath)
    }

    override fun onResume() {
        super.onResume()
        if (!isServiceReady) {
            val service = getMusicService()
            if (service != null) {
                onServiceReady()
            }
        }
    }

    fun onServiceReady() {
        isServiceReady = true
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun loadDirectory(path: String) {
        currentPath = path
        val currentDir = File(path)
        val filesAndDirs = mutableListOf<FileItem>()

        if (path != Environment.getExternalStorageDirectory().absolutePath) {
            filesAndDirs.add(FileItem("..", currentDir.parent ?: "", true, false))
        }

        currentDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (file.isDirectory && !file.isHidden) {
                filesAndDirs.add(FileItem(file.name, file.absolutePath, true, false))
            }
        }

        currentDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (file.isFile && isMusicFile(file)) {
                filesAndDirs.add(FileItem(file.name, file.absolutePath, false, true))
            }
        }

        binding.recyclerView.adapter = FileAdapter(filesAndDirs)
    }

    private fun isMusicFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return musicExtensions.contains(extension)
    }

    private fun playMusicFile(filePath: String) {
        getMusicService()?.playMusicFile(filePath)
        (requireActivity() as MainActivity).switchToNowPlayingTab()
    }

    private fun getMusicService(): MusicService? {
        return (requireActivity() as? MainActivity)?.getMusicService() ?: MusicService.getInstance()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class FileItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val isMusicFile: Boolean
    )

    private inner class FileAdapter(private val items: List<FileItem>) :
        RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

        inner class FileViewHolder(val binding: ItemFileBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
            val binding = ItemFileBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return FileViewHolder(binding)
        }

        override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
            val item = items[position]
            holder.binding.apply {
                fileName.text = item.name

                fileIcon.setImageResource(
                    when {
                        item.isDirectory -> R.drawable.ic_folder
                        item.isMusicFile -> R.drawable.ic_music_note
                        else -> R.drawable.ic_file
                    }
                )

                val textColor = if (item.isMusicFile) {
                    requireContext().getColor(R.color.spotify_green)
                } else {
                    requireContext().getColor(R.color.white)
                }
                fileName.setTextColor(textColor)

                root.setOnClickListener {
                    if (item.isDirectory) {
                        if (item.name == "..") {
                            loadDirectory(item.path)
                        } else {
                            loadDirectory(item.path)
                        }
                    } else if (item.isMusicFile) {
                        playMusicFile(item.path)
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}