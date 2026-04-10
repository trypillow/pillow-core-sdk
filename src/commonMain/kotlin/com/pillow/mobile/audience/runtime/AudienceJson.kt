package com.pillow.mobile.audience.runtime

import kotlinx.serialization.json.Json

internal object AudienceJson {
  val instance: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }
}
