package com.pillow.mobile.audience

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.pillow.mobile.audience.client.AudienceClientImpl
import com.pillow.mobile.audience.persistence.AudienceDatabase
import com.pillow.mobile.audience.runtime.AudienceClientConfig
import com.pillow.mobile.audience.runtime.AudienceClock
import com.pillow.mobile.audience.runtime.AudienceDependencies
import com.pillow.mobile.audience.runtime.AudienceHttpClient
import com.pillow.mobile.audience.runtime.AudienceHttpResponse
import com.pillow.mobile.audience.runtime.AudienceInstallSentinel
import com.pillow.mobile.audience.runtime.AudienceLogger
import com.pillow.mobile.audience.runtime.AudienceMetadata
import com.pillow.mobile.audience.runtime.AudienceMetadataProvider
import com.pillow.mobile.audience.runtime.AudiencePlatform
import com.pillow.mobile.audience.runtime.AudienceSecureStore
import com.pillow.mobile.audience.runtime.AudienceSqlDriverFactory
import com.pillow.mobile.audience.runtime.AudienceStatus
import com.pillow.mobile.audience.runtime.AudienceUuidGenerator
import com.pillow.mobile.study.runtime.LaunchStudyInstruction
import com.pillow.mobile.study.runtime.readLaunchStudyInstruction
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AudienceClientTest {
  @Test
  fun bootstrapCreatesActiveAnonymousSession() = runTest {
    val httpClient = FakeAudienceHttpClient().apply {
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(sessionId = 101, identified = false),
      )
    }
    val fixture = testFixture(httpClient = httpClient)

    fixture.client.start()
    val bootstrapped = fixture.client.onAppForeground()

    assertEquals(AudienceStatus.ACTIVE_ANONYMOUS, bootstrapped.status)
    assertEquals("101", bootstrapped.sessionId)
    assertNotNull(bootstrapped.installationId)
    assertNotNull(bootstrapped.anonymousId)
    assertEquals(listOf("/sdk/v1/bootstrap"), httpClient.paths)
  }

  @Test
  fun bootstrapPersistsLaunchStudyInstruction() = runTest {
    val secureStore = InMemoryAudienceSecureStore()
    val httpClient = FakeAudienceHttpClient().apply {
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(
          sessionId = 101,
          identified = false,
          launchStudy = LaunchStudyInstruction(
            studyId = "demo-launch",
            webDisplay = buildJsonObject {
              put("variant", "hero")
            },
          ),
        ),
      )
    }
    val fixture = testFixture(httpClient = httpClient, secureStore = secureStore)

    fixture.client.start()
    fixture.client.onAppForeground()

    assertEquals(
      LaunchStudyInstruction(
        studyId = "demo-launch",
        webDisplay = buildJsonObject {
          put("variant", "hero")
        },
      ),
      readLaunchStudyInstruction(secureStore),
    )
  }

  @Test
  fun heartbeatIsThrottledUntilIntervalElapses() = runTest {
    val httpClient = FakeAudienceHttpClient().apply {
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(sessionId = 101, identified = false),
      )
      enqueue(
        "/sdk/v1/session/heartbeat",
        heartbeatResponse(sessionId = 101),
      )
    }
    val clock = MutableClock()
    val fixture = testFixture(httpClient = httpClient, clock = clock)

    fixture.client.start()
    fixture.client.onAppForeground()
    fixture.client.onAppForeground()
    assertEquals(1, httpClient.paths.size)

    clock.advance(61_000L)
    val updated = fixture.client.onAppForeground()

    assertEquals(AudienceStatus.ACTIVE_ANONYMOUS, updated.status)
    assertEquals(
      listOf("/sdk/v1/bootstrap", "/sdk/v1/session/heartbeat"),
      httpClient.paths,
    )
  }

  @Test
  fun heartbeatClearsLaunchStudyWhenBackendStopsReturningIt() = runTest {
    val secureStore = InMemoryAudienceSecureStore()
    val httpClient = FakeAudienceHttpClient().apply {
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(
          sessionId = 101,
          identified = false,
          launchStudy = LaunchStudyInstruction(studyId = "demo-launch"),
        ),
      )
      enqueue(
        "/sdk/v1/session/heartbeat",
        heartbeatResponse(sessionId = 101, launchStudy = null),
      )
    }
    val clock = MutableClock()
    val fixture = testFixture(httpClient = httpClient, clock = clock, secureStore = secureStore)

    fixture.client.start()
    fixture.client.onAppForeground()
    assertEquals("demo-launch", readLaunchStudyInstruction(secureStore)?.studyId)

    clock.advance(61_000L)
    fixture.client.onAppForeground()

    assertNull(readLaunchStudyInstruction(secureStore))
  }

  @Test
  fun identifyPromotesTheSessionToIdentified() = runTest {
    val httpClient = FakeAudienceHttpClient().apply {
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(sessionId = 101, identified = false),
      )
      enqueue(
        "/sdk/v1/identify",
        identifyResponse(sessionId = 101, identified = true, merged = false),
      )
    }
    val fixture = testFixture(httpClient = httpClient)

    fixture.client.start()
    fixture.client.onAppForeground()
    val identified = fixture.client.identify("user-42")

    assertEquals(AudienceStatus.ACTIVE_IDENTIFIED, identified.status)
    assertEquals("101", identified.sessionId)
    assertEquals(
      listOf("/sdk/v1/bootstrap", "/sdk/v1/identify"),
      httpClient.paths,
    )
  }

  @Test
  fun identifyDoesNotRebootstrapWhenSecureStoreReadMissesAfterBootstrap() = runTest {
    val httpClient = FakeAudienceHttpClient().apply {
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(sessionId = 101, identified = false),
      )
      enqueue(
        "/sdk/v1/identify",
        identifyResponse(sessionId = 101, identified = true, merged = false),
      )
    }
    val fixture = testFixture(
      httpClient = httpClient,
      secureStore = WriteOnlyReadMissingSecureStore(),
    )

    fixture.client.start()
    fixture.client.onAppForeground()
    val identified = fixture.client.identify("user-42")

    assertEquals(AudienceStatus.ACTIVE_IDENTIFIED, identified.status)
    assertEquals(
      listOf("/sdk/v1/bootstrap", "/sdk/v1/identify"),
      httpClient.paths,
    )
  }

  @Test
  fun resetRotatesAnonymousIdentityAndReplacesSession() = runTest {
    val httpClient = FakeAudienceHttpClient().apply {
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(sessionId = 101, identified = false),
      )
      enqueue(
        "/sdk/v1/reset",
        bootstrapResponse(sessionId = 202, identified = false),
      )
    }
    val fixture = testFixture(httpClient = httpClient)

    fixture.client.start()
    val firstState = fixture.client.onAppForeground()
    val resetState = fixture.client.reset()

    assertEquals(AudienceStatus.ACTIVE_ANONYMOUS, resetState.status)
    assertEquals("202", resetState.sessionId)
    assertNotEquals(firstState.anonymousId, resetState.anonymousId)
    assertEquals(
      listOf("/sdk/v1/bootstrap", "/sdk/v1/reset"),
      httpClient.paths,
    )
  }

  @Test
  fun resetReplacesCachedLaunchStudyInstruction() = runTest {
    val secureStore = InMemoryAudienceSecureStore()
    val httpClient = FakeAudienceHttpClient().apply {
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(
          sessionId = 101,
          identified = false,
          launchStudy = LaunchStudyInstruction(studyId = "launch-before-reset"),
        ),
      )
      enqueue(
        "/sdk/v1/reset",
        bootstrapResponse(
          sessionId = 202,
          identified = false,
          launchStudy = LaunchStudyInstruction(studyId = "launch-after-reset"),
        ),
      )
    }
    val fixture = testFixture(httpClient = httpClient, secureStore = secureStore)

    fixture.client.start()
    fixture.client.onAppForeground()
    assertEquals("launch-before-reset", readLaunchStudyInstruction(secureStore)?.studyId)

    fixture.client.reset()

    assertEquals("launch-after-reset", readLaunchStudyInstruction(secureStore)?.studyId)
  }

  @Test
  fun invalidLaunchStudyInstructionIsDiscarded() = runTest {
    val secureStore = InMemoryAudienceSecureStore()
    val httpClient = FakeAudienceHttpClient().apply {
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(
          sessionId = 101,
          identified = false,
          launchStudyJson = """{"study_id":"   "}""",
        ),
      )
    }
    val fixture = testFixture(httpClient = httpClient, secureStore = secureStore)

    fixture.client.start()
    fixture.client.onAppForeground()

    assertNull(readLaunchStudyInstruction(secureStore))
  }

  @Test
  fun startupWithoutSecureTokenClearsTheStaleSession() = runTest {
    val httpClient = FakeAudienceHttpClient().apply {
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(sessionId = 101, identified = false),
      )
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(
          sessionId = 303,
          identified = false,
          tokenExpiresAt = "2026-01-01T00:10:00Z",
        ),
      )
    }
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    AudienceDatabase.Schema.create(driver)
    val driverFactory = SingleDriverFactory(driver)

    val firstFixture = testFixture(
      httpClient = httpClient,
      sqlDriverFactory = driverFactory,
      secureStore = InMemoryAudienceSecureStore(),
    )
    firstFixture.client.start()
    firstFixture.client.onAppForeground()

    val secondFixture = testFixture(
      httpClient = httpClient,
      sqlDriverFactory = driverFactory,
      secureStore = InMemoryAudienceSecureStore(),
    )

    secondFixture.client.start()
    assertNull(secondFixture.client.state().value.sessionId)

    val stateAfterForeground = secondFixture.client.onAppForeground()
    assertEquals("303", stateAfterForeground.sessionId)
    assertEquals(
      listOf("/sdk/v1/bootstrap", "/sdk/v1/bootstrap"),
      httpClient.paths,
    )
  }

  @Test
  fun staleBootstrapRetryCannotReplaceANewerActiveSession() = runTest {
    val httpClient = FakeAudienceHttpClient().apply {
      enqueueFailure("/sdk/v1/bootstrap", IOException("offline"))
      enqueueSuccess(
        "/sdk/v1/bootstrap",
        bootstrapResponse(sessionId = 101, identified = false),
      )
      enqueueSuccess(
        "/sdk/v1/session/heartbeat",
        heartbeatResponse(sessionId = 101),
      )
    }
    val clock = MutableClock()
    val fixture = testFixture(httpClient = httpClient, clock = clock)

    fixture.client.start()
    val failedForeground = fixture.client.onAppForeground()
    assertEquals(AudienceStatus.ERROR_RECOVERABLE, failedForeground.status)

    clock.advance(5_000L)
    val recovered = fixture.client.onAppForeground()
    assertEquals(AudienceStatus.ACTIVE_ANONYMOUS, recovered.status)
    assertEquals("101", recovered.sessionId)

    clock.advance(300_000L)
    val stable = fixture.client.onAppForeground()
    assertEquals("101", stable.sessionId)
    assertEquals(
      listOf(
        "/sdk/v1/bootstrap",
        "/sdk/v1/bootstrap",
        "/sdk/v1/session/heartbeat",
      ),
      httpClient.paths,
    )
  }

  @Test
  fun runtimeEndpointFromBootstrapIsUsedForSubsequentAudienceRequests() = runTest {
    val httpClient = FakeAudienceHttpClient().apply {
      enqueue(
        "/sdk/v1/bootstrap",
        bootstrapResponse(
          sessionId = 101,
          identified = false,
          apiBaseUrl = "https://runtime.example.test",
        ),
      )
      enqueue(
        "/sdk/v1/identify",
        identifyResponse(
          sessionId = 101,
          identified = true,
          merged = false,
          apiBaseUrl = "https://runtime.example.test",
        ),
      )
    }
    val fixture = testFixture(httpClient = httpClient)

    fixture.client.start()
    fixture.client.onAppForeground()
    fixture.client.identify("user-42")

    assertEquals(
      listOf("https://example.test", "https://runtime.example.test"),
      httpClient.baseUrls,
    )
  }
}

