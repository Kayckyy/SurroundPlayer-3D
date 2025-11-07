package com.example.musicplayer

import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat

object AlbumArtExtractor {

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
}