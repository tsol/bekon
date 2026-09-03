package com.wlya.core

const val LOG_KEEP_LAST = 500
const val LOG_PREVIEW_CHARS = 2_000
/** Full inbound payloads for phone-control-api (screenshots). UI still uses previews. */
const val LOG_FULL_INBOUND = 8
/** Keep screenshots; drop APK-sized commands so ingest/UI do not pin tens of MB. */
const val LOG_FULL_INBOUND_CHARS = 800_000

fun <T> MutableList<T>.keepLast(n: Int = LOG_KEEP_LAST) {
    if (size > n) subList(0, size - n).clear()
}

/** UI/debug logs must not keep screenshot base64. */
fun logPreview(text: String, maxChars: Int = LOG_PREVIEW_CHARS): String {
    if (text.length <= maxChars) return text
    return text.take(maxChars) + "… [${text.length - maxChars} more]"
}
