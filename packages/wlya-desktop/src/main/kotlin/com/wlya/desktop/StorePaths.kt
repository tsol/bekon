package com.wlya.desktop

import java.io.File

object StorePaths {
    /** JSON store used by [com.wlya.core.FileStore]. */
    fun desktopStoreFile(): String {
        System.getenv("WLYA_STORE")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        val root = projectRoot()
        val canonical = File(root, ".wlya/wlya-desktop.json")
        val legacy = File(root, "wlya-desktop/.wlya/wlya-desktop.json")

        canonical.parentFile.mkdirs()
        if (!canonical.exists() && legacy.exists()) {
            legacy.copyTo(canonical, overwrite = false)
        }
        // Canonical is source of truth. Re-merging legacy on every start
        // resurrected deleted tunnels (DELETE 200, then they came back).
        retireLegacy(legacy)

        return canonical.absolutePath
    }

    private fun retireLegacy(legacy: File) {
        if (!legacy.exists()) return
        val retired = File(legacy.parentFile, "wlya-desktop.json.legacy")
        if (!retired.exists()) {
            if (!legacy.renameTo(retired)) legacy.delete()
        } else {
            legacy.delete()
        }
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "package.json").exists() || File(dir, "pnpm-lock.yaml").exists()) {
                return dir
            }
            val parent = dir.parentFile ?: break
            dir = parent
        }
        return File(System.getProperty("user.dir"))
    }
}
