package com.pillow.mobile.audience.runtime

internal class AudienceApiException(
  val errorState: AudienceErrorState,
) : RuntimeException(errorState.message)

internal class AudienceRetryableException(
  val code: String,
  override val message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class AudienceFatalException(
  val code: String,
  override val message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class AudienceLoggedException : RuntimeException()
