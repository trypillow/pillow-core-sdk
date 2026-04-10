package com.pillow.mobile.audience.api

import com.pillow.mobile.audience.runtime.AudienceRuntimeEndpoints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class AudienceBootstrapRequest(
  @SerialName("installation_id") val installationId: String,
  @SerialName("anonymous_id") val anonymousId: String,
  @SerialName("client_session_id") val clientSessionId: String,
  val platform: String,
  @SerialName("sdk_version") val sdkVersion: String? = null,
  @SerialName("app_name") val appName: String? = null,
  @SerialName("app_version") val appVersion: String? = null,
  @SerialName("app_build") val appBuild: String? = null,
  @SerialName("os_name") val osName: String? = null,
  @SerialName("os_version") val osVersion: String? = null,
  @SerialName("device_manufacturer") val deviceManufacturer: String? = null,
  @SerialName("device_model") val deviceModel: String? = null,
  val locale: String? = null,
  val timezone: String? = null,
)

@Serializable
internal data class AudienceBootstrapResponse(
  @SerialName("session_token") val sessionToken: String,
  @SerialName("token_expires_at") val tokenExpiresAt: String,
  val identified: Boolean,
  val endpoints: AudienceRuntimeEndpoints,
)

@Serializable
internal data class AudienceIdentifyRequest(
  @SerialName("session_token") val sessionToken: String,
  @SerialName("external_id") val externalId: String,
)

@Serializable
internal data class AudienceSetUserPropertiesRequest(
  @SerialName("session_token") val sessionToken: String,
  @SerialName("user_properties") val userProperties: Map<String, JsonElement>,
)

@Serializable
internal data class AudienceSetUserPropertiesResponse(
  val ok: Boolean,
)

@Serializable
internal data class AudienceIdentifyResponse(
  @SerialName("session_token") val sessionToken: String,
  @SerialName("token_expires_at") val tokenExpiresAt: String,
  val identified: Boolean,
  val merged: Boolean,
  val endpoints: AudienceRuntimeEndpoints,
)

@Serializable
internal data class AudienceHeartbeatRequest(
  @SerialName("session_token") val sessionToken: String,
  @SerialName("app_name") val appName: String? = null,
  @SerialName("app_version") val appVersion: String? = null,
  @SerialName("app_build") val appBuild: String? = null,
  @SerialName("os_name") val osName: String? = null,
  @SerialName("os_version") val osVersion: String? = null,
  @SerialName("device_manufacturer") val deviceManufacturer: String? = null,
  @SerialName("device_model") val deviceModel: String? = null,
  val locale: String? = null,
  val timezone: String? = null,
)

@Serializable
internal data class AudienceHeartbeatResponse(
  val ok: Boolean,
  @SerialName("session_token") val sessionToken: String? = null,
  @SerialName("token_expires_at") val tokenExpiresAt: String? = null,
  val endpoints: AudienceRuntimeEndpoints,
)

@Serializable
internal data class AudienceResetRequest(
  @SerialName("session_token") val sessionToken: String,
  @SerialName("installation_id") val installationId: String,
  @SerialName("anonymous_id") val anonymousId: String,
  @SerialName("client_session_id") val clientSessionId: String,
  @SerialName("new_anonymous_id") val newAnonymousId: String,
  @SerialName("new_client_session_id") val newClientSessionId: String,
  val platform: String,
  @SerialName("sdk_version") val sdkVersion: String? = null,
  @SerialName("app_name") val appName: String? = null,
  @SerialName("app_version") val appVersion: String? = null,
  @SerialName("app_build") val appBuild: String? = null,
  @SerialName("os_name") val osName: String? = null,
  @SerialName("os_version") val osVersion: String? = null,
  @SerialName("device_manufacturer") val deviceManufacturer: String? = null,
  @SerialName("device_model") val deviceModel: String? = null,
  val locale: String? = null,
  val timezone: String? = null,
)

@Serializable
internal data class AudienceEndSessionRequest(
  @SerialName("session_token") val sessionToken: String,
)

@Serializable
internal data class AudienceEndSessionResponse(
  val ok: Boolean,
)

@Serializable
internal data class AudienceApiErrorResponse(
  val code: String? = null,
  val message: String? = null,
)
