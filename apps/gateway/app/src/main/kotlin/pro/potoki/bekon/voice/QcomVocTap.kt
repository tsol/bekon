package pro.potoki.bekon.voice

import android.util.Log
import pro.potoki.bekon.RootDetector
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.thread

/**
 * Qualcomm in-call tap.
 *
 * Mixing VOC_REC_DL and VOC_REC_UL into the same MultiMedia frontend makes
 * VOICE_DOWNLINK and VOICE_UPLINK AudioRecords identical. Keep DL on MM1 only.
 * Uplink meters use tinycap on VoiceMMode1 (modem TX), not a second AudioRecord.
 */
object QcomVocTap {
    private const val TAG = "QcomVocTap"

    /** ADSP session gain of the MultiMedia1 (pcm 0) inject stream, 0..8192. */
    private const val INJECT_GAIN_CTL = "Playback 0 Volume"
    private const val INJECT_GAIN_UNITY = 8192

    private var refs = 0
    @Volatile var lastHint = "qcom mixer idle"
        private set

    @Synchronized
    fun acquire() {
        refs++
        applyDlOnly()
    }

    @Synchronized
    fun reassert() {
        if (refs > 0) applyDlOnly()
    }

    @Synchronized
    fun release() {
        if (refs <= 0) {
            refs = 0
            return
        }
        if (--refs == 0) applyOff()
    }

    /**
     * Read back what the DSP actually holds, not what we asked for. The HAL
     * reprograms the voice path on route changes and can drop our controls.
     * Blocking `su` call — do not run on the main thread.
     */
    fun readback(): String {
        if (!RootDetector.detect()) return "no root"
        val mix = LineRoute.findTinymix() ?: return "no tinymix"
        val cmd = listOf(LineRoute.dlMixer, "Incall_Music Audio Mixer MultiMedia1", INJECT_GAIN_CTL)
            .joinToString("; ") { "$mix '$it'" }
        return RootDetector.exec(cmd)?.replace("\n", " | ")?.take(300) ?: "readback empty"
    }

    fun setInject(on: Boolean) {
        if (!RootDetector.detect()) return
        val mix = LineRoute.findTinymix() ?: return
        val v = if (on) "1 1" else "0 0"
        RootDetector.exec("$mix 'Incall_Music Audio Mixer MultiMedia1' $v; echo inj")
        if (on) applyInjectGain()
    }

    /**
     * The HAL opens MultiMedia1 with whatever session gain it last used, which
     * on ocean is well below unity and makes injected desktop speech quiet at
     * the far end. Force unity; the stream volume is programmed when the track
     * starts, so callers re-apply this shortly after playback begins.
     */
    fun applyInjectGain() {
        if (!RootDetector.detect()) return
        val mix = LineRoute.findTinymix() ?: return
        RootDetector.exec("$mix '$INJECT_GAIN_CTL' $INJECT_GAIN_UNITY; echo gain")
    }

    /** Debug Probe only. Line/Echo must not copy analog TX onto a MultiMedia frontend. */
    fun setUlTap(on: Boolean) {
        if (!RootDetector.detect()) return
        val mix = LineRoute.findTinymix() ?: return
        val v = if (on) "1 1" else "0 0"
        RootDetector.exec("$mix '${LineRoute.ulMixer}' $v; echo ul")
    }

    private fun applyDlOnly() {
        if (!RootDetector.detect()) {
            lastHint = "qcom mixer: no root"
            return
        }
        val mix = LineRoute.findTinymix() ?: run {
            lastHint = "qcom mixer: no tinymix"
            return
        }
        val ul = LineRoute.ulMixer
        val dl = LineRoute.dlMixer
        // Line/Echo need VOC_REC_DL + Incall only. Do not enable the UL tinycap copy
        // (TERT→MM2): that is debug Probe/Apply UL and can sit on top of the vocoder.
        val cmd = """
            $mix '$dl' 1 1
            $mix 'MultiMedia1 Mixer VOC_REC_UL' 0 0
            $mix 'MultiMedia8 Mixer VOC_REC_DL' 0 0
            $mix 'MultiMedia8 Mixer VOC_REC_UL' 0 0
            $mix '$ul' 0 0
            $mix 'MultiMedia4 Mixer VOC_REC_DL' 0 0
            $mix 'MultiMedia4 Mixer VOC_REC_UL' 0 0
            $mix 'MultiMedia8 Mixer AFE_PCM_TX' 0 0
            echo voc_ok
        """.trimIndent().replace("\n", "; ")
        val out = RootDetector.exec(cmd) ?: ""
        lastHint = "qcom ${LineRoute.hint()} ($out)"
        Log.i(TAG, lastHint.take(300))
    }

    private fun applyOff() {
        if (!RootDetector.detect()) return
        val mix = LineRoute.findTinymix() ?: return
        val zeros = listOf(
            LineRoute.dlMixer,
            LineRoute.ulMixer,
            "MultiMedia1 Mixer VOC_REC_UL",
            "MultiMedia4 Mixer VOC_REC_DL",
            "MultiMedia4 Mixer VOC_REC_UL",
            "MultiMedia8 Mixer VOC_REC_DL",
            "MultiMedia8 Mixer VOC_REC_UL",
            "Incall_Music Audio Mixer MultiMedia1",
        ).distinct()
        RootDetector.exec(zeros.joinToString("; ") { "$mix '$it' 0 0" } + "; echo voc_off")
        lastHint = "qcom VOC_REC off"
    }

    /** tinycap MultiMedia2 (pcm 1) after TERT_MI2S_TX mix — ocean uplink is TERT_MI2S, not VOC_REC_UL. */
    class UplinkCap(
        cacheDir: File,
        private val onPcm: (ByteArray, Int) -> Unit,
    ) {
        private val wav = File(cacheDir, "wlya-ul.wav")
        private var proc: Process? = null
        private var reader: Thread? = null
        @Volatile private var running = false

        fun start() {
            if (running) return
            running = true
            try {
                if (wav.exists()) wav.delete()
                RootDetector.exec("rm -f '${wav.absolutePath}'; mkfifo '${wav.absolutePath}'; chmod 666 '${wav.absolutePath}'; echo fifo")
            } catch (e: Exception) {
                Log.w(TAG, "fifo: ${e.message}")
            }
            reader = thread(name = "gsm-ul-tiny") { readFifo() }
            proc = try {
                Runtime.getRuntime().exec(
                    arrayOf(
                        "su", "-c",
                        "${LineRoute.findTinycap() ?: "/system/bin/tinycap"} '${wav.absolutePath}' -D 0 -d ${LineRoute.ulPcm} -c 1 -r 16000 -b 16 -p 256 -n 4",
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "tinycap: ${e.message}")
                null
            }
        }

        fun stop() {
            running = false
            try {
                proc?.destroy()
            } catch (_: Exception) {
            }
            proc = null
            reader?.join(400)
            reader = null
            RootDetector.exec("rm -f '${wav.absolutePath}'; echo x")
        }

        private fun readFifo() {
            try {
                FileInputStream(wav).use { ins ->
                    val hdr = ByteArray(44)
                    var h = 0
                    while (running && h < 44) {
                        val n = ins.read(hdr, h, 44 - h)
                        if (n <= 0) break
                        h += n
                    }
                    val buf = ByteArray(320)
                    while (running) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        onPcm(buf, n)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ul fifo: ${e.message}")
            }
        }
    }
}
