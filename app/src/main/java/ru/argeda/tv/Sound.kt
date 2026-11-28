package ru.argeda.tv

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp

/**
 * Обработка звука перед выводом.
 * «Ровная громкость» медленно подтягивает тихие каналы и приглушает громкие, чтобы при
 * переключении звук не скакал. «Ночной» работает быстрее: глушит взрывы и музыку и
 * подтягивает тихую речь. «Обычный» пропускает звук как есть.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class Sound : BaseAudioProcessor() {
    private var rate = 48_000
    private var chans = 2
    private var env = 0f
    private var gain = 1f
    private var lastMode = -1

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount <= 0)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        rate = inputAudioFormat.sampleRate
        chans = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun onFlush() {
        env = 0f
        gain = 1f
    }

    override fun onReset() {
        env = 0f
        gain = 1f
        lastMode = -1
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val m = mode
        if (m == OFF) {
            out.put(inputBuffer)
            out.flip()
            lastMode = m
            return
        }
        if (m != lastMode) {
            gain = 1f
            lastMode = m
        }
        val night = m == NIGHT
        // Постоянные времени, с: огибающая уровня, рост и спад усиления.
        val envT = if (night) 0.05f else 0.4f
        val upT = if (night) 0.6f else 3f
        val downT = if (night) 0.02f else 0.3f
        val target = if (night) 0.3f else 0.4f
        val maxGain = if (night) 5f else 3.2f
        val minGain = if (night) 0.15f else 0.35f
        val aEnv = coef(envT)
        val aUp = coef(upT)
        val aDown = coef(downT)
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        out.order(ByteOrder.nativeOrder())
        val frames = n / (2 * chans)
        val frame = FloatArray(chans)
        for (f in 0 until frames) {
            var peak = 0f
            for (ch in 0 until chans) {
                val v = inp.short / 32768f
                frame[ch] = v
                val a = abs(v)
                if (a > peak) peak = a
            }
            env += (peak - env) * (if (peak > env) aEnv * 4f else aEnv).coerceAtMost(1f)
            // В тишине усиление не растёт, иначе шум между фразами станет громким.
            if (env > 0.004f) {
                val want = (target / env).coerceIn(minGain, maxGain)
                gain += (want - gain) * (if (want < gain) aDown else aUp)
            }
            for (ch in 0 until chans) out.putShort((limit(frame[ch] * gain) * 32767f).toInt().toShort())
        }
        // Хвост, если вдруг пришёл неполный кадр.
        while (inp.hasRemaining()) out.put(inp.get())
        out.flip()
    }

    private fun coef(seconds: Float): Float = (1.0 - exp(-1.0 / (seconds * rate))).toFloat()

    companion object {
        const val OFF = 0
        const val LEVEL = 1
        const val NIGHT = 2
        @JvmField val NAMES = arrayOf("Обычный", "Ровная громкость", "Ночной")
        const val PREF = "sound"

        @Volatile @JvmField var mode = OFF

        /** Мягкое ограничение: громкие места не хрипят. */
        private fun limit(x: Float): Float {
            val a = abs(x)
            if (a <= 0.8f) return x
            val y = 0.8f + 0.2f * (1f - exp(-(a - 0.8f) / 0.2f))
            return if (x < 0) -y else y
        }
    }
}
