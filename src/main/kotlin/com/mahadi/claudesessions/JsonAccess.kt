package com.mahadi.claudesessions

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Null-safe readers for transcript JSON.
 *
 * A transcript line was written by whichever Claude Code version produced it, so a key may
 * be missing or hold a different type than expected. Every accessor answers null (or zero)
 * instead of throwing, which lets a caller skip one unusable field rather than lose the
 * whole file.
 */
internal fun JsonObject.stringOrNull(key: String): String? {
    val element: JsonElement = get(key) ?: return null
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
}

internal fun JsonObject.booleanOrNull(key: String): Boolean? {
    val element: JsonElement = get(key) ?: return null
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) element.asBoolean else null
}

internal fun JsonObject.objectOrNull(key: String): JsonObject? {
    val element: JsonElement = get(key) ?: return null
    return if (element.isJsonObject) element.asJsonObject else null
}

internal fun JsonObject.longOrZero(key: String): Long {
    val element: JsonElement = get(key) ?: return 0L
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asLong else 0L
}
