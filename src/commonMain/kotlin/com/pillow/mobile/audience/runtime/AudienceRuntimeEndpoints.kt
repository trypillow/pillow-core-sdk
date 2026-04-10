package com.pillow.mobile.audience.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val AUDIENCE_RUNTIME_ENDPOINTS_KEY: String = "audience_runtime_endpoints"

@Serializable
internal data class AudienceRuntimeEndpoints(
  @SerialName("api_base_url") val apiBaseUrl: String,
  @SerialName("study_base_url") val studyBaseUrl: String? = null,
)

private fun defaultAudienceRuntimeEndpoints(seedBaseUrl: String): AudienceRuntimeEndpoints =
  AudienceRuntimeEndpoints(
    apiBaseUrl = resolveAudienceControlPlaneBaseUrl(seedBaseUrl),
  )

private fun sanitizeAudienceRuntimeEndpoints(
  endpoints: AudienceRuntimeEndpoints,
  seedBaseUrl: String,
): AudienceRuntimeEndpoints =
  AudienceRuntimeEndpoints(
    apiBaseUrl = normalizeAudienceBaseUrl(endpoints.apiBaseUrl)
      ?: resolveAudienceControlPlaneBaseUrl(seedBaseUrl),
    studyBaseUrl = normalizeAudienceBaseUrl(endpoints.studyBaseUrl),
  )

internal suspend fun readAudienceRuntimeEndpoints(
  secureStore: AudienceSecureStore,
  seedBaseUrl: String,
): AudienceRuntimeEndpoints {
  val stored = secureStore.readValue(AUDIENCE_RUNTIME_ENDPOINTS_KEY)
    ?: return defaultAudienceRuntimeEndpoints(seedBaseUrl)
  val decoded = runCatching {
    AudienceJson.instance.decodeFromString<AudienceRuntimeEndpoints>(stored)
  }.getOrNull()
    ?: run {
      secureStore.clearValue(AUDIENCE_RUNTIME_ENDPOINTS_KEY)
      return defaultAudienceRuntimeEndpoints(seedBaseUrl)
    }

  val sanitized = sanitizeAudienceRuntimeEndpoints(decoded, seedBaseUrl)
  if (sanitized != decoded) {
    writeAudienceRuntimeEndpoints(
      secureStore = secureStore,
      seedBaseUrl = seedBaseUrl,
      endpoints = sanitized,
    )
  }
  return sanitized
}

internal suspend fun writeAudienceRuntimeEndpoints(
  secureStore: AudienceSecureStore,
  seedBaseUrl: String,
  endpoints: AudienceRuntimeEndpoints,
) {
  val sanitized = sanitizeAudienceRuntimeEndpoints(endpoints, seedBaseUrl)
  secureStore.writeValue(
    AUDIENCE_RUNTIME_ENDPOINTS_KEY,
    AudienceJson.instance.encodeToString(
      AudienceRuntimeEndpoints.serializer(),
      sanitized,
    ),
  )
}
