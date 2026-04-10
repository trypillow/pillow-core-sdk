package com.pillow.mobile.audience.api

import com.pillow.mobile.audience.runtime.AudienceApiException
import com.pillow.mobile.audience.runtime.AudienceErrorState
import com.pillow.mobile.audience.runtime.AudienceFatalException
import com.pillow.mobile.audience.runtime.AudienceHttpClient
import com.pillow.mobile.audience.runtime.AudienceJson
import com.pillow.mobile.audience.runtime.AudienceRetryableException
import kotlinx.serialization.encodeToString

internal class AudienceApiClient(
  private val publishableKey: String,
  private val httpClient: AudienceHttpClient,
  private val seedBaseUrlProvider: suspend () -> String,
  private val runtimeApiBaseUrlProvider: suspend () -> String,
) {
  suspend fun bootstrap(body: AudienceBootstrapRequest): AudienceBootstrapResponse =
    post(
      path = "/sdk/v1/bootstrap",
      body = body,
      baseUrlProvider = seedBaseUrlProvider,
    )

  suspend fun identify(body: AudienceIdentifyRequest): AudienceIdentifyResponse =
    post(
      path = "/sdk/v1/identify",
      body = body,
      baseUrlProvider = runtimeApiBaseUrlProvider,
    )

  suspend fun setUserProperties(
    body: AudienceSetUserPropertiesRequest,
  ): AudienceSetUserPropertiesResponse =
    post(
      path = "/sdk/v1/user-properties",
      body = body,
      baseUrlProvider = runtimeApiBaseUrlProvider,
    )

  suspend fun heartbeat(body: AudienceHeartbeatRequest): AudienceHeartbeatResponse =
    post(
      path = "/sdk/v1/session/heartbeat",
      body = body,
      baseUrlProvider = runtimeApiBaseUrlProvider,
    )

  suspend fun reset(body: AudienceResetRequest): AudienceBootstrapResponse =
    post(
      path = "/sdk/v1/reset",
      body = body,
      baseUrlProvider = runtimeApiBaseUrlProvider,
    )

  suspend fun endSession(body: AudienceEndSessionRequest): AudienceEndSessionResponse =
    post(
      path = "/sdk/v1/session/end",
      body = body,
      baseUrlProvider = runtimeApiBaseUrlProvider,
    )

  private suspend inline fun <reified Req : Any, reified Res : Any> post(
    path: String,
    body: Req,
    baseUrlProvider: suspend () -> String,
  ): Res {
    val response = try {
      httpClient.postJson(
        baseUrl = baseUrlProvider(),
        path = path,
        body = AudienceJson.instance.encodeToString(body),
        headers = mapOf(
          "Content-Type" to "application/json",
          "X-Pillow-SDK-Key" to publishableKey,
        ),
      )
    } catch (error: Throwable) {
      throw AudienceRetryableException(
        code = "network_error",
        message = error.message ?: "Network request failed",
        cause = error,
      )
    }

    if (response.statusCode in 200..299) {
      return AudienceJson.instance.decodeFromString(response.body)
    }

    val parsedError = runCatching {
      AudienceJson.instance.decodeFromString<AudienceApiErrorResponse>(response.body)
    }.getOrNull()

    val code = parsedError?.code ?: "http_${response.statusCode}"
    val message = parsedError?.message ?: "Audience request failed"

    when {
      response.statusCode >= 500 -> throw AudienceRetryableException(code, message)
      response.statusCode in 400..499 -> throw AudienceApiException(
        AudienceErrorState(code = code, message = message, fatal = true),
      )
      else -> throw AudienceFatalException(code, message)
    }
  }
}
