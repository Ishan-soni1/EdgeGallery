package com.edgegallery.app.processing

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * Extracts a feature-vector embedding from a bitmap using a TensorFlow Lite model.
 *
 * The model is loaded once and reused for every image. Call [close] when the
 * extractor is no longer needed to release native memory.
 */
class EmbeddingExtractor(context: Context) : Closeable {

    private val interpreter: Interpreter
    private val inputWidth: Int
    private val inputHeight: Int
    private val embeddingDimension: Int

    init {
        val modelBuffer = try {
            context.assets.open(MODEL_FILENAME).use { stream ->
                val bytes = stream.readBytes()
                ByteBuffer.allocateDirect(bytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(bytes)
                    rewind()
                }
            }
        } catch (error: IOException) {
            throw IllegalStateException(
                "TFLite model '$MODEL_FILENAME' not found in assets. " +
                    "See assets/MODEL_README.md for download instructions.",
                error,
            )
        }

        val options = Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        interpreter = Interpreter(modelBuffer, options)

        // Auto-detect input shape [1, height, width, 3].
        val inputShape = interpreter.getInputTensor(0).shape()
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]

        // Auto-detect output embedding dimension [1, D].
        val outputShape = interpreter.getOutputTensor(0).shape()
        embeddingDimension = outputShape[1]
    }

    /** Returns the size of the embedding vector produced by the model. */
    fun dimension(): Int = embeddingDimension

    /**
     * Runs inference on [bitmap] and returns a feature-vector embedding.
     *
     * The bitmap is scaled to the model's expected input size and normalised
     * to the \[0, 1\] range. The caller is responsible for recycling the bitmap.
     */
    fun extract(bitmap: Bitmap): FloatArray {
        val scaled = if (bitmap.width == inputWidth && bitmap.height == inputHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        }

        try {
            val inputBuffer = bitmapToByteBuffer(scaled)
            val outputBuffer = Array(1) { FloatArray(embeddingDimension) }

            interpreter.run(inputBuffer, outputBuffer)

            return outputBuffer[0]
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    override fun close() {
        interpreter.close()
    }

    /**
     * Converts an ARGB bitmap into a direct [ByteBuffer] of RGB floats
     * normalised to \[0, 1\] as expected by MobileNet models.
     */
    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val bufferSize = 1 * inputHeight * inputWidth * CHANNELS * FLOAT_BYTES
        val buffer = ByteBuffer.allocateDirect(bufferSize).apply {
            order(ByteOrder.nativeOrder())
        }

        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixel in pixels) {
            // Extract RGB channels and normalise from [0, 255] to [0.0, 1.0].
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }

        buffer.rewind()
        return buffer
    }

    private companion object {
        const val MODEL_FILENAME = "mobilenet_v3_small_feature_vector.tflite"
        const val CHANNELS = 3
        const val FLOAT_BYTES = 4
    }
}
