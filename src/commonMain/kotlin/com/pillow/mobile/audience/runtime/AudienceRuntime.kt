package com.pillow.mobile.audience.runtime

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonElement

public enum class AudiencePlatform(internal val wireValue: String) {
  IOS("ios"),
  ANDROID("android"),
}

public enum class AudienceStatus {
  UNINITIALIZED,
  IDLE,
  BOOTSTRAPPING,
  ACTIVE_ANONYMOUS,
  ACTIVE_IDENTIFIED,
  RESETTING,
  ENDING,
  ERROR_RECOVERABLE,
  ERROR_FATAL,
}

public data class AudienceErrorState(
  val code: String,
  val message: String,
  val fatal: Boolean,
)

public data class AudienceState(
  val status: AudienceStatus,
  val installationId: String?,
  val anonymousId: String?,
  val sessionId: String?,
  val identified: Boolean,
  val lastError: AudienceErrorState?,
)

public data class AudienceClientConfig(
  val baseUrl: String,
  val publishableKey: String,
  val platform: AudiencePlatform,
  val sdkVersion: String,
  val heartbeatInterval: Duration = 60.seconds,
  val requestTimeout: Duration = 15.seconds,
  val logger: AudienceLogger = NoopAudienceLogger,
)

public interface AudienceClient {
  public suspend fun start()

  public suspend fun onAppForeground(forceHeartbeat: Boolean = false): AudienceState

  public suspend fun onAppBackground()

  public suspend fun identify(
    externalId: String,
    userProperties: Map<String, JsonElement>? = null,
  ): AudienceState

  public suspend fun setUserProperties(userProperties: Map<String, JsonElement>?)

  public suspend fun reset(): AudienceState

  public suspend fun endSession()

  public suspend fun currentSessionToken(): String?

  public fun state(): kotlinx.coroutines.flow.StateFlow<AudienceState>
}

public interface AudienceHttpClient {
  public suspend fun postJson(
    baseUrl: String,
    path: String,
    body: String,
    headers: Map<String, String>,
  ): AudienceHttpResponse
}

public data class AudienceHttpResponse(
  val statusCode: Int,
  val body: String,
)

public interface AudienceSecureStore {
  public suspend fun readSessionToken(): String?

  public suspend fun writeSessionToken(token: String)

  public suspend fun clearSessionToken()

  public suspend fun readValue(key: String): String?

  public suspend fun writeValue(key: String, value: String)

  public suspend fun clearValue(key: String)
}

public data class AudienceMetadata(
  val appName: String? = null,
  val appVersion: String? = null,
  val appBuild: String? = null,
  val osName: String? = null,
  val osVersion: String? = null,
  val deviceManufacturer: String? = null,
  val deviceModel: String? = null,
  val locale: String? = null,
  val timezone: String? = null,
)

public interface AudienceMetadataProvider {
  public fun current(): AudienceMetadata
}

public interface AudienceClock {
  public fun nowEpochMillis(): Long
}

public interface AudienceUuidGenerator {
  public fun generate(): String
}

public interface AudienceLogger {
  public fun debug(message: String)

  public fun info(message: String)

  public fun warn(message: String, throwable: Throwable? = null)

  public fun error(message: String, throwable: Throwable? = null)
}

public object NoopAudienceLogger : AudienceLogger {
  override fun debug(message: String) = Unit

  override fun info(message: String) = Unit

  override fun warn(message: String, throwable: Throwable?) = Unit

  override fun error(message: String, throwable: Throwable?) = Unit
}

internal data class AudienceDependencies(
  val httpClient: AudienceHttpClient,
  val secureStore: AudienceSecureStore,
  val metadataProvider: AudienceMetadataProvider,
  val sqlDriverFactory: AudienceSqlDriverFactory,
  val installSentinel: AudienceInstallSentinel,
  val clock: AudienceClock,
  val uuidGenerator: AudienceUuidGenerator,
  val logger: AudienceLogger,
)

internal interface AudienceSqlDriverFactory {
  fun create(): app.cash.sqldelight.db.SqlDriver
}

/**
 * Detects fresh installs (or reinstalls) by writing a sentinel to a location
 * that does NOT survive app uninstall. On Android this is `noBackupFilesDir`
 * (excluded from auto-backup); on iOS it is `NSUserDefaults` (deleted with app sandbox).
 *
 * If the sentinel is absent, the SDK treats the launch as a new installation
 * and generates a fresh installationId.
 */
internal interface AudienceInstallSentinel {
  fun exists(): Boolean
  fun mark()
}
