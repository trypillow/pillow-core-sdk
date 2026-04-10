package com.pillow.mobile.audience.runtime

import com.pillow.mobile.audience.InMemoryAudienceSecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class AudienceBaseUrlResolverTest {
  @Test
  fun resolveAudienceControlPlaneBaseUrlNormalizesValidOverrides() {
    assertEquals(
      "https://api.example.test/platform",
      resolveAudienceControlPlaneBaseUrl("  https://api.example.test/platform/  "),
    )
  }

  @Test
  fun resolveAudienceControlPlaneBaseUrlFallsBackForInvalidOverrides() {
    assertEquals(
      DEFAULT_AUDIENCE_API_BASE_URL,
      resolveAudienceControlPlaneBaseUrl("https://api.example.test?foo=bar"),
    )
    assertEquals(
      DEFAULT_AUDIENCE_API_BASE_URL,
      resolveAudienceControlPlaneBaseUrl("ftp://api.example.test"),
    )
  }

  @Test
  fun readAudienceRuntimeEndpointsRepairsStoredValues() = runTest {
    val secureStore = InMemoryAudienceSecureStore()
    secureStore.writeValue(
      AUDIENCE_RUNTIME_ENDPOINTS_KEY,
      """
      {"api_base_url":"https://runtime.example.test///","study_base_url":"https://study.example.test///"}
      """.trimIndent(),
    )

    val endpoints = readAudienceRuntimeEndpoints(
      secureStore = secureStore,
      seedBaseUrl = "https://seed.example.test/",
    )

    assertEquals("https://runtime.example.test", endpoints.apiBaseUrl)
    assertEquals("https://study.example.test", endpoints.studyBaseUrl)
    assertEquals(
      """{"api_base_url":"https://runtime.example.test","study_base_url":"https://study.example.test"}""",
      secureStore.readValue(AUDIENCE_RUNTIME_ENDPOINTS_KEY),
    )
  }

  @Test
  fun readAudienceRuntimeEndpointsFallsBackWhenStoredPayloadIsCorrupt() = runTest {
    val secureStore = InMemoryAudienceSecureStore()
    secureStore.writeValue(AUDIENCE_RUNTIME_ENDPOINTS_KEY, """{"api_base_url":}""")

    val endpoints = readAudienceRuntimeEndpoints(
      secureStore = secureStore,
      seedBaseUrl = "https://seed.example.test/",
    )

    assertEquals("https://seed.example.test", endpoints.apiBaseUrl)
    assertNull(endpoints.studyBaseUrl)
    assertNull(secureStore.readValue(AUDIENCE_RUNTIME_ENDPOINTS_KEY))
  }
}