private data class TestFixture(
  val client: AudienceClientImpl,
  val secureStore: AudienceSecureStore,
)

private fun testFixture(
  httpClient: FakeAudienceHttpClient,
  clock: MutableClock = MutableClock(),
  uuidGenerator: SequenceUuidGenerator = SequenceUuidGenerator(),
  secureStore: AudienceSecureStore = InMemoryAudienceSecureStore(),
  sqlDriverFactory: AudienceSqlDriverFactory? = null,
  installSentinel: AudienceInstallSentinel = AlwaysFreshInstallSentinel(),
): TestFixture {
  val driverFactory = sqlDriverFactory ?: run {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    AudienceDatabase.Schema.create(driver)
    SingleDriverFactory(driver)
  }
  return TestFixture(
    client = AudienceClientImpl(
      config = AudienceClientConfig(
        baseUrl = "https://example.test",
        publishableKey = "pk_live_test",
        platform = AudiencePlatform.IOS,
        sdkVersion = "0.1.0-test",
      ),
      dependencies = AudienceDependencies(
        httpClient = httpClient,
        secureStore = secureStore,
        metadataProvider = FixedMetadataProvider(),
        sqlDriverFactory = driverFactory,
        installSentinel = installSentinel,
        clock = clock,
        uuidGenerator = uuidGenerator,
        logger = TestLogger,
      ),
    ),
    secureStore = secureStore,
  )
}

