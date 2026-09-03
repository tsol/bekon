package pro.potoki.bekon.voice

import pro.potoki.bekon.call.VoicePcm

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WavPcm16 {
    fun writeHeader(raf: RandomAccessFile, dataLen: Long) {
        val rate = VoicePcm.SAMPLE_RATE
        val channels = 1
        val bits = 16
        val byteRate = rate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val data = dataLen.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val riff = 36 + data
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray())
        hdr.putInt(riff)
        hdr.put("WAVE".toByteArray())
        hdr.put("fmt ".toByteArray())
        hdr.putInt(16)
        hdr.putShort(1)
        hdr.putShort(channels.toShort())
        hdr.putInt(rate)
        hdr.putInt(byteRate)
        hdr.putShort(blockAlign.toShort())
        hdr.putShort(bits.toShort())
        hdr.put("data".toByteArray())
        hdr.putInt(data)
        raf.seek(0)
        raf.write(hdr.array())
    }
}
