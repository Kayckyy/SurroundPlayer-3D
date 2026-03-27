package com.sonicsphere.audio.metadata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever

object AlbumArtExtractor {

    data class MusicMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val duration: Long,
        val albumArt: Bitmap?,
        // Metadados técnicos
        val bitrate: String?,
        val sampleRate: String?,
        val channels: String?,
        val mimeType: String?,
        val fileSize: Long?
    )

    fun getAlbumArt(path: String, thumbnailSize: Int = 96): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val artwork = retriever.embeddedPicture
            retriever.release()

            artwork?.let {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(it, 0, it.size, options)

                val scale = maxOf(options.outWidth, options.outHeight) / thumbnailSize
                val finalOptions = BitmapFactory.Options().apply {
                    inSampleSize = maxOf(1, scale)
                }
                BitmapFactory.decodeByteArray(it, 0, it.size, finalOptions)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getMetadata(path: String): MusicMetadata? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L

            // Metadados técnicos
            val bitrateRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val bitrate = bitrateRaw?.toLongOrNull()?.let { bps ->
                when {
                    bps >= 1_000_000 -> "%.0f Mbps".format(bps / 1_000_000.0)
                    else -> "${bps / 1000} kbps"
                }
            }

            val sampleRateRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?: retriever.extractMetadata(26) // METADATA_KEY_SAMPLERATE (API 31+, fallback numérico)
            val sampleRate = sampleRateRaw?.toFloatOrNull()?.toInt()?.let { hz ->
                when {
                    hz >= 1000 -> "${hz / 1000.0}".trimEnd('0').trimEnd('.') + " kHz"
                    else -> "$hz Hz"
                }
            }

            val channelsRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            // Canais via MIME + heurística
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val channels = retriever.extractMetadata(24)?.toIntOrNull()?.let { ch ->
                when (ch) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> "$ch canais"
                }
            }

            // Formato legível pelo MIME type
            val format = when {
                mimeType == null -> null
                mimeType.contains("flac") -> "FLAC"
                mimeType.contains("mp4") || mimeType.contains("aac") -> "AAC"
                mimeType.contains("mpeg") || mimeType.contains("mp3") -> "MP3"
                mimeType.contains("ogg") -> "OGG Vorbis"
                mimeType.contains("opus") -> "Opus"
                mimeType.contains("wav") || mimeType.contains("wave") -> "WAV"
                else -> mimeType.substringAfterLast("/").uppercase()
            }

            // Tamanho do arquivo
            val fileSize = try {
                java.io.File(path).length().takeIf { it > 0 }
            } catch (e: Exception) { null }

            val artwork = retriever.embeddedPicture
            val albumArt = artwork?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }

            retriever.release()

            MusicMetadata(
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                albumArt = albumArt,
                bitrate = bitrate,
                sampleRate = sampleRate,
                channels = channels,
                mimeType = format,
                fileSize = fileSize
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
