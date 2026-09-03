package pro.potoki.bekon.intent

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

internal data class SavedFile(
    /** Always a real file this process can read (app filesDir). */
    val path: String,
    val name: String,
    val size: Long,
    val uri: String,
    val publicPath: String? = null,
)

internal object FileInbox {
    const val MAX_BYTES = 25 * 1024 * 1024
    private const val TAG = "FileInbox"

    @Volatile
    var last: SavedFile? = null
        private set

    fun save(context: Context, requestedName: String, mimeHint: String, bytes: ByteArray): SavedFile {
        if (bytes.size > MAX_BYTES) throw IllegalArgumentException("file too large (max ${MAX_BYTES} bytes)")
        val name = sanitize(requestedName)
        val mime = resolveMime(name, mimeHint)
        val privateFile = unique(File(context.filesDir, "inbox"), name)
        privateFile.parentFile?.mkdirs()
        FileOutputStream(privateFile).use { it.write(bytes) }
        val providerUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", privateFile)
        var publicPath: String? = null
        var publicUri: String? = null
        var display = name
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val pub = saveMediaStore(context, name, mime, bytes)
                publicPath = pub.path
                publicUri = pub.uri
                display = pub.name
            } else {
                val pub = saveLegacy(context, name, mime, bytes)
                publicPath = pub.path
                publicUri = pub.uri
                display = pub.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "Downloads copy skipped: ${e.message}")
        }
        val dest = SavedFile(
            path = privateFile.absolutePath,
            name = display,
            size = bytes.size.toLong(),
            uri = publicUri ?: providerUri.toString(),
            publicPath = publicPath,
        )
        last = dest
        return dest
    }

    private fun saveMediaStore(context: Context, name: String, mime: String, bytes: ByteArray): SavedFile {
        val resolver = context.contentResolver
        var display = name
        var uri: Uri? = null
        for (attempt in 0 until 30) {
            val candidate = if (attempt == 0) name else numbered(name, attempt)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, candidate)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                display = candidate
                break
            }
        }
        val inserted = uri ?: throw IllegalStateException("cannot insert into Downloads")
        try {
            resolver.openOutputStream(inserted)?.use { it.write(bytes) }
                ?: throw IllegalStateException("cannot write Downloads")
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(inserted, done, null, null)
        } catch (e: Exception) {
            resolver.delete(inserted, null, null)
            throw e
        }
        var path = "/storage/emulated/0/${Environment.DIRECTORY_DOWNLOADS}/$display"
        resolver.query(
            inserted,
            arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { path = it }
                c.getString(1)?.takeIf { it.isNotBlank() }?.let { display = it }
            }
        }
        return SavedFile(path = path, name = display, size = bytes.size.toLong(), uri = inserted.toString())
    }

    private fun saveLegacy(context: Context, name: String, mime: String, bytes: ByteArray): SavedFile {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("cannot create Downloads")
        val dest = unique(dir, name)
        FileOutputStream(dest).use { it.write(bytes) }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
        return SavedFile(path = dest.absolutePath, name = dest.name, size = dest.length(), uri = uri.toString())
    }

    fun share(context: Context, path: String, mimeHint: String, pkg: String?, uriHint: String?) {
        val prev = last
        val usePath = path.ifBlank { prev?.path ?: prev?.publicPath ?: "" }
        val useUri = uriHint?.takeIf { it.isNotBlank() } ?: prev?.uri
        if (usePath.isBlank() && useUri.isNullOrBlank()) {
            throw IllegalArgumentException("share needs path/uri or a prior file")
        }
        val fileName = usePath.substringAfterLast('/').ifBlank { "file.bin" }
        val mime = resolveMime(fileName, mimeHint)
        val uri = resolveShareUri(context, usePath, useUri)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!pkg.isNullOrBlank()) setPackage(pkg)
        }
        if (!pkg.isNullOrBlank()) {
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(send)
        } else {
            context.startActivity(
                Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun resolveShareUri(context: Context, path: String, uriHint: String?): Uri {
        if (!uriHint.isNullOrBlank()) return Uri.parse(uriHint)
        if (path.startsWith("content:")) return Uri.parse(path)
        val file = File(path)
        if (file.isFile) {
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(path),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                }
            }
        }
        throw IllegalArgumentException("file not found: $path")
    }

    private fun resolveMime(name: String, hint: String): String {
        if (hint.isNotBlank() && hint != "application/octet-stream") return hint
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return hint.ifBlank { "application/octet-stream" }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: hint.ifBlank { "application/octet-stream" }
    }

    private fun sanitize(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        val name = if (cleaned.isBlank()) "file.bin" else cleaned.take(180)
        return if (name == "." || name == "..") "file.bin" else name
    }

    private fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return "$stem-$n$ext"
    }

    private fun unique(dir: File, name: String): File {
        dir.mkdirs()
        val dest = File(dir, name)
        if (!dest.exists()) return dest
        var n = 1
        while (true) {
            val alt = File(dir, numbered(name, n))
            if (!alt.exists()) return alt
            n++
        }
    }
}
