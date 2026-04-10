package com.pillow.mobile.audience.persistence

import app.cash.sqldelight.db.SqlDriver
import com.pillow.mobile.audience.runtime.AudienceJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

internal data class InstallationStateRecord(
  val installationId: String,
  val anonymousId: String,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)

internal data class SessionStateRecord(
  val sessionId: String?,
  val identified: Boolean,
  val lastBootstrapAtEpochMs: Long?,
  val lastHeartbeatAtEpochMs: Long?,
  val updatedAtEpochMs: Long,
)

internal data class ProfileStateRecord(
  val externalId: String?,
  val userProperties: Map<String, JsonElement>?,
  val updatedAtEpochMs: Long,
)

internal data class AudienceLocalSnapshot(
  val installation: InstallationStateRecord?,
  val session: SessionStateRecord?,
  val profile: ProfileStateRecord?,
)

internal class AudienceStateStore(
  driver: SqlDriver,
) {
  private val database = AudienceDatabase(driver)
  private val queries = database.audienceStateQueries

  fun readSnapshot(): AudienceLocalSnapshot =
    AudienceLocalSnapshot(
      installation = readInstallation(),
      session = readSession(),
      profile = readProfile(),
    )

  fun readInstallation(): InstallationStateRecord? =
    queries.selectInstallation { _, installationId, anonymousId, createdAt, updatedAt ->
      InstallationStateRecord(
        installationId = installationId,
        anonymousId = anonymousId,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = updatedAt,
      )
    }.executeAsOneOrNull()

  fun saveInstallation(record: InstallationStateRecord) {
    queries.upsertInstallation(
      installation_id = record.installationId,
      anonymous_id = record.anonymousId,
      created_at_epoch_ms = record.createdAtEpochMs,
      updated_at_epoch_ms = record.updatedAtEpochMs,
    )
  }

  fun readSession(): SessionStateRecord? =
    queries.selectSession { _, sessionId, identified, lastBootstrapAt, lastHeartbeatAt, updatedAt ->
      SessionStateRecord(
        sessionId = sessionId,
        identified = identified != 0L,
        lastBootstrapAtEpochMs = lastBootstrapAt,
        lastHeartbeatAtEpochMs = lastHeartbeatAt,
        updatedAtEpochMs = updatedAt,
      )
    }.executeAsOneOrNull()

  fun saveSession(record: SessionStateRecord) {
    queries.upsertSession(
      session_id = record.sessionId,
      identified = if (record.identified) 1L else 0L,
      last_bootstrap_at_epoch_ms = record.lastBootstrapAtEpochMs,
      last_heartbeat_at_epoch_ms = record.lastHeartbeatAtEpochMs,
      updated_at_epoch_ms = record.updatedAtEpochMs,
    )
  }

  fun clearSession() {
    queries.clearSession()
  }

  fun clearProfile() {
    queries.clearProfile()
  }

  fun readProfile(): ProfileStateRecord? =
    queries.selectProfile { _, externalId, userPropertiesJson, updatedAt ->
      ProfileStateRecord(
        externalId = externalId,
        userProperties = decodeUserProperties(userPropertiesJson),
        updatedAtEpochMs = updatedAt,
      )
    }.executeAsOneOrNull()

  fun saveProfile(record: ProfileStateRecord) {
    queries.upsertProfile(
      external_id = record.externalId,
      user_properties_json = encodeUserProperties(record.userProperties),
      updated_at_epoch_ms = record.updatedAtEpochMs,
    )
  }

  fun upsertOperation(record: AudienceOperationRecord) {
    queries.upsertOperation(
      id = record.id,
      type = record.type.name,
      dedupe_key = record.dedupeKey,
      payload_json = record.payloadJson,
      status = record.status.name.lowercase(),
      attempt_count = record.attemptCount,
      next_attempt_at_epoch_ms = record.nextAttemptAtEpochMs,
      created_at_epoch_ms = record.createdAtEpochMs,
      updated_at_epoch_ms = record.updatedAtEpochMs,
    )
  }

  fun readDueOperations(nowEpochMs: Long): List<AudienceOperationRecord> =
    queries.selectDueOperations(nowEpochMs) { id, type, dedupeKey, payloadJson, status, attempts, nextAt, createdAt, updatedAt ->
      AudienceOperationRecord(
        id = id,
        type = AudienceOperationType.valueOf(type),
        dedupeKey = dedupeKey,
        payloadJson = payloadJson,
        status = AudienceOperationStatus.valueOf(status.uppercase()),
        attemptCount = attempts,
        nextAttemptAtEpochMs = nextAt,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = updatedAt,
      )
    }.executeAsList()

  fun readAllOperations(): List<AudienceOperationRecord> =
    queries.selectAllOperations { id, type, dedupeKey, payloadJson, status, attempts, nextAt, createdAt, updatedAt ->
      AudienceOperationRecord(
        id = id,
        type = AudienceOperationType.valueOf(type),
        dedupeKey = dedupeKey,
        payloadJson = payloadJson,
        status = AudienceOperationStatus.valueOf(status.uppercase()),
        attemptCount = attempts,
        nextAttemptAtEpochMs = nextAt,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = updatedAt,
      )
    }.executeAsList()

  fun deleteOperation(id: String) {
    queries.deleteOperation(id)
  }

  fun rescheduleOperation(
    id: String,
    attemptCount: Long,
    nextAttemptAtEpochMs: Long,
    updatedAtEpochMs: Long,
  ) {
    queries.updateOperationSchedule(
      status = AudienceOperationStatus.PENDING.name.lowercase(),
      attempt_count = attemptCount,
      next_attempt_at_epoch_ms = nextAttemptAtEpochMs,
      updated_at_epoch_ms = updatedAtEpochMs,
      id = id,
    )
  }

  fun deleteOperationsMatching(predicate: (AudienceOperationRecord) -> Boolean) {
    readAllOperations().filter(predicate).forEach { deleteOperation(it.id) }
  }

  fun transaction(block: () -> Unit) {
    database.transaction(noEnclosing = true) {
      block()
    }
  }

  private fun encodeUserProperties(userProperties: Map<String, JsonElement>?): String? =
    userProperties?.let { AudienceJson.instance.encodeToString(it) }

  private fun decodeUserProperties(raw: String?): Map<String, JsonElement>? =
    raw?.let { AudienceJson.instance.decodeFromString(it) }
}
