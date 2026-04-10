package com.pillow.mobile.ios.audience

import com.pillow.mobile.audience.runtime.AudienceJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import platform.Foundation.NSUserDefaults

internal class IosAudiencePropertyStore {
  private val defaults = NSUserDefaults(suiteName = SUITE_NAME)!!

  fun readExternalId(): String? =
    defaults.stringForKey(EXTERNAL_ID_KEY)

  fun writeExternalId(externalId: String) {
    defaults.setObject(externalId, forKey = EXTERNAL_ID_KEY)
  }

  fun clearExternalId() {
    defaults.removeObjectForKey(EXTERNAL_ID_KEY)
  }

  fun readUserProperties(): Map<String, JsonElement>? {
    val raw = defaults.stringForKey(USER_PROPERTIES_KEY) ?: return null
    return runCatching {
      AudienceJson.instance.decodeFromString<Map<String, JsonElement>>(raw)
    }.getOrNull()
  }

  fun writeUserProperty(key: String, value: JsonElement) {
    val current = (readUserProperties() ?: emptyMap()).toMutableMap()
    current[key] = value
    defaults.setObject(
      AudienceJson.instance.encodeToString(current as Map<String, JsonElement>),
      forKey = USER_PROPERTIES_KEY,
    )
  }

  fun clearUserProperty(key: String) {
    val current = (readUserProperties() ?: emptyMap()).toMutableMap()
    if (current.remove(key) == null) {
      return
    }

    if (current.isEmpty()) {
      defaults.removeObjectForKey(USER_PROPERTIES_KEY)
    } else {
      defaults.setObject(
        AudienceJson.instance.encodeToString(current as Map<String, JsonElement>),
        forKey = USER_PROPERTIES_KEY,
      )
    }
  }

  fun clearUserProperties() {
    defaults.removeObjectForKey(USER_PROPERTIES_KEY)
  }

  private companion object {
    const val SUITE_NAME = "com.pillow.mobile.audience"
    const val EXTERNAL_ID_KEY = "external_id"
    const val USER_PROPERTIES_KEY = "user_properties"
  }
}
