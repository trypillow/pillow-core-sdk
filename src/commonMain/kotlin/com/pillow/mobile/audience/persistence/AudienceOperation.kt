package com.pillow.mobile.audience.persistence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

internal enum class AudienceOperationType {
  BOOTSTRAP,
  HEARTBEAT,
  IDENTIFY,
  SET_USER_PROPERTIES,
  RESET,
  END_SESSION,
}

internal enum class AudienceOperationStatus {
  PENDING,
}

@Serializable
internal data class BootstrapOperationPayload(
  @SerialName("client_session_id") val clientSessionId: String,
)

@Serializable
internal data class HeartbeatOperationPayload(
  @SerialName("session_id") val sessionId: String,
  val bucket: Long,
)

@Serializable
internal data class IdentifyOperationPayload(
  @SerialName("session_id") val sessionId: String,
  @SerialName("external_id") val externalId: String,
)

@Serializable
internal data class SetUserPropertiesOperationPayload(
  @SerialName("session_id") val sessionId: String,
  @SerialName("user_properties") val userProperties: Map<String, JsonElement>,
)

@Serializable
internal data class ResetOperationPayload(
  @SerialName("session_id") val sessionId: String,
  @SerialName("new_anonymous_id") val newAnonymousId: String,
  @SerialName("new_client_session_id") val newClientSessionId: String,
)

@Serializable
internal data class EndSessionOperationPayload(
  @SerialName("session_id") val sessionId: String,
)

internal data class AudienceOperationRecord(
  val id: String,
  val type: AudienceOperationType,
  val dedupeKey: String,
  val payloadJson: String,
  val status: AudienceOperationStatus,
  val attemptCount: Long,
  val nextAttemptAtEpochMs: Long,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)
