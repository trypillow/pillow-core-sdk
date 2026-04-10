package com.pillow.mobile.study

import com.pillow.mobile.audience.InMemoryAudienceSecureStore
import com.pillow.mobile.audience.runtime.AudienceClient
import com.pillow.mobile.audience.runtime.AudienceClientConfig
import com.pillow.mobile.audience.runtime.AudienceHttpClient
import com.pillow.mobile.audience.runtime.AudienceHttpResponse
import com.pillow.mobile.audience.runtime.AudienceLogger
import com.pillow.mobile.audience.runtime.AudiencePlatform
import com.pillow.mobile.audience.runtime.AudienceRuntimeEndpoints
import com.pillow.mobile.audience.runtime.AudienceState
import com.pillow.mobile.audience.runtime.AudienceStatus
import com.pillow.mobile.audience.runtime.writeAudienceRuntimeEndpoints
import com.pillow.mobile.study.runtime.PillowStudy
import com.pillow.mobile.study.runtime.PresentStudy
import com.pillow.mobile.study.runtime.SkipStudy
import com.pillow.mobile.study.runtime.StudyRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

class StudyRuntimeTest {
  @Test
  fun preparePresentationUsesAudienceSessionToken() = runTest {
    val audienceClient = FakeAudienceClient(sessionToken = "aud-session")
    val httpClient = FakeStudyHttpClient()
    val runtime =
      StudyRuntime(
        config =
          AudienceClientConfig(
            baseUrl = "https://api.example.test",
            publishableKey = "pk_live_demo",
            platform = AudiencePlatform.IOS,
            sdkVersion = "1.0.0",
            logger = TestLogger,
          ),
        audienceClient = audienceClient,
        httpClient = httpClient,
        secureStore = InMemoryAudienceSecureStore(),
        logger = TestLogger,
      )

    val prepared =
      runtime.preparePresentation(
        study = PillowStudy(id = "demo"),
        skipIfAlreadyExposed = false,
      )

    require(prepared is PresentStudy)
    assertEquals("demo", prepared.presentation.alias)
    assertEquals("/sdk/v1/campaigns/prepare", httpClient.paths.single())
    assertEquals("aud-session", httpClient.lastBody["session_token"])
    assertEquals("demo", httpClient.lastBody["alias"])
    assertEquals("false", httpClient.lastBody["skip_if_already_exposed"])
  }

  @Test
  fun storedSessionRefsCanBeWrittenAndCleared() = runTest {
    val runtime =
      StudyRuntime(
        config =
          AudienceClientConfig(
            baseUrl = "https://api.example.test",
            publishableKey = "pk_live_demo",
            platform = AudiencePlatform.IOS,
            sdkVersion = "1.0.0",
            logger = TestLogger,
          ),
        audienceClient = FakeAudienceClient(sessionToken = "aud-session"),
        httpClient = FakeStudyHttpClient(),
        secureStore = InMemoryAudienceSecureStore(),
        logger = TestLogger,
      )

    runtime.writeStoredSessionToken("demo", "web-session")
    assertEquals("web-session", runtime.readStoredSessionToken("demo"))

    runtime.clearStoredSessionToken("demo")
    assertNull(runtime.readStoredSessionToken("demo"))
  }

  @Test
  fun resolveStoredSessionTokenClearsExistingSessionWhenFreshSessionIsForced() = runTest {
    val runtime =
      StudyRuntime(
        config =
          AudienceClientConfig(
            baseUrl = "https://api.example.test",
            publishableKey = "pk_live_demo",
            platform = AudiencePlatform.IOS,
            sdkVersion = "1.0.0",
            logger = TestLogger,
          ),
        audienceClient = FakeAudienceClient(sessionToken = "aud-session"),
        httpClient = FakeStudyHttpClient(),
        secureStore = InMemoryAudienceSecureStore(),
        logger = TestLogger,
      )

    runtime.writeStoredSessionToken("demo", "web-session")

    assertNull(
      runtime.resolveStoredSessionToken(
        alias = "demo",
        forceFreshSession = true,
      ),
    )
    assertNull(runtime.readStoredSessionToken("demo"))
  }

