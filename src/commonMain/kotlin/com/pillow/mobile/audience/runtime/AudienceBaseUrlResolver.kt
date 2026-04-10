package com.pillow.mobile.audience.runtime

private val HTTP_BASE_URL_PATTERN = Regex(
  pattern = """^https?://[^/?#]+(?:/[^?#]*)?$""",
  option = RegexOption.IGNORE_CASE,
)

internal const val DEFAULT_AUDIENCE_API_BASE_URL: String = "https://api.pillow.so"

internal fun resolveAudienceControlPlaneBaseUrl(rawValue: String?): String =
  normalizeAudienceBaseUrl(rawValue) ?: DEFAULT_AUDIENCE_API_BASE_URL

internal fun normalizeAudienceBaseUrl(rawValue: String?): String? {
  val trimmed = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  if (!HTTP_BASE_URL_PATTERN.matches(trimmed)) {
    return null
  }
  return trimmed.trimEnd('/')
}
