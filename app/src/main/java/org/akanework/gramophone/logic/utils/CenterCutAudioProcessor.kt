package org.akanework.gramophone.logic.utils

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CenterCutAudioProcessor : BaseAudioProcessor() {
    enum class Mode(val preferenceValue: String) {
        Disabled("0"),
        CenterCut("1"),
        SideCut("2");

        companion object {
            fun fromPreferenceValue(value: String?): Mode {
                return values().firstOrNull { it.preferenceValue == value } ?: Disabled
            }
        }
    }

    @Volatile
    var mode = Mode.Disabled
        private set

    @Volatile
    var fftMode = false
        private set

    @Volatile
    var fftSize = DEFAULT_FFT_SIZE
        private set

    @Volatile
    var blend = DEFAULT_BLEND
        private set

    val blocksOffload: Boolean
        get() = mode != Mode.Disabled

    private var spectralCenterCut = SpectralCenterCut(DEFAULT_FFT_SIZE)

    fun setMode(mode: Mode): Boolean {
        if (this.mode == mode) {
            return false
        }
        this.mode = mode
        spectralCenterCut.reset()
        return true
    }

    fun setFftMode(enabled: Boolean): Boolean {
        if (fftMode == enabled) {
            return false
        }
        fftMode = enabled
        spectralCenterCut.reset()
        return true
    }

    fun setFftSize(size: Int): Boolean {
        val newSize = if (size in ALLOWED_FFT_SIZES) size else DEFAULT_FFT_SIZE
        if (fftSize == newSize) {
            return false
        }
        fftSize = newSize
        spectralCenterCut = SpectralCenterCut(newSize)
        return true
    }

    fun setBlend(value: Float): Boolean {
        val newBlend = value.coerceIn(0f, 1f)
        if (blend == newBlend) {
            return false
        }
        blend = newBlend
        spectralCenterCut.reset()
        return true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val mode = mode
        if (
            inputAudioFormat.channelCount != 2 ||
            !isSupportedPcm(inputAudioFormat.encoding)
        ) {
            val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        if (mode != Mode.Disabled && fftMode) {
            val outputBuffer = replaceOutputBuffer(
                inputBuffer.remaining() + spectralCenterCut.hopSize * bytesPerFrame()
            )
            processFft(inputBuffer, outputBuffer, mode)
            outputBuffer.flip()
            return
        }

        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, outputBuffer, mode)
            C.ENCODING_PCM_DOUBLE -> processDouble(inputBuffer, outputBuffer, mode)
            else -> processInt(inputBuffer, outputBuffer, mode)
        }
        outputBuffer.flip()
    }

    override fun onQueueEndOfStream() {
        if (
            mode == Mode.Disabled ||
            !fftMode ||
            inputAudioFormat.channelCount != 2 ||
            !isSupportedPcm(inputAudioFormat.encoding)
        ) {
            return
        }

        val framesToEmit = spectralCenterCut.remainingOutputFrames().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (framesToEmit == 0) {
            return
        }
        val outputBuffer = replaceOutputBuffer(framesToEmit * bytesPerFrame())
        spectralCenterCut.queueEnd(
            mode,
            outputBuffer,
            inputAudioFormat.encoding,
            blend.toDouble(),
            ::writePcmDouble
        )
        outputBuffer.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        spectralCenterCut.reset()
    }

    override fun onReset() {
        spectralCenterCut.reset()
    }

    private fun processFft(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, mode: Mode) {
        while (inputBuffer.hasRemaining()) {
            spectralCenterCut.queueInput(
                readPcmDouble(inputBuffer, inputAudioFormat.encoding),
                readPcmDouble(inputBuffer, inputAudioFormat.encoding),
                mode,
                outputBuffer,
                inputAudioFormat.encoding,
                blend.toDouble(),
                ::writePcmDouble
            )
        }
    }

    private fun processInt(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, mode: Mode) {
        while (inputBuffer.hasRemaining()) {
            val left = readPcmInt(inputBuffer, inputAudioFormat.encoding)
            val right = readPcmInt(inputBuffer, inputAudioFormat.encoding)
            when (mode) {
                Mode.CenterCut -> {
                    val newMid = (left + right) / 2L
                    val newSide = (left - right) / 2L
                    val blendedMid = (newMid * blend.toDouble()).roundToLong()
                    writePcmInt(outputBuffer, inputAudioFormat.encoding, blendedMid + newSide)
                    writePcmInt(outputBuffer, inputAudioFormat.encoding, blendedMid - newSide)
                }
                Mode.SideCut -> {
                    val newMid = (left + right) / 2L
                    val newSide = ((left - right) * 0.5 * blend.toDouble()).roundToLong()
                    writePcmInt(outputBuffer, inputAudioFormat.encoding, newMid + newSide)
                    writePcmInt(outputBuffer, inputAudioFormat.encoding, newMid - newSide)
                }
                Mode.Disabled -> {
                    writePcmInt(outputBuffer, inputAudioFormat.encoding, left)
                    writePcmInt(outputBuffer, inputAudioFormat.encoding, right)
                }
            }
        }
    }

    private fun processFloat(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, mode: Mode) {
        while (inputBuffer.hasRemaining()) {
            val left = inputBuffer.getFloat()
            val right = inputBuffer.getFloat()
            when (mode) {
                Mode.CenterCut -> {
                    val newMid = (left + right) * 0.5f * blend
                    val newSide = (left - right) * 0.5f
                    outputBuffer.putFloat(newMid + newSide)
                    outputBuffer.putFloat(newMid - newSide)
                }
                Mode.SideCut -> {
                    val newMid = (left + right) * 0.5f
                    val newSide = (left - right) * 0.5f * blend
                    outputBuffer.putFloat(newMid + newSide)
                    outputBuffer.putFloat(newMid - newSide)
                }
                Mode.Disabled -> {
                    outputBuffer.putFloat(left)
                    outputBuffer.putFloat(right)
                }
            }
        }
    }

    private fun processDouble(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, mode: Mode) {
        while (inputBuffer.hasRemaining()) {
            val left = inputBuffer.getDouble()
            val right = inputBuffer.getDouble()
            when (mode) {
                Mode.CenterCut -> {
                    val newMid = (left + right) * 0.5 * blend.toDouble()
                    val newSide = (left - right) * 0.5
                    outputBuffer.putDouble(newMid + newSide)
                    outputBuffer.putDouble(newMid - newSide)
                }
                Mode.SideCut -> {
                    val newMid = (left + right) * 0.5
                    val newSide = (left - right) * 0.5 * blend.toDouble()
                    outputBuffer.putDouble(newMid + newSide)
                    outputBuffer.putDouble(newMid - newSide)
                }
                Mode.Disabled -> {
                    outputBuffer.putDouble(left)
                    outputBuffer.putDouble(right)
                }
            }
        }
    }

    private fun readPcmInt(inputBuffer: ByteBuffer, encoding: Int): Long {
        return when (encoding) {
            C.ENCODING_PCM_8BIT -> ((inputBuffer.get().toInt() and 0xFF) - 128).toLong()
            C.ENCODING_PCM_16BIT -> readInt16(inputBuffer, littleEndian = true).toLong()
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> readInt16(inputBuffer, littleEndian = false).toLong()
            C.ENCODING_PCM_24BIT -> readInt24(inputBuffer, littleEndian = true).toLong()
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> readInt24(inputBuffer, littleEndian = false).toLong()
            C.ENCODING_PCM_32BIT -> readInt32(inputBuffer, littleEndian = true).toLong()
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> readInt32(inputBuffer, littleEndian = false).toLong()
            else -> throw IllegalStateException("unsupported pcm encoding $encoding")
        }
    }

    private fun writePcmInt(outputBuffer: ByteBuffer, encoding: Int, value: Long) {
        when (encoding) {
            C.ENCODING_PCM_8BIT -> outputBuffer.put((clamp(value, -128L, 127L) + 128L).toByte())
            C.ENCODING_PCM_16BIT -> writeInt16(outputBuffer, value, littleEndian = true)
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> writeInt16(outputBuffer, value, littleEndian = false)
            C.ENCODING_PCM_24BIT -> writeInt24(outputBuffer, value, littleEndian = true)
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> writeInt24(outputBuffer, value, littleEndian = false)
            C.ENCODING_PCM_32BIT -> writeInt32(outputBuffer, value, littleEndian = true)
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> writeInt32(outputBuffer, value, littleEndian = false)
            else -> throw IllegalStateException("unsupported pcm encoding $encoding")
        }
    }

    private fun readPcmDouble(inputBuffer: ByteBuffer, encoding: Int): Double {
        return when (encoding) {
            C.ENCODING_PCM_FLOAT -> inputBuffer.getFloat().toDouble()
            C.ENCODING_PCM_DOUBLE -> inputBuffer.getDouble()
            C.ENCODING_PCM_8BIT,
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                val value = readPcmInt(inputBuffer, encoding)
                val scale = when (encoding) {
                    C.ENCODING_PCM_8BIT -> 128.0
                    C.ENCODING_PCM_16BIT,
                    C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 32768.0
                    C.ENCODING_PCM_24BIT,
                    C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 8388608.0
                    C.ENCODING_PCM_32BIT,
                    C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 2147483648.0
                    else -> throw IllegalStateException("unsupported pcm encoding $encoding")
                }
                value / scale
            }
            else -> throw IllegalStateException("unsupported pcm encoding $encoding")
        }
    }

    private fun writePcmDouble(outputBuffer: ByteBuffer, encoding: Int, value: Double) {
        val clamped = value.coerceIn(-1.0, 1.0)
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> outputBuffer.putFloat(clamped.toFloat())
            C.ENCODING_PCM_DOUBLE -> outputBuffer.putDouble(clamped)
            C.ENCODING_PCM_8BIT -> writePcmInt(outputBuffer, encoding, (clamped * 128.0).roundToLong())
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> {
                writePcmInt(outputBuffer, encoding, (clamped * 32768.0).roundToLong())
            }
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                writePcmInt(outputBuffer, encoding, (clamped * 8388608.0).roundToLong())
            }
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                writePcmInt(outputBuffer, encoding, (clamped * 2147483648.0).roundToLong())
            }
            else -> throw IllegalStateException("unsupported pcm encoding $encoding")
        }
    }

    private fun readInt16(inputBuffer: ByteBuffer, littleEndian: Boolean): Int {
        val first = inputBuffer.get().toInt() and 0xFF
        val second = inputBuffer.get().toInt() and 0xFF
        val value = if (littleEndian) first or (second shl 8) else (first shl 8) or second
        return value.toShort().toInt()
    }

    private fun writeInt16(outputBuffer: ByteBuffer, value: Long, littleEndian: Boolean) {
        val clamped = clamp(value, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
        if (littleEndian) {
            outputBuffer.put((clamped and 0xFF).toByte())
            outputBuffer.put(((clamped shr 8) and 0xFF).toByte())
        } else {
            outputBuffer.put(((clamped shr 8) and 0xFF).toByte())
            outputBuffer.put((clamped and 0xFF).toByte())
        }
    }

    private fun readInt24(inputBuffer: ByteBuffer, littleEndian: Boolean): Int {
        val first = inputBuffer.get().toInt() and 0xFF
        val second = inputBuffer.get().toInt() and 0xFF
        val third = inputBuffer.get().toInt() and 0xFF
        val value = if (littleEndian) {
            first or (second shl 8) or (third shl 16)
        } else {
            (first shl 16) or (second shl 8) or third
        }
        return value shl 8 shr 8
    }

    private fun writeInt24(outputBuffer: ByteBuffer, value: Long, littleEndian: Boolean) {
        val clamped = clamp(value, -8_388_608L, 8_388_607L).toInt()
        if (littleEndian) {
            outputBuffer.put((clamped and 0xFF).toByte())
            outputBuffer.put(((clamped shr 8) and 0xFF).toByte())
            outputBuffer.put(((clamped shr 16) and 0xFF).toByte())
        } else {
            outputBuffer.put(((clamped shr 16) and 0xFF).toByte())
            outputBuffer.put(((clamped shr 8) and 0xFF).toByte())
            outputBuffer.put((clamped and 0xFF).toByte())
        }
    }

    private fun readInt32(inputBuffer: ByteBuffer, littleEndian: Boolean): Int {
        val first = inputBuffer.get().toInt() and 0xFF
        val second = inputBuffer.get().toInt() and 0xFF
        val third = inputBuffer.get().toInt() and 0xFF
        val fourth = inputBuffer.get().toInt() and 0xFF
        return if (littleEndian) {
            first or (second shl 8) or (third shl 16) or (fourth shl 24)
        } else {
            (first shl 24) or (second shl 16) or (third shl 8) or fourth
        }
    }

    private fun writeInt32(outputBuffer: ByteBuffer, value: Long, littleEndian: Boolean) {
        val clamped = clamp(value, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        if (littleEndian) {
            outputBuffer.put((clamped and 0xFF).toByte())
            outputBuffer.put(((clamped shr 8) and 0xFF).toByte())
            outputBuffer.put(((clamped shr 16) and 0xFF).toByte())
            outputBuffer.put(((clamped shr 24) and 0xFF).toByte())
        } else {
            outputBuffer.put(((clamped shr 24) and 0xFF).toByte())
            outputBuffer.put(((clamped shr 16) and 0xFF).toByte())
            outputBuffer.put(((clamped shr 8) and 0xFF).toByte())
            outputBuffer.put((clamped and 0xFF).toByte())
        }
    }

    private fun clamp(value: Long, minValue: Long, maxValue: Long): Long {
        return min(max(value, minValue), maxValue)
    }

    private fun isSupportedPcm(encoding: Int): Boolean {
        return when (encoding) {
            C.ENCODING_PCM_8BIT,
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN,
            C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_DOUBLE -> true
            else -> false
        }
    }

    private fun bytesPerFrame(): Int {
        return inputAudioFormat.channelCount * when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN,
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_DOUBLE -> 8
            else -> throw IllegalStateException("unsupported pcm encoding ${inputAudioFormat.encoding}")
        }
    }

    companion object {
        const val DEFAULT_FFT_SIZE = 2048
        const val DEFAULT_BLEND = 0f
        val ALLOWED_FFT_SIZES = intArrayOf(1024, 2048, 4096)
    }

    private class SpectralCenterCut(private val fftSize: Int) {
        val hopSize = fftSize / 2
        private val window = DoubleArray(fftSize) { index ->
            sqrt(0.5 - 0.5 * cos(2.0 * PI * index / fftSize))
        }
        private val frameLeft = DoubleArray(fftSize)
        private val frameRight = DoubleArray(fftSize)
        private val fftLeftReal = DoubleArray(fftSize)
        private val fftLeftImag = DoubleArray(fftSize)
        private val fftRightReal = DoubleArray(fftSize)
        private val fftRightImag = DoubleArray(fftSize)
        private val outputLeft = DoubleArray(fftSize)
        private val outputRight = DoubleArray(fftSize)
        private var frameFill = fftSize - hopSize
        private var queuedFrames = 0L
        private var emittedFrames = 0L
        private var skipInitialOutput = true

        fun queueInput(
            left: Double,
            right: Double,
            mode: Mode,
            outputBuffer: ByteBuffer,
            encoding: Int,
            blend: Double,
            write: (ByteBuffer, Int, Double) -> Unit
        ) {
            queueSample(left, right, countInput = true, mode, outputBuffer, encoding, blend, write)
        }

        fun queueEnd(
            mode: Mode,
            outputBuffer: ByteBuffer,
            encoding: Int,
            blend: Double,
            write: (ByteBuffer, Int, Double) -> Unit
        ) {
            while (emittedFrames < queuedFrames) {
                queueSample(0.0, 0.0, countInput = false, mode, outputBuffer, encoding, blend, write)
            }
        }

        fun remainingOutputFrames(): Long {
            return queuedFrames - emittedFrames
        }

        fun reset() {
            Arrays.fill(frameLeft, 0.0)
            Arrays.fill(frameRight, 0.0)
            Arrays.fill(outputLeft, 0.0)
            Arrays.fill(outputRight, 0.0)
            frameFill = fftSize - hopSize
            queuedFrames = 0L
            emittedFrames = 0L
            skipInitialOutput = true
        }

        private fun queueSample(
            left: Double,
            right: Double,
            countInput: Boolean,
            mode: Mode,
            outputBuffer: ByteBuffer,
            encoding: Int,
            blend: Double,
            write: (ByteBuffer, Int, Double) -> Unit
        ) {
            if (countInput) {
                queuedFrames++
            }
            frameLeft[frameFill] = left
            frameRight[frameFill] = right
            frameFill++
            if (frameFill == fftSize) {
                processFrame(mode, outputBuffer, encoding, blend, write)
                shiftFrame()
            }
        }

        private fun processFrame(
            mode: Mode,
            outputBuffer: ByteBuffer,
            encoding: Int,
            blend: Double,
            write: (ByteBuffer, Int, Double) -> Unit
        ) {
            for (index in 0 until fftSize) {
                fftLeftReal[index] = frameLeft[index] * window[index]
                fftLeftImag[index] = 0.0
                fftRightReal[index] = frameRight[index] * window[index]
                fftRightImag[index] = 0.0
            }

            fft(fftLeftReal, fftLeftImag, inverse = false)
            fft(fftRightReal, fftRightImag, inverse = false)
            processSpectrum(mode, blend)
            fft(fftLeftReal, fftLeftImag, inverse = true)
            fft(fftRightReal, fftRightImag, inverse = true)

            for (index in 0 until fftSize) {
                outputLeft[index] += fftLeftReal[index] * window[index]
                outputRight[index] += fftRightReal[index] * window[index]
            }

            if (skipInitialOutput) {
                skipInitialOutput = false
            } else {
                val framesToEmit = min(hopSize.toLong(), queuedFrames - emittedFrames).toInt()
                for (index in 0 until framesToEmit) {
                    write(outputBuffer, encoding, outputLeft[index])
                    write(outputBuffer, encoding, outputRight[index])
                }
                emittedFrames += framesToEmit
            }
            shiftOutput()
        }

        private fun processSpectrum(mode: Mode, blend: Double) {
            for (index in 0 until fftSize) {
                val leftReal = fftLeftReal[index]
                val leftImag = fftLeftImag[index]
                val rightReal = fftRightReal[index]
                val rightImag = fftRightImag[index]

                var midReal = (leftReal + rightReal) * 0.5
                var midImag = (leftImag + rightImag) * 0.5
                var sideReal = (leftReal - rightReal) * 0.5
                var sideImag = (leftImag - rightImag) * 0.5

                when (mode) {
                    Mode.CenterCut -> {
                        val centerWeight = centerWeight(leftReal, leftImag, rightReal, rightImag)
                        val midGain = 1.0 - centerWeight * (1.0 - blend)
                        midReal *= midGain
                        midImag *= midGain
                    }
                    Mode.SideCut -> {
                        sideReal *= blend
                        sideImag *= blend
                    }
                    Mode.Disabled -> Unit
                }

                fftLeftReal[index] = midReal + sideReal
                fftLeftImag[index] = midImag + sideImag
                fftRightReal[index] = midReal - sideReal
                fftRightImag[index] = midImag - sideImag
            }
        }

        private fun centerWeight(
            leftReal: Double,
            leftImag: Double,
            rightReal: Double,
            rightImag: Double
        ): Double {
            val leftMagnitude = sqrt(leftReal * leftReal + leftImag * leftImag)
            val rightMagnitude = sqrt(rightReal * rightReal + rightImag * rightImag)
            if (leftMagnitude <= MIN_MAGNITUDE || rightMagnitude <= MIN_MAGNITUDE) {
                return 0.0
            }

            val phaseSimilarity = (
                (leftReal * rightReal + leftImag * rightImag) /
                    (leftMagnitude * rightMagnitude)
                ).coerceIn(0.0, 1.0)
            val levelSimilarity = min(leftMagnitude, rightMagnitude) / max(leftMagnitude, rightMagnitude)
            return (phaseSimilarity * levelSimilarity).coerceIn(0.0, 1.0)
        }

        private fun shiftFrame() {
            System.arraycopy(frameLeft, hopSize, frameLeft, 0, fftSize - hopSize)
            System.arraycopy(frameRight, hopSize, frameRight, 0, fftSize - hopSize)
            Arrays.fill(frameLeft, fftSize - hopSize, fftSize, 0.0)
            Arrays.fill(frameRight, fftSize - hopSize, fftSize, 0.0)
            frameFill = fftSize - hopSize
        }

        private fun shiftOutput() {
            System.arraycopy(outputLeft, hopSize, outputLeft, 0, fftSize - hopSize)
            System.arraycopy(outputRight, hopSize, outputRight, 0, fftSize - hopSize)
            Arrays.fill(outputLeft, fftSize - hopSize, fftSize, 0.0)
            Arrays.fill(outputRight, fftSize - hopSize, fftSize, 0.0)
        }

        private fun fft(real: DoubleArray, imag: DoubleArray, inverse: Boolean) {
            var j = 0
            for (i in 1 until fftSize) {
                var bit = fftSize shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val realTemp = real[i]
                    real[i] = real[j]
                    real[j] = realTemp
                    val imagTemp = imag[i]
                    imag[i] = imag[j]
                    imag[j] = imagTemp
                }
            }

            var length = 2
            while (length <= fftSize) {
                val angle = 2.0 * PI / length * if (inverse) 1.0 else -1.0
                val wLengthReal = cos(angle)
                val wLengthImag = sin(angle)
                var i = 0
                while (i < fftSize) {
                    var wReal = 1.0
                    var wImag = 0.0
                    for (k in 0 until length / 2) {
                        val evenIndex = i + k
                        val oddIndex = evenIndex + length / 2
                        val oddReal = real[oddIndex] * wReal - imag[oddIndex] * wImag
                        val oddImag = real[oddIndex] * wImag + imag[oddIndex] * wReal
                        real[oddIndex] = real[evenIndex] - oddReal
                        imag[oddIndex] = imag[evenIndex] - oddImag
                        real[evenIndex] += oddReal
                        imag[evenIndex] += oddImag

                        val nextWReal = wReal * wLengthReal - wImag * wLengthImag
                        wImag = wReal * wLengthImag + wImag * wLengthReal
                        wReal = nextWReal
                    }
                    i += length
                }
                length = length shl 1
            }

            if (inverse) {
                for (i in 0 until fftSize) {
                    real[i] /= fftSize
                    imag[i] /= fftSize
                }
            }
        }

        companion object {
            private const val MIN_MAGNITUDE = 1.0e-12
        }
    }
}
