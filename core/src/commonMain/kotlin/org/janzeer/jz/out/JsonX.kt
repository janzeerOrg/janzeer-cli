package org.janzeer.jz.out

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Small read helpers over the node's JSON. Missing or null → null, never an exception. */
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonElement?.long(): Long? = str()?.toLongOrNull() ?: str()?.toDoubleOrNull()?.toLong()
fun JsonElement?.int(): Int? = long()?.toInt()
fun JsonElement?.bool(): Boolean? = str()?.toBooleanStrictOrNull()
fun JsonElement?.str(key: String): String? = this[key].str()
fun JsonElement?.long(key: String): Long? = this[key].long()
fun JsonElement?.int(key: String): Int? = this[key].int()
fun JsonElement?.bool(key: String): Boolean? = this[key].bool()
fun String?.orDash(): String = this ?: "-"

/** `3a7167…782c` for hashes and keys in tables; full value under --json. */
fun String.short(head: Int = 10, tail: Int = 6): String = if (length <= head + tail + 1) this else take(head) + "…" + takeLast(tail)

/** `2 min ago`, `3 h ago`, `5 d ago` for a millisecond timestamp. */
fun relativeAge(epochMs: Long, nowMs: Long): String {
    val s = (nowMs - epochMs) / 1000
    return when {
        s < 0 -> "in the future"
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

/** `4 d 20 h`, `3 h 12 min`, `45 min` for a duration in seconds. */
fun humanDuration(seconds: Long): String {
    val d = seconds / 86400; val h = seconds % 86400 / 3600; val m = seconds % 3600 / 60
    return when {
        d > 0 -> "$d d $h h"
        h > 0 -> "$h h $m min"
        m > 0 -> "$m min"
        else -> "$seconds s"
    }
}
