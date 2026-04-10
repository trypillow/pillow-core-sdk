package com.pillow.mobile.audience.runtime

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

internal class KtorAudienceHttpClient(
  private val client: HttpClient,
) : AudienceHttpClient {
  override suspend fun postJson(
    baseUrl: String,
    path: String,
    body: String,
    headers: Map<String, String>,
  ): AudienceHttpResponse {
    val response = client.post(joinUrl(baseUrl = baseUrl, path = path)) {
      contentType(ContentType.Application.Json)
      headers.forEach { (key, value) -> header(key, value) }
      setBody(body)
    }
    return AudienceHttpResponse(
      statusCode = response.status.value,
      body = response.bodyAsText(),
    )
  }

  private fun joinUrl(
    baseUrl: String,
    path: String,
  ): String {
    val trimmedBase = baseUrl.trimEnd('/')
    val trimmedPath = path.trimStart('/')
    return "$trimmedBase/$trimmedPath"
  }
}
