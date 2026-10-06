package com.ccatq.xdylupdater2.core

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object Redactor {
    fun sensitive(key: String): Boolean = key.lowercase().replace('-', '_').let { it.contains("token") || it.contains("password") || it.contains("secret") || it.contains("cookie") || it == "authorization" || it == "code" || it == "session_id" || it == "api_key" }
    fun json(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (k, v) -> if (sensitive(k)) JsonPrimitive("[REDACTED]") else json(v) })
        is JsonArray -> JsonArray(value.map(::json))
        else -> value
    }
    fun text(value: String): String = runCatching { json(wireJson.parseToJsonElement(value)).toString() }.getOrElse {
        // Non-JSON text may echo secrets in arbitrary formats: export no raw body.
        "[非 JSON 正文已省略；${value.toByteArray().size} 字节]"
    }
    fun url(value: String): String {
        val u = value.toHttpUrlOrNull() ?: return "[无效地址]"
        return u.newBuilder().username("").password("").fragment(null).apply {
            u.queryParameterNames.forEach { name -> if (sensitive(name)) setQueryParameter(name, "[REDACTED]") }
        }.build().toString()
    }
    fun headers(values: Map<String, String>) = values.mapValues { (k, v) -> if (sensitive(k)) "[REDACTED]" else if (k.equals("Location", true)) url(v) else v }
}
