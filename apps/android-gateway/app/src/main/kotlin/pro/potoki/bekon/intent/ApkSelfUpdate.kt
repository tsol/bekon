package pro.potoki.bekon.intent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import pro.potoki.bekon.RootDetector
import pro.potoki.bekon.SetupActivity
import pro.potoki.bekon.wlya.WlyaTunnelManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * After putFile of a matching APK (ACK already sent): root `pm install` + Magisk overlay,
 * or a system install prompt when unrooted.
 */
object ApkSelfUpdate {
    private const val TAG = "ApkSelfUpdate"
    const val PREFS_AUTO = "bekon_auto_update_apk"
    const val PREFS_SHARE_LOGS = "bekon_share_logs"
    private const val TMP = "/data/local/tmp/bekon-update.apk"
    private const val MAGISK_APK =
        "/data/adb/modules/wlya-voice/system/priv-app/Bekon/Bekon.apk"
    private const val LOG_CAP = 80

    private val logBuf = ArrayDeque<String>()
    private val logLock = Any()

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREFS_AUTO, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREFS_AUTO, on).apply()
    }

    fun logsEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREFS_SHARE_LOGS, true)

    fun setLogsEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREFS_SHARE_LOGS, on).apply()
    }

    fun recentLog(n: Int = LOG_CAP): List<String> = synchronized(logLock) {
        logBuf.toList().takeLast(n.coerceIn(1, LOG_CAP))
    }

    fun looksLikeApk(name: String, mime: String): Boolean {
        if (name.endsWith(".apk", ignoreCase = true)) return true
        return mime.equals("application/vnd.android.package-archive", ignoreCase = true)
    }

    /** Call only after the putFile ACK has been sent on the tunnel. */
    fun afterAckSent(ctx: Context, mime: String, sendPlaintext: (String) -> Unit) {
        if (!enabled(ctx)) {
            note("skip: auto-update off")
            return
        }
        val dest = FileInbox.last ?: run {
            note("skip: no last file")
            return
        }
        if (!looksLikeApk(dest.name, mime) && !looksLikeApk(File(dest.path).name, mime)) {
            return
        }
        val app = ctx.applicationContext
        Thread {
            val result = apply(app, dest)
            try {
                sendPlaintext(JSONArray().put(result.toJson()).toString())
            } catch (e: Exception) {
                Log.e(TAG, "apkUpdate send failed: ${e.message}")
                note("apkUpdate send failed: ${e.message}")
            }
        }.start()
    }

    data class UpdateResult(
        val ok: Boolean,
        val stage: String,
        val detail: String,
        val rooted: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", "apk-update")
            .put("ok", ok)
            .put("type", "apkUpdate")
            .put("stage", stage)
            .put("detail", detail)
            .put("rooted", rooted)
            .put("log", JSONArray(recentLog()))
    }

    internal fun apply(ctx: Context, dest: SavedFile): UpdateResult {
        val src = File(dest.path)
        if (!src.isFile) {
            val msg = "missing private file ${dest.path}"
            note("skip: $msg")
            return UpdateResult(false, "missing", msg, RootDetector.detect())
        }
        val ident = identifyApk(ctx, src.absolutePath)
        if (!ident.ok) {
            note("skip: ${ident.reason}")
            return UpdateResult(false, ident.stage, ident.reason, RootDetector.detect())
        }
        val rooted = RootDetector.detect()
        note("begin path=${src.absolutePath} size=${src.length()} rooted=$rooted public=${dest.publicPath}")
        return if (rooted) applyRoot(ctx, src) else applyUserPrompt(ctx, src)
    }

    private fun applyRoot(ctx: Context, src: File): UpdateResult {
        val quoted = src.absolutePath.replace("'", "'\\''")
        val stage = RootDetector.exec("cp '$quoted' $TMP && chmod 644 $TMP && ls -l $TMP")
        note("stage: $stage")
        if (stage == null) {
            return UpdateResult(false, "stage", "su cp failed", true)
        }
        val install = RootDetector.exec("pm install -r $TMP; echo __RC:$?")
        note("pm install: $install")
        val success = install?.contains("Success", ignoreCase = true) == true ||
            install?.contains("__RC:0") == true
        if (!success) {
            return UpdateResult(false, "pm", install ?: "pm install empty", true)
        }
        RootDetector.exec("cp $TMP /data/local/tmp/bekon.apk && echo tmp")
        RootDetector.exec("mkdir -p /data/adb/modules/wlya-voice/system/priv-app/Bekon")
        val overlay = RootDetector.exec(
            "cp $TMP $MAGISK_APK && chmod 644 $MAGISK_APK && ls -l $MAGISK_APK",
        )
        note("overlay: $overlay")
        ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(WlyaTunnelManager.PREF_RUNNING, true)
            .commit()
        RootDetector.exec("sync && echo synced")
        val reboot = RootDetector.exec("(sleep 8; reboot) >/dev/null 2>&1 & echo reboot-scheduled")
        note("reboot: $reboot")
        return UpdateResult(true, "installed", install ?: "Success", true)
    }

    private fun applyUserPrompt(ctx: Context, src: File): UpdateResult {
        return try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", src)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            grantInstallers(ctx, uri)
            if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                note("opening unknown-sources settings")
                val settings = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Handler(Looper.getMainLooper()).post {
                    try {
                        ctx.startActivity(settings)
                    } catch (e: Exception) {
                        note("settings: ${e.message}")
                    }
                }
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(flags)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }
            ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(WlyaTunnelManager.PREF_RUNNING, true)
                .commit()
            Handler(Looper.getMainLooper()).post {
                try {
                    ctx.startActivity(view)
                    note("install prompt started")
                } catch (e: Exception) {
                    note("install prompt failed: ${e.message}")
                }
            }
            UpdateResult(true, "prompt", "system installer shown (tap Continue)", false)
        } catch (e: Exception) {
            note("prompt error: ${e.message}")
            UpdateResult(false, "prompt", e.message ?: "prompt failed", false)
        }
    }

    private fun grantInstallers(ctx: Context, uri: Uri) {
        val perm = Intent.FLAG_GRANT_READ_URI_PERMISSION
        for (pkg in listOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.google.android.permissioncontroller",
        )) {
            try {
                ctx.grantUriPermission(pkg, uri, perm)
            } catch (_: Exception) {
            }
        }
    }

    private fun note(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] [ApkSelfUpdate] $msg"
        Log.i(TAG, msg)
        synchronized(logLock) {
            logBuf.addLast(line)
            while (logBuf.size > LOG_CAP) logBuf.removeFirst()
        }
    }

    private data class Ident(
        val ok: Boolean,
        val stage: String,
        val reason: String,
    )

    /**
     * `GET_SIGNING_CERTIFICATES` alone on [PackageManager.getPackageArchiveInfo] is empty on
     * many API 28–29 builds (MIUI). Always OR in [PackageManager.GET_SIGNATURES] and compare cert bytes.
     */
    private fun identifyApk(ctx: Context, apkPath: String): Ident {
        val pm = ctx.packageManager
        val incoming = parseArchive(pm, apkPath)
            ?: return Ident(false, "archive", "getPackageArchiveInfo null path=$apkPath")
        incoming.applicationInfo?.sourceDir = apkPath
        incoming.applicationInfo?.publicSourceDir = apkPath
        val pkg = incoming.packageName ?: ""
        if (pkg != ctx.packageName) {
            return Ident(false, "package", "apk pkg=$pkg installed=${ctx.packageName}")
        }
        val ours = try {
            pm.getPackageInfo(ctx.packageName, signerFlags())
        } catch (e: Exception) {
            return Ident(false, "installed", "getPackageInfo: ${e.message}")
        }
        val a = signerCerts(incoming)
        val b = signerCerts(ours)
        if (a.isEmpty()) {
            return Ident(false, "archive-signers", "archive has no signatures pkg=$pkg")
        }
        if (b.isEmpty()) {
            return Ident(false, "installed-signers", "installed has no signatures")
        }
        if (!certsOverlap(a, b)) {
            return Ident(
                false,
                "signature",
                "cert mismatch apk=${shortCerts(a)} installed=${shortCerts(b)}",
            )
        }
        note("identity ok pkg=$pkg apk=${shortCerts(a)} installed=${shortCerts(b)}")
        return Ident(true, "identity", "ok")
    }

    @Suppress("DEPRECATION")
    private fun parseArchive(pm: PackageManager, apkPath: String): PackageInfo? {
        pm.getPackageArchiveInfo(apkPath, signerFlags())?.let { return it }
        return pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
    }

    @Suppress("DEPRECATION")
    private fun signerFlags(): Int {
        var flags = PackageManager.GET_SIGNATURES
        if (Build.VERSION.SDK_INT >= 28) flags = flags or PackageManager.GET_SIGNING_CERTIFICATES
        return flags
    }

    @Suppress("DEPRECATION")
    private fun signerCerts(info: PackageInfo): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            si?.apkContentsSigners?.forEach { out.add(it.toByteArray()) }
            if (out.isEmpty()) si?.signingCertificateHistory?.forEach { out.add(it.toByteArray()) }
        }
        if (out.isEmpty()) info.signatures?.forEach { out.add(it.toByteArray()) }
        return out
    }

    private fun certsOverlap(a: List<ByteArray>, b: List<ByteArray>): Boolean {
        for (left in a) {
            for (right in b) {
                if (left.contentEquals(right)) return true
            }
        }
        return false
    }

    private fun shortCerts(certs: List<ByteArray>): String =
        certs.joinToString(",") { it.fold(0) { acc, byte -> acc * 31 + byte.toInt() }.toString(16) }
}
