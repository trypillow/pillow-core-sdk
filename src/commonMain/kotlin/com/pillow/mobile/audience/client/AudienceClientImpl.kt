package com.pillow.mobile.audience.client

import com.pillow.mobile.sdk.PillowSdkUserLogger
import com.pillow.mobile.sdk.toPillowSdkError
import com.pillow.mobile.audience.api.AudienceApiClient
import com.pillow.mobile.audience.api.AudienceBootstrapRequest
import com.pillow.mobile.audience.api.AudienceEndSessionRequest
import com.pillow.mobile.audience.api.AudienceHeartbeatRequest
import com.pillow.mobile.audience.api.AudienceIdentifyRequest
import com.pillow.mobile.audience.api.AudienceResetRequest
import com.pillow.mobile.audience.api.AudienceSetUserPropertiesRequest
import com.pillow.mobile.audience.persistence.AudienceLocalSnapshot
import com.pillow.mobile.audience.persistence.AudienceOperationRecord
import com.pillow.mobile.audience.persistence.AudienceOperationStatus
import com.pillow.mobile.audience.persistence.AudienceOperationType
import com.pillow.mobile.audience.persistence.AudienceStateStore
import com.pillow.mobile.audience.persistence.BootstrapOperationPayload
import com.pillow.mobile.audience.persistence.EndSessionOperationPayload
import com.pillow.mobile.audience.persistence.HeartbeatOperationPayload
import com.pillow.mobile.audience.persistence.IdentifyOperationPayload
import com.pillow.mobile.audience.persistence.InstallationStateRecord
import com.pillow.mobile.audience.persistence.SetUserPropertiesOperationPayload
import com.pillow.mobile.audience.persistence.ProfileStateRecord
import com.pillow.mobile.audience.persistence.ResetOperationPayload
import com.pillow.mobile.audience.persistence.SessionStateRecord
import com.pillow.mobile.audience.runtime.AudienceApiException
import com.pillow.mobile.audience.runtime.AudienceClient
import com.pillow.mobile.audience.runtime.AudienceClientConfig
import com.pillow.mobile.audience.runtime.AudienceDependencies
import com.pillow.mobile.audience.runtime.AudienceErrorState
import com.pillow.mobile.audience.runtime.AudienceJson
import com.pillow.mobile.audience.runtime.AudienceMetadata
import com.pillow.mobile.audience.runtime.AUDIENCE_RUNTIME_ENDPOINTS_KEY
import com.pillow.mobile.audience.runtime.AudienceRuntimeEndpoints
import com.pillow.mobile.audience.runtime.AudienceState
import com.pillow.mobile.audience.runtime.AudienceStatus
import com.pillow.mobile.audience.runtime.computeNextAttemptEpochMs
import com.pillow.mobile.audience.runtime.decodeAudienceSessionToken
import com.pillow.mobile.audience.runtime.readAudienceRuntimeEndpoints
import com.pillow.mobile.audience.runtime.writeAudienceRuntimeEndpoints
import com.pillow.mobile.study.runtime.writeLaunchStudyInstruction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

