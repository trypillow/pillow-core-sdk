package com.pillow.mobile.audience.runtime

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class AudienceSessionClaims(
  val sessionId: String,
)

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeAudienceSessionToken(token: String): AudienceSessionClaims {
  val parts = token.split('.')
  require(parts.size == 3) { "Invalid audience token format" }
  val payload = parts[1]
    .replace('-', '+')
    .replace('_', '/')
    .let { segment ->
      when (segment.length % 4) {
        0 -> segment
        2 -> "$segment=="
        3 -> "$segment="
        else -> throw IllegalArgumentException("Invalid audience token payload")
      }
    }

  val payloadJson = AudienceJson.instance.parseToJsonElement(
    Base64.decode(payload).decodeToString(),
  ).jsonObject

  return AudienceSessionClaims(
    sessionId = payloadJson.requireNumber("session_id"),
  )
}

private fun JsonObject.requireNumber(key: String): String {
  val value = this[key] as? JsonPrimitive ?: throw IllegalArgumentException("Missing $key")
  return value.content
}
