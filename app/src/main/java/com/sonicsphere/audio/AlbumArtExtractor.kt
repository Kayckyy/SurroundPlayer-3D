package com.sonicsphere.audio

import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory

object AlbumArtExtractor {

    data class MusicMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val duration: Long,
        val albumArt: Bitmap?
    )

    fun getAlbumArt(path: String): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)

            val artwork = retriever.embeddedPicture
            retriever.release()

            artwork?.let {
                BitmapFactory.decodeByteArray(artwork, 0, artwork.size)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Função para pegar todos os metadados de uma vez
    fun getMetadata(path: String): MusicMetadata? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L

            val artwork = retriever.embeddedPicture
            val albumArt = artwork?.let {
                BitmapFactory.decodeByteArray(artwork, 0, artwork.size)
            }

            retriever.release()

            MusicMetadata(
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                albumArt = albumArt
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}