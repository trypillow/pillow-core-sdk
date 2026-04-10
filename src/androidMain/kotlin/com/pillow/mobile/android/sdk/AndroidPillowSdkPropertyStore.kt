package com.pillow.mobile.android.sdk

import android.content.SharedPreferences
import com.pillow.mobile.audience.runtime.AudienceJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

internal class AndroidPillowSdkPropertyStore(
  private val sharedPreferences: SharedPreferences,
) {
  fun readExternalId(): String? = sharedPreferences.getString(EXTERNAL_ID_KEY, null)

  fun writeExternalId(externalId: String) {
    sharedPreferences.edit().putString(EXTERNAL_ID_KEY, externalId).apply()
  }

  fun clearExternalId() {
    sharedPreferences.edit().remove(EXTERNAL_ID_KEY).apply()
  }

  fun readUserProperties(): Map<String, JsonElement>? {
    val raw = sharedPreferences.getString(USER_PROPERTIES_KEY, null) ?: return null
    return runCatching {
      AudienceJson.instance.decodeFromString<Map<String, JsonElement>>(raw)
    }.getOrNull()
  }

  fun writeUserProperty(key: String, value: JsonElement) {
    val current = (readUserProperties() ?: emptyMap()).toMutableMap()
    current[key] = value
    sharedPreferences.edit()
      .putString(USER_PROPERTIES_KEY, AudienceJson.instance.encodeToString(current))
      .apply()
  }

  fun clearUserProperty(key: String) {
    val current = (readUserProperties() ?: emptyMap()).toMutableMap()
    if (current.remove(key) == null) {
      return
    }

    val editor = sharedPreferences.edit()
    if (current.isEmpty()) {
      editor.remove(USER_PROPERTIES_KEY)
    } else {
      editor.putString(USER_PROPERTIES_KEY, AudienceJson.instance.encodeToString(current))
    }
    editor.apply()
  }

  fun clearUserProperties() {
    sharedPreferences.edit().remove(USER_PROPERTIES_KEY).apply()
  }

  private companion object {
    const val EXTERNAL_ID_KEY = "external_id"
    const val USER_PROPERTIES_KEY = "user_properties_json"
  }
}
