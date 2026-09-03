package com.wlya.desktop

import java.io.File

object StorePaths {
    /** JSON store used by [com.wlya.core.FileStore]. */
    fun desktopStoreFile(): String {
        System.getenv("WLYA_STORE")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        val root = projectRoot()
        val wlyaDir = File(root, ".wlya")
        val canonical = File(wlyaDir, "wlya-tunnel.json")
        val fromDesktopJson = File(wlyaDir, "wlya-desktop.json")
        val legacy = File(root, "wlya-desktop/.wlya/wlya-desktop.json")

        wlyaDir.mkdirs()
        if (!canonical.exists()) {
            when {
                fromDesktopJson.exists() -> fromDesktopJson.copyTo(canonical, overwrite = false)
                legacy.exists() -> legacy.copyTo(canonical, overwrite = false)
            }
        }
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
