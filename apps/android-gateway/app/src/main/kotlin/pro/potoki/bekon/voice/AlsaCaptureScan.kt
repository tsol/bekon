package pro.potoki.bekon.voice

import android.content.Context
import android.util.Log
import pro.potoki.bekon.RootDetector
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Watch ALSA without tinycap. tinycap on a FIFO exits 0 with 0 frames;
 * live GSM is on PCMs the HAL already owns. Poll /proc/asound hw_ptr instead.
 */
object AlsaCaptureScan {
    private const val TAG = "AlsaScan"
    private val dumpCmd =
        "cd /proc/asound/card0; for d in pcm*c pcm*p; do echo @@\$d; cat \$d/sub0/status 2>/dev/null || echo closed; done"

    private val names = ConcurrentHashMap<String, String>()
    private val peaks = ConcurrentHashMap<String, Int>()
    private val st = ConcurrentHashMap<String, String>()
    private val lastPtr = ConcurrentHashMap<String, Long>()
    private val lock = Any()
    @Volatile private var running = false
    private var loop: Thread? = null

    fun sync(ctx: Context) {
        if (VoiceMeters.debug) start(ctx.applicationContext) else stop()
    }

    fun start(ctx: Context) {
        synchronized(lock) {
            if (running) return
            if (!RootDetector.detect()) {
                st["_"] = "no root"
                return
            }
            val pcm = RootDetector.exec("cat /proc/asound/pcm") ?: ""
            parseNames(pcm)
            running = true
            loop = thread(name = "alsa-proc") {
                while (running) {
                    pollOnce()
                    try {
                        Thread.sleep(400)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            Log.i(TAG, "procfs poller on, names=${names.size}")
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            loop?.interrupt()
            loop?.join(600)
            loop = null
        }
    }

    fun decay() {
        /* hw_ptr delta already decays when streams stall */
    }

    fun render(): String {
        if (st["_"] == "no root") return "ALSA: no root"
        val live = names.keys.filter { st[it] != null && st[it] != "closed" }.sorted()
        val closedN = names.keys.count { st[it] == "closed" }
        val body = if (live.isEmpty()) {
            "all closed ($closedN nodes) — HAL not streaming. Place a call."
        } else {
            live.joinToString("\n") { id ->
                val pk = peaks[id] ?: 0
                val filled = (pk / 10).coerceIn(0, 10)
                val bar = "#".repeat(filled) + ".".repeat(10 - filled)
                "%-5s %-18s %3d %s %s".format(
                    id,
                    (names[id] ?: "").take(18),
                    pk,
                    bar,
                    st[id] ?: "",
                )
            }
        }
        return "ALSA /proc hw_ptr (no tinycap)\n$body"
    }

    private fun parseNames(pcm: String) {
        names.clear()
        for (line in pcm.lineSequence()) {
            val m = Regex("""^(\d+)-(\d+):\s*([^:]+)""").find(line.trim()) ?: continue
            val id = m.groupValues[2]
            val name = m.groupValues[3].replace("(*)", "").trim()
            if (line.contains("capture", ignoreCase = true)) names["${id}c"] = name
            if (line.contains("playback", ignoreCase = true)) names["${id}p"] = name
        }
    }

    private fun pollOnce() {
        val raw = RootDetector.exec(dumpCmd) ?: return
        var cur: String? = null
        val buf = StringBuilder()
        fun flush() {
            val key = cur ?: return
            val text = buf.toString()
            buf.clear()
            if (text.contains("closed") && !text.contains("state:")) {
                st[key] = "closed"
                peaks[key] = 0
                return
            }
            val state = Regex("""state:\s*(\S+)""").find(text)?.groupValues?.get(1) ?: "?"
            val ptr = Regex("""hw_ptr\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
            st[key] = state
            if (ptr != null) {
                val prev = lastPtr[key]
                lastPtr[key] = ptr
                val delta = if (prev == null) 0L else kotlin.math.abs(ptr - prev)
                val pk = when {
                    !state.contains("RUNNING", ignoreCase = true) -> 0
                    delta <= 0L -> 8
                    else -> (delta / 200L).toInt().coerceIn(15, 100)
                }
                peaks[key] = pk
            } else if (state.contains("RUNNING", ignoreCase = true)) {
                peaks[key] = 20
            } else {
                peaks[key] = 0
            }
        }
        for (line in raw.lineSequence()) {
            if (line.startsWith("@@")) {
                flush()
                val dir = line.removePrefix("@@").trim()
                cur = dir.removePrefix("pcm")
                continue
            }
            buf.append(line).append('\n')
        }
        flush()
    }
}
