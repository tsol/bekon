package pro.potoki.bekon.voice

import pro.potoki.bekon.RootDetector

/** Last known electrical GSM tap. Defaults are moto ocean; Probe UL overwrites after RMS. */
object LineRoute {
    const val DEFAULT_UL_MIXER = "MultiMedia2 Mixer TERT_MI2S_TX"
    const val DEFAULT_UL_PCM = 1
    const val DEFAULT_DL_MIXER = "MultiMedia1 Mixer VOC_REC_DL"

    @Volatile var ulMixer = DEFAULT_UL_MIXER
    @Volatile var ulPcm = DEFAULT_UL_PCM
    @Volatile var dlMixer = DEFAULT_DL_MIXER

    fun load(prefs: VoicePrefs) {
        ulMixer = prefs.ulMixer.ifBlank { DEFAULT_UL_MIXER }
        ulPcm = prefs.ulPcm
        dlMixer = prefs.dlMixer.ifBlank { DEFAULT_DL_MIXER }
    }

    fun save(prefs: VoicePrefs) {
        prefs.ulMixer = ulMixer
        prefs.ulPcm = ulPcm
        prefs.dlMixer = dlMixer
    }

    fun hint(): String = "UL=$ulMixer pcm$ulPcm  DL=$dlMixer"

    @Volatile private var tinymixPath: String? = null
    @Volatile private var tinycapPath: String? = null

    fun findTinymix(): String? {
        tinymixPath?.let { return it }
        val p = firstExec(
            "/vendor/bin/tinymix",
            "/system/bin/tinymix",
            "/system/vendor/bin/tinymix",
        )
        tinymixPath = p
        return p
    }

    fun findTinycap(): String? {
        tinycapPath?.let { return it }
        val p = firstExec(
            "/system/bin/tinycap",
            "/vendor/bin/tinycap",
            "/system/xbin/tinycap",
        )
        tinycapPath = p
        return p
    }

    private fun firstExec(vararg paths: String): String? {
        if (!RootDetector.detect()) return null
        for (p in paths) {
            val ok = RootDetector.exec("test -x '$p' && echo ok") ?: continue
            if (ok.contains("ok")) return p
        }
        return null
    }
}
