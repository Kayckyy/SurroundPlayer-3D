package com.sonicsphere.audio.fragments

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sonicsphere.audio.MainActivity
import com.sonicsphere.audio.service.MusicService
import com.sonicsphere.audio.R
import com.sonicsphere.audio.databinding.FragmentPlaylistBinding
import com.sonicsphere.audio.databinding.ItemFileBinding
import com.sonicsphere.audio.metadata.AlbumArtExtractor
import java.io.File

class FileExplorerFragment : Fragment() {

    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!
    private var currentPath: String = Environment.getExternalStorageDirectory().absolutePath
    private val musicExtensions = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus")
    private var isServiceReady = false

    // Cache de thumbnails para evitar recarregar
    private val thumbnailCache = mutableMapOf<String, Bitmap?>()

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
        setupRefreshButton()

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

        // Atualizar favoritos quando retornar ao fragment
        binding.recyclerView.adapter?.notifyDataSetChanged()
    }

    fun onServiceReady() {
        isServiceReady = true
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupRefreshButton() {
        // Assumindo que você tem um botão de refresh no layout
        // Se não tiver, adicione: <ImageButton android:id="@+id/btnRefresh" ... />
        // Por enquanto, vou criar a função que você pode chamar
    }

    fun refreshCurrentDirectory() {
        thumbnailCache.clear()
        loadDirectory(currentPath)
        Toast.makeText(requireContext(), "Atualizado", Toast.LENGTH_SHORT).show()
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
        thumbnailCache.clear()
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

                val isFavorite = if (item.isMusicFile) {
                    getMusicService()?.isFavorite(item.path) ?: false
                } else {
                    false
                }

                // Configurar ícone/thumbnail
                if (item.isMusicFile) {
                    // Sempre mostrar placeholder primeiro
                    fileIcon.setImageResource(R.drawable.ic_music_note)

                    // Verificar se já tem no cache
                    if (thumbnailCache.containsKey(item.path)) {
                        val cachedThumbnail = thumbnailCache[item.path]
                        if (cachedThumbnail != null) {
                            fileIcon.setImageBitmap(cachedThumbnail)
                            fileIcon.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        } else {
                            // Sem thumbnail, usar ícone apropriado
                            fileIcon.setImageResource(
                                if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_music_note
                            )
                            fileIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        }
                    } else {
                        // Carregar thumbnail em background
                        fileIcon.setImageResource(
                            if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_music_note
                        )
                        fileIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER

                        loadThumbnail(item.path, holder)
                    }
                } else if (item.isDirectory) {
                    fileIcon.setImageResource(R.drawable.ic_folder)
                    fileIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                } else {
                    fileIcon.setImageResource(R.drawable.ic_file)
                    fileIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }

                val textColor = if (item.isMusicFile) {
                    if (isFavorite) {
                        ContextCompat.getColor(requireContext(), R.color.spotify_green)
                    } else {
                        ContextCompat.getColor(requireContext(), R.color.white)
                    }
                } else {
                    ContextCompat.getColor(requireContext(), R.color.white)
                }
                fileName.setTextColor(textColor)

                root.setOnClickListener {
                    if (item.isDirectory) {
                        loadDirectory(item.path)
                    } else if (item.isMusicFile) {
                        playMusicFile(item.path)
                    }
                }

                root.setOnLongClickListener {
                    if (item.isMusicFile) {
                        val isNowFavorite = getMusicService()?.toggleFavorite(item.path) ?: false

                        // Se não tem thumbnail, voltar para o ícone
                        if (!thumbnailCache.containsKey(item.path) || thumbnailCache[item.path] == null) {
                            fileIcon.setImageResource(
                                if (isNowFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_music_note
                            )
                            fileIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        }

                        val newColor = if (isNowFavorite) {
                            ContextCompat.getColor(requireContext(), R.color.spotify_green)
                        } else {
                            ContextCompat.getColor(requireContext(), R.color.white)
                        }
                        fileName.setTextColor(newColor)

                        Toast.makeText(requireContext(),
                            if (isNowFavorite) "Adicionado aos favoritos" else "Removido dos favoritos",
                            Toast.LENGTH_SHORT
                        ).show()

                        true
                    } else {
                        false
                    }
                }
            }
        }

        private fun loadThumbnail(musicPath: String, holder: FileViewHolder) {
            Thread {
                try {
                    val albumArt = AlbumArtExtractor.getAlbumArt(musicPath)

                    // Adicionar ao cache
                    thumbnailCache[musicPath] = albumArt

                    // Atualizar UI
                    activity?.runOnUiThread {
                        if (albumArt != null) {
                            holder.binding.fileIcon.setImageBitmap(albumArt)
                            holder.binding.fileIcon.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        }
                        // Se não tiver thumbnail, mantém o ícone que já estava
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    thumbnailCache[musicPath] = null
                }
            }.start()
        }

        override fun getItemCount(): Int = items.size
    }
}