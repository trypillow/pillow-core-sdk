package com.pillow.mobile.sdk

import com.pillow.mobile.audience.runtime.AudienceApiException
import com.pillow.mobile.audience.runtime.AudienceErrorState
import com.pillow.mobile.audience.runtime.AudienceFatalException
import com.pillow.mobile.audience.runtime.AudienceLoggedException
import com.pillow.mobile.audience.runtime.AudienceLogger
import com.pillow.mobile.audience.runtime.AudienceRetryableException

public enum class PillowSdkErrorCategory {
  NETWORK,
  API,
  FATAL,
  USAGE,
  UNKNOWN,
}

public data class PillowSdkError(
  val category: PillowSdkErrorCategory,
  val code: String?,
  val message: String,
  val fatal: Boolean,
)

internal fun interface PillowSdkErrorListener {
  fun onError(error: PillowSdkError)
}

internal fun Throwable.toPillowSdkError(): PillowSdkError =
  when (this) {
    is AudienceApiException -> errorState.toPillowSdkError()
    is AudienceFatalException -> PillowSdkError(
      category = PillowSdkErrorCategory.FATAL,
      code = code,
      message = message,
      fatal = true,
    )
    is AudienceRetryableException -> PillowSdkError(
      category = PillowSdkErrorCategory.NETWORK,
      code = code,
      message = message,
      fatal = false,
    )
    is IllegalStateException -> PillowSdkError(
      category = PillowSdkErrorCategory.USAGE,
      code = "illegal_state",
      message = message ?: "PillowSDK was used before it was ready",
      fatal = false,
    )
    is IllegalArgumentException -> PillowSdkError(
      category = PillowSdkErrorCategory.USAGE,
      code = "invalid_argument",
      message = message ?: "PillowSDK received an invalid argument",
      fatal = false,
    )
    else -> PillowSdkError(
      category = PillowSdkErrorCategory.UNKNOWN,
      code = null,
      message = message ?: "PillowSDK operation failed",
      fatal = false,
    )
  }

internal fun AudienceErrorState.toPillowSdkError(): PillowSdkError =
  PillowSdkError(
    category = PillowSdkErrorCategory.API,
    code = code,
    message = message,
    fatal = fatal,
  )

internal fun reportPillowSdkError(
  error: Throwable,
  logger: AudienceLogger,
  listener: PillowSdkErrorListener? = null,
) {
  if (error is AudienceLoggedException) {
    return
  }
  val sdkError = error.toPillowSdkError()
  PillowSdkUserLogger.emit(logger, sdkError)
  listener?.onError(sdkError)
}