private class FakeAudienceHttpClient : AudienceHttpClient {
  val baseUrls = mutableListOf<String>()
  val paths = mutableListOf<String>()
  private val responses = mutableMapOf<String, ArrayDeque<FakeAudienceHttpResult>>()

  fun enqueue(path: String, body: String) {
    enqueueSuccess(path, body)
  }

  fun enqueueSuccess(path: String, body: String) {
    responses.getOrPut(path) { ArrayDeque() }.add(FakeAudienceHttpResult.Success(body))
  }

  fun enqueueFailure(path: String, error: Throwable) {
    responses.getOrPut(path) { ArrayDeque() }.add(FakeAudienceHttpResult.Failure(error))
  }

  override suspend fun postJson(
    baseUrl: String,
    path: String,
    body: String,
    headers: Map<String, String>,
  ): AudienceHttpResponse {
    baseUrls += baseUrl
    paths += path
    return when (val result = responses[path]?.removeFirstOrNull()) {
      is FakeAudienceHttpResult.Success -> AudienceHttpResponse(statusCode = 200, body = result.body)
      is FakeAudienceHttpResult.Failure -> throw result.error
      null -> error("No fake response queued for $path")
    }
  }
}

private sealed interface FakeAudienceHttpResult {
  data class Success(val body: String) : FakeAudienceHttpResult

