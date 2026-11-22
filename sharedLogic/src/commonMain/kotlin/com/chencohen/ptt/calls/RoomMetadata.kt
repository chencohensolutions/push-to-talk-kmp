package com.chencohen.ptt.calls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The LiveKit room's metadata the server sets: {"recording":true,"by":"user.device","startedAt":…} or {"recording":false}. */
object RoomMetadata {
    /** The device the server keeps the call for, or null when it doesn't (or the metadata isn't the server's). */
    fun recorderOf(metadata: String?): String? = runCatching {
        if (metadata.isNullOrBlank()) return null
        val json: JsonObject = Json.parseToJsonElement(metadata).jsonObject
        if (json["recording"]?.jsonPrimitive?.booleanOrNull != true) return null
        json["by"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
    }.getOrNull()
}
