package pro.potoki.bekon.call

object VoicePcm {
    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 10
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    const val FRAME_BYTES = FRAME_SAMPLES * 2
    const val PREFIX: Byte = 0xA1.toByte()

    fun recBufBytes(): Int {
        val min = android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        return min.coerceAtLeast(FRAME_BYTES * VoiceLatency.bufMult)
    }

    fun playBufBytes(minPlay: Int): Int = minPlay.coerceAtLeast(FRAME_BYTES * VoiceLatency.bufMult)

    fun encodeFrame(pcm: ByteArray): ByteArray {
        val out = ByteArray(1 + pcm.size)
        out[0] = PREFIX
        System.arraycopy(pcm, 0, out, 1, pcm.size)
        return out
    }

    fun decodeFrame(raw: ByteArray): ByteArray? {
        if (raw.size < 3 || raw[0] != PREFIX) return null
        return raw.copyOfRange(1, raw.size)
    }
}