  @Test
  fun preparePresentationReturnsSkipWhenBackendMarksStudyAsAlreadyExposed() = runTest {
    val runtime =
      StudyRuntime(
        config =
          AudienceClientConfig(
            baseUrl = "https://api.example.test",
            publishableKey = "pk_live_demo",
            platform = AudiencePlatform.IOS,
            sdkVersion = "1.0.0",
            logger = TestLogger,
          ),
        audienceClient = FakeAudienceClient(sessionToken = "aud-session"),
        httpClient = FakeStudyHttpClient(shouldPresent = false),
        secureStore = InMemoryAudienceSecureStore(),
        logger = TestLogger,
      )

    val prepared =
      runtime.preparePresentation(
        study = PillowStudy(id = "demo"),
        skipIfAlreadyExposed = true,
      )

    assertEquals(SkipStudy(alias = "demo"), prepared)
  }

  @Test
  fun preparePresentationUsesStoredRuntimeEndpointWhenAvailable() = runTest {
    val secureStore = InMemoryAudienceSecureStore()
    writeAudienceRuntimeEndpoints(
      secureStore = secureStore,
      seedBaseUrl = "https://api.example.test",
      endpoints = AudienceRuntimeEndpoints(
        apiBaseUrl = "https://runtime.example.test",
        studyBaseUrl = "https://study.example.test",
      ),
    )
    val httpClient = FakeStudyHttpClient()
    val runtime =
      StudyRuntime(
        config =
          AudienceClientConfig(
            baseUrl = "https://api.example.test",
            publishableKey = "pk_live_demo",
            platform = AudiencePlatform.IOS,
            sdkVersion = "1.0.0",
            logger = TestLogger,
          ),
        audienceClient = FakeAudienceClient(sessionToken = "aud-session"),
        httpClient = httpClient,
        secureStore = secureStore,
        logger = TestLogger,
      )

    runtime.preparePresentation(
      study = PillowStudy(id = "demo"),
      skipIfAlreadyExposed = false,
    )

    assertEquals("https://runtime.example.test", httpClient.baseUrls.single())
  }
}

private class FakeAudienceClient(
  private val sessionToken: String?,
) : AudienceClient {
  private val state =
    MutableStateFlow(
      AudienceState(
        status = AudienceStatus.ACTIVE_ANONYMOUS,
        installationId = "installation",
        anonymousId = "anonymous",
        sessionId = "session",
        identified = false,
        lastError = null,
      ),
    )

  override suspend fun start() = Unit

  override suspend fun onAppForeground(): AudienceState = state.value

  override suspend fun onAppBackground() = Unit

  override suspend fun identify(
    externalId: String,
    userProperties: Map<String, kotlinx.serialization.json.JsonElement>?,
  ): AudienceState = state.value

  override suspend fun setUserProperties(userProperties: Map<String, kotlinx.serialization.json.JsonElement>?) =
    Unit

  override suspend fun reset(): AudienceState = state.value

  override suspend fun endSession() = Unit

  override suspend fun currentSessionToken(): String? = sessionToken

  override fun state(): StateFlow<AudienceState> = state
}

private class FakeStudyHttpClient(
  private val shouldPresent: Boolean = true,
) : AudienceHttpClient {
  val baseUrls = mutableListOf<String>()
  val paths = mutableListOf<String>()
  var lastBody: Map<String, String> = emptyMap()

  override suspend fun postJson(
    baseUrl: String,
    path: String,
    body: String,
    headers: Map<String, String>,
  ): AudienceHttpResponse {
    baseUrls += baseUrl
    paths += path
    lastBody =
      mapOf(
        "session_token" to (Regex("\"session_token\":\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
          ?: error("Missing session_token in request body: $body")),
        "alias" to (Regex("\"alias\":\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
          ?: error("Missing alias in request body: $body")),
        "skip_if_already_exposed" to (Regex("\"skip_if_already_exposed\":(true|false)").find(body)?.groupValues?.getOrNull(1)
          ?: error("Missing skip_if_already_exposed in request body: $body")),
      )
    return AudienceHttpResponse(
      statusCode = 200,
      body =
        """
        {"should_present":$shouldPresent,"study_url":"https://study.example.test/demo","campaign_handoff_token":"handoff","campaign_exposure_id":42}
        """.trimIndent(),
    )
  }
}

private object TestLogger : AudienceLogger {
  override fun debug(message: String) = Unit

  override fun info(message: String) = Unit

  override fun warn(message: String, throwable: Throwable?) = Unit

  override fun error(message: String, throwable: Throwable?) = Unit
}