  data class Failure(val error: Throwable) : FakeAudienceHttpResult
}

private class FixedMetadataProvider : AudienceMetadataProvider {
  override fun current(): AudienceMetadata =
    AudienceMetadata(
      appName = "Pillow Mobile Test",
      appVersion = "1.0.0",
      appBuild = "1",
      osName = "iOS",
      osVersion = "18.0",
      deviceManufacturer = "Apple",
      deviceModel = "iPhone",
      locale = "en-US",
      timezone = "UTC",
    )
}

private class MutableClock(
  private var now: Long = 1_710_000_000_000L,
) : AudienceClock {
  override fun nowEpochMillis(): Long = now

  fun advance(deltaMs: Long) {
    now += deltaMs
  }
}

private class SequenceUuidGenerator(
  private val values: ArrayDeque<String> = ArrayDeque(
    listOf(
      "installation-1",
      "anonymous-1",
      "bootstrap-op-1",
      "client-session-1",
      "identify-op-1",
      "reset-new-anon",
      "reset-op-1",
      "reset-client-session-1",
      "reset-request-client-session-1",
      "end-op-1",
      "spare-1",
      "spare-2",
    ),
  ),
) : AudienceUuidGenerator {
  override fun generate(): String = values.removeFirstOrNull()
    ?: error("SequenceUuidGenerator exhausted: add more values to the test fixture")
}

internal class InMemoryAudienceSecureStore : AudienceSecureStore {
  private val values = mutableMapOf<String, String>()

  override suspend fun readSessionToken(): String? = readValue(SESSION_TOKEN_KEY)

  override suspend fun writeSessionToken(token: String) {
    writeValue(SESSION_TOKEN_KEY, token)
  }

  override suspend fun clearSessionToken() {
    clearValue(SESSION_TOKEN_KEY)
  }

  override suspend fun readValue(key: String): String? = values[key]

  override suspend fun writeValue(key: String, value: String) {
    values[key] = value
  }

  override suspend fun clearValue(key: String) {
    values.remove(key)
  }

  private companion object {
    const val SESSION_TOKEN_KEY = "audience_session_token"
  }
}