internal class AudienceClientImpl(
  private val config: AudienceClientConfig,
  dependencies: AudienceDependencies,
) : AudienceClient {
  private val secureStore = dependencies.secureStore
  private val apiClient = AudienceApiClient(
    publishableKey = config.publishableKey,
    httpClient = dependencies.httpClient,
    seedBaseUrlProvider = { config.baseUrl },
    runtimeApiBaseUrlProvider = { readRuntimeEndpointsLocked().apiBaseUrl },
  )
  private val metadataProvider = dependencies.metadataProvider
  private val store = AudienceStateStore(dependencies.sqlDriverFactory.create())
  private val clock = dependencies.clock
  private val uuidGenerator = dependencies.uuidGenerator
  private val logger = dependencies.logger
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var retryJob: Job? = null
  private var sessionTokenCache: String? = null
  private var freshInstallOnLastStart = false
  private val state = MutableStateFlow(
    AudienceState(
      status = AudienceStatus.UNINITIALIZED,
      installationId = null,
      anonymousId = null,
      sessionId = null,
      identified = false,
      lastError = null,
    ),
  )
  private val mutex = Mutex()

  override fun state(): StateFlow<AudienceState> = state

  override fun wasFreshInstallOnLastStart(): Boolean = freshInstallOnLastStart

  override suspend fun start() {
    mutex.withLock {
      initializeLocked()
    }
  }

  override suspend fun onAppForeground(forceHeartbeat: Boolean): AudienceState =
    mutex.withLock {
      initializeLocked()
      drainQueueLocked()

      var snapshot = freshSnapshot()
      var sessionToken = readSessionTokenLocked()
      if (snapshot.session?.sessionId != null && sessionToken.isNullOrBlank()) {
        clearSessionLocked(snapshot)
        snapshot = freshSnapshot()
        sessionToken = null
      }

      val session = snapshot.session
      val sessionId = session?.sessionId
      if (sessionId.isNullOrBlank() || sessionToken.isNullOrBlank()) {
        enqueueBootstrapLocked()
        drainQueueLocked()
      } else if (forceHeartbeat || shouldHeartbeat(session.lastHeartbeatAtEpochMs)) {
        enqueueHeartbeatLocked(sessionId)
        drainQueueLocked()
        // If the heartbeat failed and cleared the session, re-bootstrap immediately
        // instead of waiting for the next foreground cycle.
        if (freshSnapshot().session?.sessionId == null) {
          enqueueBootstrapLocked()
          drainQueueLocked()
        }
      } else {
        publishStateLocked(snapshot)
      }

      state.value
    }

  override suspend fun onAppBackground() {
    mutex.withLock {
      initializeLocked()
      drainQueueLocked()
    }
  }

  override suspend fun identify(
    externalId: String,
    userProperties: Map<String, JsonElement>?,
  ): AudienceState =
    mutex.withLock {
      initializeLocked()
      val trimmedExternalId = externalId.trim()
      require(trimmedExternalId.isNotEmpty()) { "externalId must not be blank" }

      val snapshot = freshSnapshot()
      val profile = snapshot.profile
      store.saveProfile(
        ProfileStateRecord(
          externalId = trimmedExternalId,
          userProperties = userProperties ?: profile?.userProperties,
          updatedAtEpochMs = now(),
        ),
      )

      ensureActiveSessionLocked()
      val activeSessionId = freshSnapshot().session?.sessionId
      if (!activeSessionId.isNullOrBlank()) {
        enqueueIdentifyLocked(activeSessionId, trimmedExternalId)
        drainQueueLocked()
      }
      state.value
    }

  override suspend fun setUserProperties(userProperties: Map<String, JsonElement>?) {
    mutex.withLock {
      initializeLocked()
      val snapshot = freshSnapshot()
      val profile = snapshot.profile
      store.saveProfile(
        ProfileStateRecord(
          externalId = profile?.externalId,
          userProperties = userProperties,
          updatedAtEpochMs = now(),
        ),
      )

      ensureActiveSessionLocked()
      val activeSessionId = freshSnapshot().session?.sessionId
      logger.debug("setUserProperties: sessionId=$activeSessionId userProperties=${userProperties?.keys}")
      if (!activeSessionId.isNullOrBlank() && userProperties != null && userProperties.isNotEmpty()) {
        enqueueSetUserPropertiesLocked(activeSessionId, userProperties)
        drainQueueLocked()
      }
      publishStateLocked(freshSnapshot())
    }
  }

  override suspend fun reset(): AudienceState =
    mutex.withLock {
      initializeLocked()
      val snapshot = freshSnapshot()
      val newAnonymousId = uuidGenerator.generate()
      val existingInstallation = requireNotNull(snapshot.installation) {
        "Audience installation state is missing"
      }

      val activeSessionId = snapshot.session?.sessionId
      if (activeSessionId.isNullOrBlank() || readSessionTokenLocked().isNullOrBlank()) {
        store.saveInstallation(
          existingInstallation.copy(
            anonymousId = newAnonymousId,
            updatedAtEpochMs = now(),
          ),
        )
        clearSessionLocked(snapshot)
        enqueueBootstrapLocked()
      } else {
        enqueueResetLocked(
          sessionId = activeSessionId,
          newAnonymousId = newAnonymousId,
        )
      }

      drainQueueLocked()
      state.value
    }

  override suspend fun endSession() {
    mutex.withLock {
      initializeLocked()
      val snapshot = freshSnapshot()
      val sessionId = snapshot.session?.sessionId
      val token = readSessionTokenLocked()
      if (sessionId.isNullOrBlank() || token.isNullOrBlank()) {
        clearSessionLocked(snapshot)
        publishStateLocked(freshSnapshot())
        return@withLock
      }

      enqueueEndSessionLocked(sessionId)
      drainQueueLocked()
    }
  }

  override suspend fun currentSessionToken(): String? =
    mutex.withLock {
      initializeLocked()
      readSessionTokenLocked()
    }

  private suspend fun initializeLocked() {
    if (state.value.status != AudienceStatus.UNINITIALIZED) {
      return
    }

    val now = now()
    val installation = store.readInstallation()
    val publishableKeyChanged = detectPublishableKeyChange()

    // The SQLite installation row is the source of truth for "is this a fresh install".
    // Absence means either a brand-new install or a reinstall that wiped the app sandbox;
    // either way, any keychain residue from a previous lifetime is now stale.
    // Pub-key change rotates the install_id so the previous account stops seeing this device.
    val needsFreshInstall = installation == null || publishableKeyChanged
    if (needsFreshInstall) {
      if (installation == null) {
        logger.info("No installation row found, treating as fresh install")
      } else if (publishableKeyChanged) {
        logger.info("Publishable key changed, clearing cached state")
      }
      store.transaction {
        store.clearSession()
        store.clearProfile()
        store.deleteOperationsMatching { true }
      }
      clearSessionTokenLocked()
      secureStore.clearValue(AUDIENCE_RUNTIME_ENDPOINTS_KEY)
      writeLaunchStudyInstruction(secureStore, null)
      store.saveInstallation(
        InstallationStateRecord(
          installationId = uuidGenerator.generate(),
          anonymousId = uuidGenerator.generate(),
          createdAtEpochMs = now,
          updatedAtEpochMs = now,
        ),
      )
    }
    freshInstallOnLastStart = needsFreshInstall

    val snapshot = freshSnapshot()
    if (snapshot.session?.sessionId != null && readSessionTokenLocked().isNullOrBlank()) {
      clearSessionLocked(snapshot)
    }
    publishStateLocked(freshSnapshot(), AudienceStatus.IDLE)
  }

  private suspend fun detectPublishableKeyChange(): Boolean {
    val storedKey = secureStore.readValue(PUBLISHABLE_KEY_STORE_KEY)
    val currentKey = config.publishableKey
    if (storedKey != currentKey) {
      secureStore.writeValue(PUBLISHABLE_KEY_STORE_KEY, currentKey)
      return storedKey != null
    }
    return false
  }

  private suspend fun ensureActiveSessionLocked() {
    val snapshot = freshSnapshot()
    val token = readSessionTokenLocked()
    if (snapshot.session?.sessionId.isNullOrBlank() || token.isNullOrBlank()) {
      enqueueBootstrapLocked()
      drainQueueLocked()
    }
  }

  private fun scheduleRetry(delayMs: Long) {
    retryJob?.cancel()
    retryJob = scope.launch {
      delay(delayMs.coerceAtLeast(0L))
      mutex.withLock {
        drainQueueLocked()
      }
    }
  }

  private fun cancelPendingRetry() {
    retryJob?.cancel()
    retryJob = null
  }

  private suspend fun drainQueueLocked() {
    while (true) {
      val nextOperation = store.readDueOperations(now()).firstOrNull() ?: return
      cancelPendingRetry()
      if (now() - nextOperation.createdAtEpochMs > OPERATION_TTL_MS) {
        logger.debug(
          "Dropping expired operation ${nextOperation.type} (age ${now() - nextOperation.createdAtEpochMs}ms)",
        )
        store.deleteOperation(nextOperation.id)
        continue
      }
      val shouldContinue = processOperationLocked(nextOperation)
      if (!shouldContinue) {
        return
      }
    }
  }

  private suspend fun processOperationLocked(operation: AudienceOperationRecord): Boolean {
    return try {
      when (operation.type) {
        AudienceOperationType.BOOTSTRAP -> performBootstrapLocked(operation)
        AudienceOperationType.HEARTBEAT -> performHeartbeatLocked(operation)
        AudienceOperationType.IDENTIFY -> performIdentifyLocked(operation)
        AudienceOperationType.SET_USER_PROPERTIES -> performSetUserPropertiesLocked(operation)
        AudienceOperationType.RESET -> performResetLocked(operation)
        AudienceOperationType.END_SESSION -> performEndSessionLocked(operation)
      }
      true
    } catch (error: AudienceApiException) {
      handleFatalOperationErrorLocked(operation, error.errorState)
      false
    } catch (error: Throwable) {
      val nextAttempt = computeNextAttemptEpochMs(now(), operation.attemptCount + 1L)
      store.rescheduleOperation(
        id = operation.id,
        attemptCount = operation.attemptCount + 1L,
        nextAttemptAtEpochMs = nextAttempt,
        updatedAtEpochMs = now(),
      )
      scheduleRetry(nextAttempt - now())
      publishStateLocked(
        freshSnapshot(),
        status = AudienceStatus.ERROR_RECOVERABLE,
        lastError = AudienceErrorState(
          code = "retryable_error",
          message = error.message ?: "Audience operation failed",
          fatal = false,
        ),
      )
      PillowSdkUserLogger.emit(logger, error.toPillowSdkError())
      false
    }
  }

  private suspend fun performBootstrapLocked(operation: AudienceOperationRecord) {
    val payload = decodePayload<BootstrapOperationPayload>(operation.payloadJson)
    val snapshot = freshSnapshot()
    val existingSessionId = snapshot.session?.sessionId
    val existingSessionToken = readSessionTokenLocked()
    if (!existingSessionId.isNullOrBlank() && !existingSessionToken.isNullOrBlank()) {
      store.deleteOperation(operation.id)
      publishStateLocked(snapshot)
      return
    }
    val installation = requireNotNull(snapshot.installation) {
      "Audience installation state is missing"
    }
    val profile = snapshot.profile
    val metadata = metadataProvider.current()

    publishStateLocked(snapshot, AudienceStatus.BOOTSTRAPPING)

    val response = apiClient.bootstrap(
      AudienceBootstrapRequest(
        installationId = installation.installationId,
        anonymousId = installation.anonymousId,
        clientSessionId = payload.clientSessionId,
        platform = config.platform.wireValue,
        sdkVersion = config.sdkVersion,
        appName = metadata.appName,
        appVersion = metadata.appVersion,
        appBuild = metadata.appBuild,
        osName = metadata.osName,
        osVersion = metadata.osVersion,
        deviceManufacturer = metadata.deviceManufacturer,
        deviceModel = metadata.deviceModel,
        locale = metadata.locale,
        timezone = metadata.timezone,
      ),
    )

    val claims = decodeAudienceSessionToken(response.sessionToken)
    writeRuntimeEndpointsLocked(response.endpoints)
    writeSessionTokenLocked(response.sessionToken)
    writeLaunchStudyInstruction(secureStore, response.launchStudy)
    store.transaction {
      store.saveSession(
        SessionStateRecord(
          sessionId = claims.sessionId,
          identified = response.identified,
          lastBootstrapAtEpochMs = now(),
          lastHeartbeatAtEpochMs = now(),
          updatedAtEpochMs = now(),
        ),
      )
      store.deleteOperation(operation.id)
    }
    publishStateLocked(freshSnapshot())
  }

  private suspend fun performHeartbeatLocked(operation: AudienceOperationRecord) {
    val payload = decodePayload<HeartbeatOperationPayload>(operation.payloadJson)
    val snapshot = freshSnapshot()
    val session = snapshot.session
    val sessionToken = readSessionTokenLocked()
    if (session == null || sessionToken.isNullOrBlank() || session.sessionId != payload.sessionId) {
      store.deleteOperation(operation.id)
      return
    }

    val metadata = metadataProvider.current()
    val response = apiClient.heartbeat(
      AudienceHeartbeatRequest(
        sessionToken = sessionToken,
        appName = metadata.appName,
        appVersion = metadata.appVersion,
        appBuild = metadata.appBuild,
        osName = metadata.osName,
        osVersion = metadata.osVersion,
        deviceManufacturer = metadata.deviceManufacturer,
        deviceModel = metadata.deviceModel,
        locale = metadata.locale,
        timezone = metadata.timezone,
      ),
    )

    val effectiveToken = response.sessionToken ?: sessionToken
    val claims = decodeAudienceSessionToken(effectiveToken)
    writeRuntimeEndpointsLocked(response.endpoints)
    writeSessionTokenLocked(effectiveToken)
    writeLaunchStudyInstruction(secureStore, response.launchStudy)
    store.transaction {
      store.saveSession(
        session.copy(
          sessionId = claims.sessionId,
          lastHeartbeatAtEpochMs = now(),
          updatedAtEpochMs = now(),
        ),
      )
      store.deleteOperation(operation.id)
    }
    publishStateLocked(freshSnapshot())
  }

  private suspend fun performIdentifyLocked(operation: AudienceOperationRecord) {
    val payload = decodePayload<IdentifyOperationPayload>(operation.payloadJson)
    val snapshot = freshSnapshot()
    val session = snapshot.session
    val sessionToken = readSessionTokenLocked()
    if (session == null || sessionToken.isNullOrBlank() || session.sessionId != payload.sessionId) {
      store.deleteOperation(operation.id)
      return
    }

    val response = apiClient.identify(
      AudienceIdentifyRequest(
        sessionToken = sessionToken,
        externalId = payload.externalId,
      ),
    )

    val claims = decodeAudienceSessionToken(response.sessionToken)
    writeRuntimeEndpointsLocked(response.endpoints)
    writeSessionTokenLocked(response.sessionToken)
    writeLaunchStudyInstruction(secureStore, response.launchStudy)
    store.transaction {
      store.saveSession(
        session.copy(
          sessionId = claims.sessionId,
          identified = true,
          updatedAtEpochMs = now(),
        ),
      )
      store.saveProfile(
        ProfileStateRecord(
          externalId = payload.externalId,
          userProperties = snapshot.profile?.userProperties,
          updatedAtEpochMs = now(),
        ),
      )
      store.deleteOperation(operation.id)
    }
    publishStateLocked(freshSnapshot())
  }

  private suspend fun performSetUserPropertiesLocked(operation: AudienceOperationRecord) {
    val payload = decodePayload<SetUserPropertiesOperationPayload>(operation.payloadJson)
    val snapshot = freshSnapshot()
    val session = snapshot.session
    val sessionToken = readSessionTokenLocked()
    logger.debug(
      "performSetUserProperties: session=${session?.sessionId} payloadSession=${payload.sessionId} hasToken=${!sessionToken.isNullOrBlank()}",
    )
    if (session == null || sessionToken.isNullOrBlank() || session.sessionId != payload.sessionId) {
      logger.debug("performSetUserProperties: dropping — session mismatch or missing token")
      store.deleteOperation(operation.id)
      return
    }

    logger.debug("performSetUserProperties: calling API with ${payload.userProperties.size} properties")
    apiClient.setUserProperties(
      AudienceSetUserPropertiesRequest(
        sessionToken = sessionToken,
        userProperties = payload.userProperties,
      ),
    )

    store.deleteOperation(operation.id)
    publishStateLocked(freshSnapshot())
  }

  private suspend fun performResetLocked(operation: AudienceOperationRecord) {
    val payload = decodePayload<ResetOperationPayload>(operation.payloadJson)
    val snapshot = freshSnapshot()
    val installation = requireNotNull(snapshot.installation) {
      "Audience installation state is missing"
    }
    val session = snapshot.session
    val sessionToken = readSessionTokenLocked()
    if (session == null || sessionToken.isNullOrBlank() || session.sessionId != payload.sessionId) {
      store.deleteOperation(operation.id)
      return
    }

    publishStateLocked(snapshot, AudienceStatus.RESETTING)

    val metadata = metadataProvider.current()
    val response = apiClient.reset(
      AudienceResetRequest(
        sessionToken = sessionToken,
        installationId = installation.installationId,
        anonymousId = installation.anonymousId,
        clientSessionId = uuidGenerator.generate(),
        newAnonymousId = payload.newAnonymousId,
        newClientSessionId = payload.newClientSessionId,
        platform = config.platform.wireValue,
        sdkVersion = config.sdkVersion,
        appName = metadata.appName,
        appVersion = metadata.appVersion,
        appBuild = metadata.appBuild,
        osName = metadata.osName,
        osVersion = metadata.osVersion,
        deviceManufacturer = metadata.deviceManufacturer,
        deviceModel = metadata.deviceModel,
        locale = metadata.locale,
        timezone = metadata.timezone,
      ),
    )

    val claims = decodeAudienceSessionToken(response.sessionToken)
    writeRuntimeEndpointsLocked(response.endpoints)
    writeSessionTokenLocked(response.sessionToken)
    writeLaunchStudyInstruction(secureStore, response.launchStudy)
    store.transaction {
      store.saveInstallation(
        installation.copy(
          anonymousId = payload.newAnonymousId,
          updatedAtEpochMs = now(),
        ),
      )
      store.saveSession(
        SessionStateRecord(
          sessionId = claims.sessionId,
          identified = response.identified,
          lastBootstrapAtEpochMs = now(),
          lastHeartbeatAtEpochMs = now(),
          updatedAtEpochMs = now(),
        ),
      )
      store.saveProfile(
        ProfileStateRecord(
          externalId = null,
          userProperties = null,
          updatedAtEpochMs = now(),
        ),
      )
      store.deleteOperation(operation.id)
    }
    publishStateLocked(freshSnapshot())
  }

  private suspend fun performEndSessionLocked(operation: AudienceOperationRecord) {
    val payload = decodePayload<EndSessionOperationPayload>(operation.payloadJson)
    val snapshot = freshSnapshot()
    val session = snapshot.session
    val sessionToken = readSessionTokenLocked()
    if (session == null || sessionToken.isNullOrBlank() || session.sessionId != payload.sessionId) {
      store.deleteOperation(operation.id)
      return
    }

    publishStateLocked(snapshot, AudienceStatus.ENDING)
    apiClient.endSession(AudienceEndSessionRequest(sessionToken))
    store.deleteOperation(operation.id)
    clearSessionLocked(snapshot)
    publishStateLocked(freshSnapshot(), AudienceStatus.IDLE)
  }

  private suspend fun handleFatalOperationErrorLocked(
    operation: AudienceOperationRecord,
    errorState: AudienceErrorState,
  ) {
    // The backend uses domain error codes (session_invalid, sdk_session_ended) but
    // Encore maps them to standard codes in the HTTP response (unauthenticated,
    // failed_precondition). Check both sets so recovery works regardless of format.
    if (
      errorState.code == "session_invalid" ||
      errorState.code == "sdk_session_ended" ||
      errorState.code == "unauthenticated" ||
      errorState.code == "failed_precondition"
    ) {
      clearSessionLocked(freshSnapshot())
    }
    store.deleteOperation(operation.id)
    publishStateLocked(
      freshSnapshot(),
      status = AudienceStatus.ERROR_FATAL,
      lastError = errorState,
    )
    PillowSdkUserLogger.emitApiError(logger, errorState)
  }

  private fun enqueueBootstrapLocked() {
    val timestamp = now()
    val payload = BootstrapOperationPayload(clientSessionId = uuidGenerator.generate())
    store.upsertOperation(
      AudienceOperationRecord(
        id = uuidGenerator.generate(),
        type = AudienceOperationType.BOOTSTRAP,
        // Keep bootstrap replaceable so an older retry cannot replay after a newer
        // session has already been established successfully.
        dedupeKey = "bootstrap",
        payloadJson = AudienceJson.instance.encodeToString(payload),
        status = AudienceOperationStatus.PENDING,
        attemptCount = 0L,
        nextAttemptAtEpochMs = timestamp,
        createdAtEpochMs = timestamp,
        updatedAtEpochMs = timestamp,
      ),
    )
  }

  private fun enqueueHeartbeatLocked(sessionId: String) {
    val timestamp = now()
    val bucket = timestamp / config.heartbeatInterval.inWholeMilliseconds
    val payload = HeartbeatOperationPayload(sessionId = sessionId, bucket = bucket)
    store.upsertOperation(
      AudienceOperationRecord(
        id = uuidGenerator.generate(),
        type = AudienceOperationType.HEARTBEAT,
        dedupeKey = "heartbeat:$sessionId:$bucket",
        payloadJson = AudienceJson.instance.encodeToString(payload),
        status = AudienceOperationStatus.PENDING,
        attemptCount = 0L,
        nextAttemptAtEpochMs = timestamp,
        createdAtEpochMs = timestamp,
        updatedAtEpochMs = timestamp,
      ),
    )
  }

  private fun enqueueIdentifyLocked(
    sessionId: String,
    externalId: String,
  ) {
    val timestamp = now()
    val payload = IdentifyOperationPayload(
      sessionId = sessionId,
      externalId = externalId,
    )
    store.upsertOperation(
      AudienceOperationRecord(
        id = uuidGenerator.generate(),
        type = AudienceOperationType.IDENTIFY,
        dedupeKey = "identify:$sessionId:$externalId",
        payloadJson = AudienceJson.instance.encodeToString(payload),
        status = AudienceOperationStatus.PENDING,
        attemptCount = 0L,
        nextAttemptAtEpochMs = timestamp,
        createdAtEpochMs = timestamp,
        updatedAtEpochMs = timestamp,
      ),
    )
  }

  private fun enqueueSetUserPropertiesLocked(
    sessionId: String,
    userProperties: Map<String, JsonElement>,
  ) {
    val timestamp = now()
    val payload = SetUserPropertiesOperationPayload(
      sessionId = sessionId,
      userProperties = userProperties,
    )
    store.upsertOperation(
      AudienceOperationRecord(
        id = uuidGenerator.generate(),
        type = AudienceOperationType.SET_USER_PROPERTIES,
        dedupeKey = "set-user-properties:$sessionId",
        payloadJson = AudienceJson.instance.encodeToString(payload),
        status = AudienceOperationStatus.PENDING,
        attemptCount = 0L,
        nextAttemptAtEpochMs = timestamp,
        createdAtEpochMs = timestamp,
        updatedAtEpochMs = timestamp,
      ),
    )
  }

  private fun enqueueResetLocked(sessionId: String, newAnonymousId: String) {
    val timestamp = now()
    store.deleteOperationsMatching { record ->
      (record.type == AudienceOperationType.HEARTBEAT ||
        record.type == AudienceOperationType.IDENTIFY ||
        record.type == AudienceOperationType.SET_USER_PROPERTIES ||
        record.type == AudienceOperationType.END_SESSION) &&
        record.payloadJson.contains(sessionId)
    }

    val payload = ResetOperationPayload(
      sessionId = sessionId,
      newAnonymousId = newAnonymousId,
      newClientSessionId = uuidGenerator.generate(),
    )
    store.upsertOperation(
      AudienceOperationRecord(
        id = uuidGenerator.generate(),
        type = AudienceOperationType.RESET,
        dedupeKey = "reset:$newAnonymousId",
        payloadJson = AudienceJson.instance.encodeToString(payload),
        status = AudienceOperationStatus.PENDING,
        attemptCount = 0L,
        nextAttemptAtEpochMs = timestamp,
        createdAtEpochMs = timestamp,
        updatedAtEpochMs = timestamp,
      ),
    )
  }

  private fun enqueueEndSessionLocked(sessionId: String) {
    val timestamp = now()
    val payload = EndSessionOperationPayload(sessionId = sessionId)
    store.upsertOperation(
      AudienceOperationRecord(
        id = uuidGenerator.generate(),
        type = AudienceOperationType.END_SESSION,
        dedupeKey = "end:$sessionId",
        payloadJson = AudienceJson.instance.encodeToString(payload),
        status = AudienceOperationStatus.PENDING,
        attemptCount = 0L,
        nextAttemptAtEpochMs = timestamp,
        createdAtEpochMs = timestamp,
        updatedAtEpochMs = timestamp,
      ),
    )
  }

  private suspend fun clearSessionLocked(snapshot: AudienceLocalSnapshot) {
    store.transaction {
      store.clearSession()
      store.deleteOperationsMatching { operation ->
        operation.type != AudienceOperationType.BOOTSTRAP
      }
    }
    clearSessionTokenLocked()
    publishStateLocked(
      snapshot.copy(session = null),
      status = AudienceStatus.IDLE,
    )
  }

  private suspend fun readSessionTokenLocked(): String? {
    val cachedToken = sessionTokenCache
    if (!cachedToken.isNullOrBlank()) {
      return cachedToken
    }

    val storedToken = secureStore.readSessionToken()
    sessionTokenCache = storedToken
    return storedToken
  }

  private suspend fun writeSessionTokenLocked(token: String) {
    secureStore.writeSessionToken(token)
    sessionTokenCache = token
  }

  private suspend fun clearSessionTokenLocked() {
    secureStore.clearSessionToken()
    sessionTokenCache = null
  }

  private suspend fun readRuntimeEndpointsLocked(): AudienceRuntimeEndpoints =
    readAudienceRuntimeEndpoints(
      secureStore = secureStore,
      seedBaseUrl = config.baseUrl,
    )

  private suspend fun writeRuntimeEndpointsLocked(endpoints: AudienceRuntimeEndpoints) {
    writeAudienceRuntimeEndpoints(
      secureStore = secureStore,
      seedBaseUrl = config.baseUrl,
      endpoints = endpoints,
    )
  }

  private fun publishStateLocked(
    snapshot: AudienceLocalSnapshot,
    status: AudienceStatus? = null,
    lastError: AudienceErrorState? = null,
  ) {
    state.value = AudienceState(
      status = status ?: when {
        snapshot.session?.sessionId == null -> AudienceStatus.IDLE
        snapshot.session.identified -> AudienceStatus.ACTIVE_IDENTIFIED
        else -> AudienceStatus.ACTIVE_ANONYMOUS
      },
      installationId = snapshot.installation?.installationId,
      anonymousId = snapshot.installation?.anonymousId,
      sessionId = snapshot.session?.sessionId,
      identified = snapshot.session?.identified ?: false,
      lastError = lastError,
    )
  }

  private fun shouldHeartbeat(lastHeartbeatAtEpochMs: Long?): Boolean {
    if (lastHeartbeatAtEpochMs == null) {
      return true
    }
    return now() - lastHeartbeatAtEpochMs >= config.heartbeatInterval.inWholeMilliseconds
  }

  private fun freshSnapshot(): AudienceLocalSnapshot = store.readSnapshot()

  private fun now(): Long = clock.nowEpochMillis()

  private inline fun <reified T> decodePayload(payloadJson: String): T =
    AudienceJson.instance.decodeFromString(payloadJson)

  private companion object {
    const val OPERATION_TTL_MS: Long = 24L * 60L * 60L * 1_000L // 24 hours
    const val PUBLISHABLE_KEY_STORE_KEY: String = "audience_publishable_key"
  }
}
