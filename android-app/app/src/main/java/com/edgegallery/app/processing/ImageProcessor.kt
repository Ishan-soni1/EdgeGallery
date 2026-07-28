package com.edgegallery.app.processing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import com.edgegallery.app.model.ImageFeatures
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads one selected URI and calculates all features needed by the MVP. */
class ImageProcessor(
    private val contentResolver: ContentResolver,
    private val embeddingExtractor: EmbeddingExtractor,
) {

    suspend fun analyze(uri: Uri): ImageFeatures = withContext(Dispatchers.IO) {
        val displayName = findDisplayName(uri)
        val fileSize = findFileSize(uri)
        val (imageWidth, imageHeight) = findDimensions(uri)
        val sha256 = calculateSha256(uri)

        // Decode once, then derive the smaller dHash and exposure inputs from
        // the same orientation-corrected bitmap.
        val thumbnail = decodeThumbnail(uri, EMBEDDING_THUMBNAIL_SIZE)
        try {
            val exposureLuminance = readScaledLuminance(
                thumbnail,
                EXPOSURE_THUMBNAIL_SIZE,
                EXPOSURE_THUMBNAIL_SIZE,
            )
            val differenceHashLuminance = readScaledLuminance(
                thumbnail,
                ImageMath.DIFFERENCE_HASH_WIDTH,
                ImageMath.DIFFERENCE_HASH_HEIGHT,
            )

            ImageFeatures(
                id = uri.toString(),
                uri = uri,
                displayName = displayName,
                sha256 = sha256,
                differenceHash = ImageMath.calculateDifferenceHash(differenceHashLuminance),
                embedding = embeddingExtractor.extract(thumbnail),
                exposure = ImageMath.analyzeExposure(exposureLuminance),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                fileSize = fileSize,
            )
        } finally {
            thumbnail.recycle()
        }
    }

    /** Streams bytes into SHA-256 instead of loading a complete photo into memory. */
    private fun calculateSha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("The selected image could not be opened")

        input.use { stream ->
            while (true) {
                val bytesRead = stream.read(buffer)
                if (bytesRead == -1) break
                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    /**
     * ImageDecoder handles content URIs and EXIF orientation. A software bitmap
     * at the requested size is enough for feature extraction.
     */
    private fun decodeThumbnail(uri: Uri, size: Int): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(size, size)
        }
    }

    /** Converts Android ARGB pixels into conventional 0..255 luminance values. */
    private fun readLuminance(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        return IntArray(pixels.size) { index ->
            val pixel = pixels[index]
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            (0.299 * red + 0.587 * green + 0.114 * blue).toInt()
        }
    }

    private fun readScaledLuminance(bitmap: Bitmap, width: Int, height: Int): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return try {
            readLuminance(scaled)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun findDisplayName(uri: Uri): String {
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return uri.lastPathSegment ?: "Unnamed image"
    }

    private fun findFileSize(uri: Uri): Long {
        val columns = arrayOf(OpenableColumns.SIZE)
        contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    return cursor.getLong(sizeIndex)
                }
            }
        }
        return 0L
    }

    private fun findDimensions(uri: Uri): Pair<Int, Int> {
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, options)
            }
            Pair(options.outWidth.coerceAtLeast(0), options.outHeight.coerceAtLeast(0))
        } catch (_: Exception) {
            Pair(0, 0)
        }
    }

    private companion object {
        const val EXPOSURE_THUMBNAIL_SIZE = 64
        const val EMBEDDING_THUMBNAIL_SIZE = 224
    }
}