private class WriteOnlyReadMissingSecureStore : AudienceSecureStore {
  private val values = mutableMapOf<String, String>()

  override suspend fun readSessionToken(): String? = null

  override suspend fun writeSessionToken(token: String) {
    writeValue(SESSION_TOKEN_KEY, token)
  }

  override suspend fun clearSessionToken() {
    clearValue(SESSION_TOKEN_KEY)
  }

  override suspend fun readValue(key: String): String? = values[key]

  override suspend fun writeValue(key: String, value: String) {
    values[key] = value
  }

  override suspend fun clearValue(key: String) {
    values.remove(key)
  }

  private companion object {
    const val SESSION_TOKEN_KEY = "audience_session_token"
  }
}

private class SingleDriverFactory(
  private val driver: app.cash.sqldelight.db.SqlDriver,
) : AudienceSqlDriverFactory {
  override fun create(): app.cash.sqldelight.db.SqlDriver = driver
}

/** Sentinel that starts absent (simulating a fresh install) and remembers mark(). */
private class AlwaysFreshInstallSentinel : AudienceInstallSentinel {
  private var marked = false

  override fun exists(): Boolean = marked

  override fun mark() {
    marked = true
  }
}

private object TestLogger : AudienceLogger {
  override fun debug(message: String) = Unit

  override fun info(message: String) = Unit

  override fun warn(message: String, throwable: Throwable?) = Unit

  override fun error(message: String, throwable: Throwable?) = Unit
}

@OptIn(ExperimentalEncodingApi::class)
private fun tokenForSession(sessionId: Int): String {
  val header = """{"alg":"HS256","typ":"JWT"}""".encodeToByteArray()
  val payload = """{"session_id":$sessionId}""".encodeToByteArray()
  return "${Base64.UrlSafe.encode(header)}.${Base64.UrlSafe.encode(payload)}.sig"
}

private fun bootstrapResponse(
  sessionId: Int,
  identified: Boolean,
  tokenExpiresAt: String = "2026-01-01T00:00:00Z",
  apiBaseUrl: String = "https://example.test",
  launchStudy: LaunchStudyInstruction? = null,
  launchStudyJson: String? = null,
): String =
  """
  {"session_token":"${tokenForSession(sessionId)}","token_expires_at":"$tokenExpiresAt","identified":$identified,"endpoints":{"api_base_url":"$apiBaseUrl","study_base_url":"https://study.example.test"}${launchStudyField(launchStudy, launchStudyJson)}}
  """.trimIndent()

private fun identifyResponse(
  sessionId: Int,
  identified: Boolean,
  merged: Boolean,
  tokenExpiresAt: String = "2026-01-01T00:05:00Z",
  apiBaseUrl: String = "https://example.test",
  launchStudy: LaunchStudyInstruction? = null,
  launchStudyJson: String? = null,
): String =
  """
  {"session_token":"${tokenForSession(sessionId)}","token_expires_at":"$tokenExpiresAt","identified":$identified,"merged":$merged,"endpoints":{"api_base_url":"$apiBaseUrl","study_base_url":"https://study.example.test"}${launchStudyField(launchStudy, launchStudyJson)}}
  """.trimIndent()

private fun heartbeatResponse(
  sessionId: Int,
  tokenExpiresAt: String = "2026-01-01T00:05:00Z",
  apiBaseUrl: String = "https://example.test",
  launchStudy: LaunchStudyInstruction? = null,
  launchStudyJson: String? = null,
): String =
  """
  {"ok":true,"session_token":"${tokenForSession(sessionId)}","token_expires_at":"$tokenExpiresAt","endpoints":{"api_base_url":"$apiBaseUrl","study_base_url":"https://study.example.test"}${launchStudyField(launchStudy, launchStudyJson)}}
  """.trimIndent()

private fun launchStudyField(
  launchStudy: LaunchStudyInstruction?,
  launchStudyJson: String?,
): String =
  when {
    launchStudyJson != null -> ""","launch_study":$launchStudyJson"""
    launchStudy != null -> {
      val webDisplayField = launchStudy.webDisplay?.toString()?.let { ""","web_display":$it""" } ?: ""
      ""","launch_study":{"study_id":"${launchStudy.studyId}"$webDisplayField}"""
    }
    else -> ""","launch_study":null"""
  }
