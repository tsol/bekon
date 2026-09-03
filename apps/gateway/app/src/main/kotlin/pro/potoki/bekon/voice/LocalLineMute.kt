package pro.potoki.bekon.voice

import android.util.Log
import pro.potoki.bekon.RootDetector

/**
 * Test controls that silence only Motorola's physical call endpoints.
 *
 * Do not use Voice Tx/Rx Device Mute here: those controls sit on the complete
 * vocoder path and also mute Incall_Music. TX variants cut the codec microphone
 * before it reaches TERT_MI2S_TX. Values are snapshotted during the live call
 * and restored. RX is deliberately untouched: the quiet earpiece is harmless,
 * while every tested voice RX control also disturbed Incall_Music.
 */
object LocalLineMute {
    private const val TAG = "LocalLineMute"

    const val NORMAL = "normal"
    const val TX_ADC0 = "adc0"
    const val TX_DEC0 = "dec0"
    const val TX_MUX_ZERO = "mux-zero"

    private val txAdc = listOf("ADC1 Volume", "ADC2 Volume", "ADC3 Volume")
    private val txDec = listOf("DEC1 Volume", "DEC2 Volume", "DEC3 Volume", "DEC4 Volume")
    private val txMux = listOf("DEC1 MUX", "DEC2 MUX", "DEC3 MUX", "DEC4 MUX")

    private val txOriginal = linkedMapOf<String, String>()

    @Volatile var txMode = NORMAL
        private set
    @Volatile var lastResult = ""
        private set

    @Synchronized
    fun setTx(mode: String): String {
        require(mode in setOf(NORMAL, TX_ADC0, TX_DEC0, TX_MUX_ZERO)) {
            "unknown local TX mode $mode"
        }
        val mix = LineRoute.findTinymix() ?: error("tinymix not found")
        if (txOriginal.isEmpty()) snapshot(mix, txAdc + txDec + txMux, txOriginal)
        restore(mix, txOriginal)
        when (mode) {
            TX_ADC0 -> writeAll(mix, txAdc, "0")
            TX_DEC0 -> writeAll(mix, txDec, "0")
            TX_MUX_ZERO -> writeAll(mix, txMux, "ZERO")
        }
        txMode = mode
        lastResult = "tx=$mode ${readActive(mix, txAdc + txDec + txMux)}"
        Log.i(TAG, lastResult)
        return lastResult
    }

    @Synchronized
    fun restoreAll() {
        try {
            val mix = LineRoute.findTinymix()
            if (mix != null) {
                restore(mix, txOriginal)
            }
            lastResult = "local endpoints restored"
        } catch (e: Exception) {
            // Cleanup runs from call-state/service teardown and must never crash
            // VoiceService even if a vendor mixer control rejects restoration.
            lastResult = "restore warning: ${e.message}"
            Log.e(TAG, lastResult, e)
        } finally {
            txMode = NORMAL
            txOriginal.clear()
        }
        Log.i(TAG, lastResult)
    }

    private fun snapshot(mix: String, names: List<String>, into: MutableMap<String, String>) {
        val output = RootDetector.exec(
            names.joinToString("; ") { "$mix '$it'" } + "; echo snapshot_done",
        ) ?: error("mixer snapshot failed")
        for (name in names) {
            val line = output.lineSequence().firstOrNull { it.startsWith("$name:") }
                ?: error("missing mixer control $name")
            into[name] = parseValue(line)
        }
    }

    private fun parseValue(line: String): String {
        val raw = line.substringAfter(':').trim()
        // Enum selection is prefixed by '>' at a token boundary. Do not match
        // the arrow in integer metadata such as "(dsrange 0->124)".
        Regex("""(?:^|\s)>([A-Za-z_][A-Za-z0-9_-]*)""")
            .find(raw)?.groupValues?.get(1)?.let { return it }
        val fields = raw.substringBefore(" (").split(' ')
        if (fields.all { it == "On" || it == "Off" }) {
            return fields.joinToString(" ") { if (it == "On") "1" else "0" }
        }
        val value = fields.first()
        require(value.matches(Regex("""-?\d+"""))) {
            "cannot parse mixer value: $line"
        }
        return value
    }

    private fun restore(mix: String, values: Map<String, String>) {
        if (values.isEmpty()) return
        val cmd = values.entries.joinToString("; ") { (name, value) ->
            "$mix '$name' $value"
        }
        RootDetector.exec("$cmd; echo restore_done") ?: error("mixer restore failed")
    }

    private fun writeAll(mix: String, names: List<String>, value: String) {
        val cmd = names.joinToString("; ") { "$mix '$it' $value" }
        RootDetector.exec("$cmd; echo write_done") ?: error("mixer write failed")
    }

    private fun readActive(mix: String, names: List<String>): String {
        val output = RootDetector.exec(
            names.joinToString("; ") { "$mix '$it'" } + "; echo read_done",
        ) ?: return "readback failed"
        return output.lineSequence()
            .filterNot { it == "read_done" }
            .joinToString(" | ")
            .take(500)
    }
}
