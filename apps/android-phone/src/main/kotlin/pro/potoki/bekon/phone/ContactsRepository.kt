package pro.potoki.bekon.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ContactHit(
    val name: String,
    val number: String,
)

class ContactsRepository(private val context: Context) {
    fun search(query: String, limit: Int = 40): List<ContactHit> {
        if (!hasPermission()) return emptyList()
        val trimmed = query.trim()
        val out = mutableListOf<ContactHit>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection: String?
        val args: Array<String>?
        if (trimmed.isEmpty()) {
            selection = null
            args = null
        } else {
            val q = "%$trimmed%"
            selection =
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            args = arrayOf(q, q)
        }
        context.contentResolver.query(
            uri,
            projection,
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { c ->
            val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext() && out.size < limit) {
                val name = if (ni >= 0) c.getString(ni).orEmpty() else ""
                val number = if (pi >= 0) c.getString(pi).orEmpty() else ""
                if (number.isNotBlank()) out.add(ContactHit(name, number))
            }
        }
        return out
    }

    fun displayName(number: String): String {
        if (!hasPermission() || number.isBlank()) return ""
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (i >= 0) return c.getString(i).orEmpty()
            }
        }
        return ""
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
}
