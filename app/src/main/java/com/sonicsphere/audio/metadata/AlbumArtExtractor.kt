package com.sonicsphere.audio.metadata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile

object AlbumArtExtractor {

    data class MusicMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val duration: Long,
        val albumArt: Bitmap?,
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
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, options)
                val scale = maxOf(options.outWidth, options.outHeight) / thumbnailSize
                val finalOptions = BitmapFactory.Options().apply { inSampleSize = maxOf(1, scale) }
                BitmapFactory.decodeByteArray(it, 0, it.size, finalOptions)
            }
        } catch (e: Exception) {
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
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val bitrateRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
            val bitrate = bitrateRaw?.let { "${it / 1000} kbps" }

            val mimeRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val mimeType = when {
                mimeRaw == null -> null
                mimeRaw.contains("flac") -> "FLAC"
                mimeRaw.contains("mp4") || mimeRaw.contains("aac") -> "AAC"
                mimeRaw.contains("mpeg") || mimeRaw.contains("mp3") -> "MP3"
                mimeRaw.contains("ogg") -> "OGG"
                mimeRaw.contains("opus") -> "Opus"
                mimeRaw.contains("wav") || mimeRaw.contains("wave") -> "WAV"
                else -> mimeRaw.substringAfterLast("/").uppercase()
            }

            // Thumbnail já escalado para evitar OOM
            val artwork = retriever.embeddedPicture
            val albumArt = artwork?.let {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, opts)
                val scale = maxOf(opts.outWidth, opts.outHeight) / 96
                val finalOpts = BitmapFactory.Options().apply { inSampleSize = maxOf(1, scale) }
                BitmapFactory.decodeByteArray(it, 0, it.size, finalOpts)
            }

            retriever.release()

            val fileSize = try { File(path).length().takeIf { it > 0 } } catch (e: Exception) { null }

            val (sampleRate, channels) = readAudioHeader(path, mimeType)

            MusicMetadata(
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                albumArt = albumArt,
                bitrate = bitrate,
                sampleRate = sampleRate,
                channels = channels,
                mimeType = mimeType,
                fileSize = fileSize
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun readAudioHeader(path: String, mimeType: String?): Pair<String?, String?> {
        return try {
            when (mimeType) {
                "FLAC" -> readFlacHeader(path)
                "WAV" -> readWavHeader(path)
                "MP3" -> readMp3Header(path)
                else -> Pair(null, null)
            }
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    private fun readFlacHeader(path: String): Pair<String?, String?> {
        val raf = RandomAccessFile(path, "r")
        return try {
            val magic = ByteArray(4)
            raf.read(magic)
            if (String(magic) != "fLaC") return Pair(null, null)

            raf.skipBytes(4)

            val info = ByteArray(8)
            raf.read(info)

            val sampleRateRaw = ((info[0].toInt() and 0xFF) shl 12) or
                    ((info[1].toInt() and 0xFF) shl 4) or
                    ((info[2].toInt() and 0xFF) shr 4)

            val channelsRaw = ((info[2].toInt() and 0xFF) shr 1) and 0x07
            val channelCount = channelsRaw + 1

            Pair(formatSampleRate(sampleRateRaw), formatChannels(channelCount))
        } finally {
            raf.close()
        }
    }

    private fun readWavHeader(path: String): Pair<String?, String?> {
        val raf = RandomAccessFile(path, "r")
        return try {
            val header = ByteArray(28)
            raf.read(header)

            if (String(header.sliceArray(0..3)) != "RIFF") return Pair(null, null)
            if (String(header.sliceArray(8..11)) != "WAVE") return Pair(null, null)

            val channelCount = ((header[23].toInt() and 0xFF) shl 8) or (header[22].toInt() and 0xFF)
            val sampleRateRaw = (header[24].toInt() and 0xFF) or
                    ((header[25].toInt() and 0xFF) shl 8) or
                    ((header[26].toInt() and 0xFF) shl 16) or
                    ((header[27].toInt() and 0xFF) shl 24)

            Pair(formatSampleRate(sampleRateRaw), formatChannels(channelCount))
        } finally {
            raf.close()
        }
    }

    private fun readMp3Header(path: String): Pair<String?, String?> {
        val raf = RandomAccessFile(path, "r")
        return try {
            val buf = ByteArray(minOf(10240, raf.length().toInt()))
            raf.read(buf)

            var i = 0
            while (i < buf.size - 3) {
                if (i == 0 && buf[0] == 'I'.code.toByte() &&
                    buf[1] == 'D'.code.toByte() && buf[2] == '3'.code.toByte()) {
                    val id3Size = ((buf[6].toInt() and 0x7F) shl 21) or
                            ((buf[7].toInt() and 0x7F) shl 14) or
                            ((buf[8].toInt() and 0x7F) shl 7) or
                            (buf[9].toInt() and 0x7F)
                    i = id3Size + 10
                    continue
                }

                if ((buf[i].toInt() and 0xFF) == 0xFF && (buf[i + 1].toInt() and 0xE0) == 0xE0) {
                    val b2 = buf[i + 2].toInt() and 0xFF
                    val b3 = buf[i + 3].toInt() and 0xFF

                    val srIndex = (b2 shr 2) and 0x03
                    val channelMode = (b3 shr 6) and 0x03

                    val sampleRateRaw = when (srIndex) {
                        0 -> 44100
                        1 -> 48000
                        2 -> 32000
                        else -> 0
                    }

                    val channelCount = if (channelMode == 3) 1 else 2

                    if (sampleRateRaw > 0) {
                        return Pair(formatSampleRate(sampleRateRaw), formatChannels(channelCount))
                    }
                }
                i++
            }
            Pair(null, null)
        } finally {
            raf.close()
        }
    }

    private fun formatSampleRate(hz: Int): String? {
        if (hz <= 0) return null
        return if (hz % 1000 == 0) "${hz / 1000} kHz" else "${hz / 1000.0} kHz"
    }

    private fun formatChannels(count: Int): String? {
        return when (count) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> if (count > 0) "$count ch" else null
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
