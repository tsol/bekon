package pro.potoki.bekon.voice

import android.content.Context
import android.util.Log
import pro.potoki.bekon.RootDetector
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Vendor-agnostic GSM line recon for rooted phones.
 *
 * Do not tinycap HAL-owned Voice/VoiceMMode PCMs (0 frames, or exclusive).
 * Read VoiceMMode*_Tx Mixer * = On → that widget is the mic/TX bus.
 * Copy it into a **closed** MultiMedia capture and tinycap that PCM (ocean: TERT_MI2S → MM2).
 * Downlink: VOC_REC_DL → MM1 + AudioRecord(VOICE_DOWNLINK), not tinycap on the voice DSP.
 */
object LineRouteMap {
    private const val TAG = "LineRouteMap"

    @Volatile var text = "Scan routes during a call (root + tinymix)."
        private set
    @Volatile var busy = false
        private set
    @Volatile var lastBest: ProbeHit? = null
        private set

    data class ProbeHit(val mixer: String, val pcm: Int, val peak: Int, val rms: Double)

    private data class PcmNode(val id: String, val dev: Int, val capture: Boolean, val name: String)

    fun scanAsync() {
        if (busy) return
        busy = true
        text = "Scanning mixer + PCM…"
        thread(name = "line-scan") {
            try {
                text = buildScan()
            } catch (e: Exception) {
                text = "scan failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun probeUlAsync(ctx: Context) {
        if (busy) return
        busy = true
        text = "Probing UL: speak into the phone (1s per candidate)…"
        thread(name = "line-probe") {
            try {
                GsmLevelProbe.stop()
                text = runProbe()
                GsmLevelProbe.sync(ctx)
            } catch (e: Exception) {
                text = "probe failed: ${e.message}"
                GsmLevelProbe.sync(ctx)
            } finally {
                busy = false
            }
        }
    }

    fun applyBest(ctx: Context): Boolean {
        val hit = lastBest ?: return false
        LineRoute.ulMixer = hit.mixer
        LineRoute.ulPcm = hit.pcm
        LineRoute.save(VoicePrefs(ctx))
        GsmLevelProbe.stop()
        GsmLevelProbe.sync(ctx)
        return true
    }

    private fun buildScan(): String {
        if (!RootDetector.detect()) return "Need Magisk/root for mixer + /proc/asound."
        val mix = LineRoute.findTinymix()
        val cap = LineRoute.findTinycap()
        val pcmTxt = RootDetector.exec("cat /proc/asound/pcm") ?: ""
        val nodes = parsePcm(pcmTxt)
        val dumpCmd =
            "cd /proc/asound/card0; for d in pcm*c pcm*p; do echo @@\$d; cat \$d/sub0/status 2>/dev/null || echo closed; done"
        val stRaw = RootDetector.exec(dumpCmd) ?: ""
        val status = parseStatus(stRaw)
        val mixRaw = if (mix != null) RootDetector.exec("$mix") ?: "" else ""
        val live = nodes.filter { status[it.id]?.startsWith("RUNNING") == true }
        val txOn = mixRaw.lineSequence().mapNotNull { parseTxOn(it) }.toList()
        val voc = mixRaw.lineSequence().filter { it.contains("VOC_REC", true) }.toList()
        val incall = mixRaw.lineSequence().filter {
            it.contains("Incall", true) && Regex("""\bOn\b""").containsMatchIn(it)
        }.toList()
        val otherOn = mixRaw.lineSequence().filter { keepOtherOn(it) }.toList()
        val freeMm = nodes.filter { n ->
            n.capture &&
                n.name.contains("MultiMedia", true) &&
                !n.name.contains("Hostless", true) &&
                (status[n.id] == null || status[n.id] == "closed")
        }
        val sb = StringBuilder()
        sb.append("WS ← tinycap(free MM) ← copy of Tx bus\n")
        sb.append("WS → Incall_Music / Telephony Tx\n")
        sb.append("gsmIn ← VOICE_DOWNLINK after VOC_REC_DL\n")
        sb.append("Do NOT tinycap RUNNING VoiceMMode / HAL voice PCMs.\n")
        sb.append("tinymix=${mix ?: "missing"} tinycap=${cap ?: "missing"}\n")
        sb.append("active tap: ${LineRoute.hint()}\n\n")
        sb.append("== LIVE PCM (HAL owns these) ==\n")
        if (live.isEmpty()) sb.append("(none — place a GSM call)\n")
        else live.forEach { n ->
            val st = status[n.id] ?: "?"
            val warn = when {
                n.name.contains("Voice", true) || n.name.contains("MMode", true) ->
                    "  [DSP — tinycap usually 0; hw_ptr may stay 0]"
                n.capture && n.name.contains("MultiMedia1", true) ->
                    "  [often VOC_REC_DL / gsmIn]"
                else -> ""
            }
            sb.append("${n.id} ${n.name.take(28)} $st$warn\n")
        }
        sb.append("\n== TX mixers On (UL candidates) ==\n")
        if (txOn.isEmpty()) sb.append("(none On — call then Scan. Idle mic is not GSM TX.)\n")
        else txOn.forEach { t ->
            sb.append("${t.ctrl} = ${t.value}\n")
            sb.append("  copy as: MultiMediaN Mixer ${t.widget}\n")
        }
        sb.append("\n== VOC_REC ==\n")
        if (voc.isEmpty()) sb.append("(no VOC_REC names — Unisoc/MTK: use policy Telephony Rx/Tx)\n")
        else voc.take(24).forEach { sb.append(it.trim()).append('\n') }
        if (incall.isNotEmpty()) {
            sb.append("\n== Incall On (inject WS→radio) ==\n")
            incall.take(12).forEach { sb.append(it.trim()).append('\n') }
        }
        if (otherOn.isNotEmpty()) {
            sb.append("\n== other Mixer On (I2S/SLIM/AFE) ==\n")
            otherOn.take(20).forEach { sb.append(it.trim()).append('\n') }
        }
        sb.append("\n== free MultiMedia capture (tap targets) ==\n")
        if (freeMm.isEmpty()) sb.append("(none closed — stop music/recorder)\n")
        else freeMm.take(16).forEach { n ->
            val mm = Regex("""MultiMedia(\d+)""", RegexOption.IGNORE_CASE).find(n.name)?.groupValues?.get(1)
            sb.append("${n.id} ${n.name.take(28)}  mixer MultiMedia${mm ?: "?"} Mixer …\n")
        }
        sb.append("\nProbe UL measures RMS on Tx widget → first free MM. Speak during the beep window.")
        return sb.toString()
    }

    private fun runProbe(): String {
        val mix = LineRoute.findTinymix() ?: return "no tinymix"
        val tiny = LineRoute.findTinycap() ?: return "no tinycap"
        val pcmTxt = RootDetector.exec("cat /proc/asound/pcm") ?: ""
        val nodes = parsePcm(pcmTxt)
        val dumpCmd =
            "cd /proc/asound/card0; for d in pcm*c pcm*p; do echo @@\$d; cat \$d/sub0/status 2>/dev/null || echo closed; done"
        val status = parseStatus(RootDetector.exec(dumpCmd) ?: "")
        val mixRaw = RootDetector.exec(mix) ?: ""
        val txOn = mixRaw.lineSequence().mapNotNull { parseTxOn(it) }.toList()
        val widgets = linkedSetOf<String>()
        txOn.forEach { widgets.add(it.widget) }
        if (widgets.isEmpty()) {
            if (mixRaw.contains("VOC_REC_UL")) widgets.add("VOC_REC_UL")
        }
        val targets = nodes.filter { n ->
            n.capture &&
                n.name.contains("MultiMedia", true) &&
                !n.name.contains("Hostless", true) &&
                (status[n.id] == null || status[n.id] == "closed")
        }.take(4)
        if (targets.isEmpty()) return buildScan() + "\n\nProbe: no free MultiMedia capture."
        if (widgets.isEmpty()) {
            return buildScan() + "\n\nProbe: no VoiceMMode_Tx On and no VOC_REC_UL. Place a call, Scan, then Probe."
        }
        val wav = "/data/local/tmp/wlya-probe.wav"
        val hits = mutableListOf<ProbeHit>()
        val log = StringBuilder()
        log.append("Probe UL (speak now)\n")
        for (w in widgets.take(6)) {
            val mmNode = targets.firstOrNull { n ->
                val mm = Regex("""MultiMedia(\d+)""", RegexOption.IGNORE_CASE).find(n.name)
                val idx = mm?.groupValues?.get(1)?.toIntOrNull() ?: return@firstOrNull false
                idx != 1
            } ?: targets.first()
            val mmIdx = Regex("""MultiMedia(\d+)""", RegexOption.IGNORE_CASE)
                .find(mmNode.name)?.groupValues?.get(1) ?: continue
            val ctl = "MultiMedia$mmIdx Mixer $w"
            RootDetector.exec("$mix '$ctl' 1 1; echo set")
            RootDetector.exec("rm -f $wav")
            val capOut = RootDetector.exec(
                "$tiny $wav -D 0 -d ${mmNode.dev} -c 1 -r 16000 -b 16 -T 1; echo CAP_DONE",
            ) ?: ""
            RootDetector.exec("$mix '$ctl' 0 0; echo clr")
            val bytes = RootDetector.execBytes("cat $wav") ?: ByteArray(0)
            val (peak, rms) = wavStats(bytes)
            log.append(
                "$ctl pcm${mmNode.dev} peak=$peak rms=${"%.0f".format(Locale.US, rms)}  ${capOut.take(80)}\n",
            )
            if (peak >= 80) hits.add(ProbeHit(ctl, mmNode.dev, peak, rms))
        }
        lastBest = hits.maxByOrNull { it.peak }
        val best = lastBest
        log.append('\n')
        if (best != null) {
            log.append("BEST peak=${best.peak}  ${best.mixer}  tinycap -d ${best.pcm}\n")
            log.append("Tap Apply best UL to use this for gsmOut / line.\n")
        } else {
            log.append("No candidate had peak>=80. Talk louder, stay offhook, Scan again.\n")
        }
        log.append('\n').append(buildScan())
        Log.i(TAG, log.toString().take(500))
        return log.toString()
    }

    private data class TxOn(val ctrl: String, val value: String, val widget: String)

    private fun parseTxOn(line: String): TxOn? {
        if (!Regex("""\bOn\b""").containsMatchIn(line)) return null
        val m = Regex(
            """(VoiceMMode\d+_Tx Mixer (\S+)|Voice_Tx Mixer (\S+)|VOICEMMODE\d+_TX Mixer (\S+))""",
            RegexOption.IGNORE_CASE,
        ).find(line) ?: return null
        val ctrl = m.groupValues[1]
        val raw = listOf(m.groupValues[2], m.groupValues[3], m.groupValues[4]).first { it.isNotBlank() }
        val widget = raw.replace(Regex("""_MMode\d+$""", RegexOption.IGNORE_CASE), "")
        val value = line.substringAfter(ctrl).trim().ifBlank { "On" }
        return TxOn(ctrl.trim(), value, widget)
    }

    private fun keepOtherOn(line: String): Boolean {
        if (!Regex("""\bOn\b""").containsMatchIn(line)) return false
        if (line.contains("VoiceMMode", true)) return false
        val u = line.uppercase(Locale.US)
        return u.contains("MIXER") && (
            u.contains("MI2S") || u.contains("SLIM") || u.contains("I2S") ||
                u.contains("AFE") || u.contains("TDM") || u.contains("QUAT") ||
                u.contains("TERT") || u.contains("PRI_") || u.contains("SEC_")
            )
    }

    private fun parsePcm(pcm: String): List<PcmNode> {
        val out = mutableListOf<PcmNode>()
        for (line in pcm.lineSequence()) {
            val m = Regex("""^(\d+)-(\d+):\s*([^:]+)""").find(line.trim()) ?: continue
            val dev = m.groupValues[2].toIntOrNull() ?: continue
            val name = m.groupValues[3].replace("(*)", "").trim()
            if (line.contains("capture", ignoreCase = true)) {
                out.add(PcmNode("${dev}c", dev, true, name))
            }
            if (line.contains("playback", ignoreCase = true)) {
                out.add(PcmNode("${dev}p", dev, false, name))
            }
        }
        return out
    }

    private fun parseStatus(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var cur: String? = null
        val buf = StringBuilder()
        fun flush() {
            val key = cur ?: return
            val text = buf.toString()
            buf.clear()
            if (text.contains("closed") && !text.contains("state:")) {
                map[key] = "closed"
                return
            }
            val state = Regex("""state:\s*(\S+)""").find(text)?.groupValues?.get(1) ?: "?"
            val pid = Regex("""owner_pid\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)
            map[key] = if (pid != null) "$state pid=$pid" else state
        }
        for (line in raw.lineSequence()) {
            if (line.startsWith("@@")) {
                flush()
                cur = line.removePrefix("@@").trim().removePrefix("pcm")
                continue
            }
            buf.append(line).append('\n')
        }
        flush()
        return map
    }

    private fun wavStats(raw: ByteArray): Pair<Int, Double> {
        if (raw.size < 48) return 0 to 0.0
        var i = 12
        var dataAt = 44
        var dataLen = raw.size - 44
        while (i + 8 <= raw.size) {
            val id = String(raw, i, 4, Charsets.ISO_8859_1)
            val len = (raw[i + 4].toInt() and 0xff) or
                ((raw[i + 5].toInt() and 0xff) shl 8) or
                ((raw[i + 6].toInt() and 0xff) shl 16) or
                ((raw[i + 7].toInt() and 0xff) shl 24)
            if (id == "data") {
                dataAt = i + 8
                dataLen = len.coerceAtMost(raw.size - dataAt)
                break
            }
            i += 8 + len
            if (len < 0) break
        }
        val end = (dataAt + dataLen).coerceAtMost(raw.size)
        if (end - dataAt < 4) return 0 to 0.0
        var peak = 0
        var sum = 0.0
        var n = 0
        var p = dataAt
        while (p + 1 < end) {
            val s = (raw[p].toInt() and 0xff) or (raw[p + 1].toInt() shl 8)
            val v = s.toShort().toInt()
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
            sum += v.toDouble() * v
            n++
            p += 2
        }
        val rms = if (n == 0) 0.0 else sqrt(sum / n)
        return peak to rms
    }
}
