package com.edgegallery.app.processing

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Extracts a feature-vector embedding from a bitmap using a TensorFlow Lite model.
 *
 * The model is loaded once and reused for every image. Call [close] when the
 * extractor is no longer needed to release native memory.
 */
class EmbeddingExtractor(context: Context) : Closeable {

    private val modelBuffer: ByteBuffer
    private val interpreter: Interpreter
    private val inputWidth: Int
    private val inputHeight: Int
    private val embeddingDimension: Int
    private val inputBuffer: ByteBuffer
    private val inputPixels: IntArray
    private val outputBuffer: Array<FloatArray>

    init {
        modelBuffer = try {
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
        val createdInterpreter = Interpreter(modelBuffer, options)

        try {
            val inputTensor = createdInterpreter.getInputTensor(0)
            val inputShape = inputTensor.shape()
            require(inputTensor.dataType() == DataType.FLOAT32) {
                "MobileNet input must be FLOAT32, but was ${inputTensor.dataType()}"
            }
            require(
                inputShape.size == 4 &&
                    inputShape[0] == 1 &&
                    inputShape[1] > 0 &&
                    inputShape[2] > 0 &&
                    inputShape[3] == CHANNELS
            ) {
                "MobileNet input must have shape [1, height, width, 3]"
            }

            val outputTensor = createdInterpreter.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            require(outputTensor.dataType() == DataType.FLOAT32) {
                "MobileNet output must be FLOAT32, but was ${outputTensor.dataType()}"
            }
            require(outputShape.size == 2 && outputShape[0] == 1 && outputShape[1] > 0) {
                "MobileNet output must have shape [1, embedding dimension]"
            }

            inputHeight = inputShape[1]
            inputWidth = inputShape[2]
            embeddingDimension = outputShape[1]
        } catch (error: Exception) {
            createdInterpreter.close()
            throw IllegalStateException("The bundled MobileNet model is incompatible", error)
        }

        interpreter = createdInterpreter
        inputBuffer = ByteBuffer.allocateDirect(
            inputHeight * inputWidth * CHANNELS * FLOAT_BYTES,
        ).apply { order(ByteOrder.nativeOrder()) }
        inputPixels = IntArray(inputWidth * inputHeight)
        outputBuffer = Array(1) { FloatArray(embeddingDimension) }
    }

    /** Returns the size of the embedding vector produced by the model. */
    fun dimension(): Int = embeddingDimension

    /**
     * Runs inference on [bitmap] and returns a feature-vector embedding.
     *
     * The bitmap is scaled to the model's expected input size and normalised
     * to the \[0, 1\] range. The caller is responsible for recycling the bitmap.
     */
    @Synchronized
    fun extract(bitmap: Bitmap): FloatArray {
        val scaled = if (bitmap.width == inputWidth && bitmap.height == inputHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        }

        try {
            bitmapToByteBuffer(scaled)
            outputBuffer[0].fill(0.0f)
            interpreter.run(inputBuffer, outputBuffer)

            return normalise(outputBuffer[0])
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
    private fun bitmapToByteBuffer(bitmap: Bitmap) {
        inputBuffer.clear()
        bitmap.getPixels(inputPixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixel in inputPixels) {
            // Extract RGB channels and normalise from [0, 255] to [0.0, 1.0].
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }

        inputBuffer.rewind()
    }

    /** Returns an owned, unit-length copy suitable for cosine comparison. */
    private fun normalise(values: FloatArray): FloatArray {
        var squaredMagnitude = 0.0
        for (value in values) {
            require(value.isFinite()) { "MobileNet produced a non-finite embedding" }
            squaredMagnitude += value.toDouble() * value.toDouble()
        }

        val result = values.copyOf()
        if (squaredMagnitude == 0.0) return result

        val magnitude = kotlin.math.sqrt(squaredMagnitude).toFloat()
        for (index in result.indices) {
            result[index] /= magnitude
        }
        return result
    }

    private companion object {
        const val MODEL_FILENAME = "mobilenet_v3_small_feature_vector.tflite"
        const val CHANNELS = 3
        const val FLOAT_BYTES = 4
    }
}
