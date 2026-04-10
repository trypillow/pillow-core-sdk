package com.pillow.mobile.study.api

import com.pillow.mobile.audience.api.AudienceApiErrorResponse
import com.pillow.mobile.audience.runtime.AudienceApiException
import com.pillow.mobile.audience.runtime.AudienceErrorState
import com.pillow.mobile.audience.runtime.AudienceFatalException
import com.pillow.mobile.audience.runtime.AudienceHttpClient
import com.pillow.mobile.audience.runtime.AudienceJson
import com.pillow.mobile.audience.runtime.AudienceRetryableException
import kotlinx.serialization.encodeToString

internal class StudyApiClient(
  private val publishableKey: String,
  private val httpClient: AudienceHttpClient,
  private val apiBaseUrlProvider: suspend () -> String,
) {
  suspend fun prepareCampaign(body: PrepareCampaignRequest): PrepareCampaignResponse {
    val response = try {
      httpClient.postJson(
        baseUrl = apiBaseUrlProvider(),
        path = "/sdk/v1/campaigns/prepare",
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
    val message = parsedError?.message ?: "Study prepare request failed"

    when {
      response.statusCode >= 500 -> throw AudienceRetryableException(code, message)
      response.statusCode in 400..499 -> throw AudienceApiException(
        AudienceErrorState(code = code, message = message, fatal = true),
      )
      else -> throw AudienceFatalException(code, message)
    }
  }
}
