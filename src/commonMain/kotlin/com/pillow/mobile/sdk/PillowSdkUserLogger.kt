package com.pillow.mobile.sdk

import com.pillow.mobile.audience.runtime.AudienceErrorState
import com.pillow.mobile.audience.runtime.AudienceLogger

internal object PillowSdkUserLogger {
  fun emit(
    logger: AudienceLogger,
    sdkError: PillowSdkError,
  ) {
    when (sdkError.category) {
      PillowSdkErrorCategory.API -> logger.error(
        "Request rejected by Pillow${sdkError.code?.let { " [$it]" }.orEmpty()}: ${sdkError.message}",
      )
      PillowSdkErrorCategory.NETWORK -> logger.warn(
        "Temporary network issue while syncing with Pillow: ${sdkError.message}",
      )
      PillowSdkErrorCategory.FATAL -> logger.error(
        "PillowSDK failed${sdkError.code?.let { " [$it]" }.orEmpty()}: ${sdkError.message}",
      )
      PillowSdkErrorCategory.USAGE -> logger.warn("PillowSDK usage issue: ${sdkError.message}")
      PillowSdkErrorCategory.UNKNOWN -> logger.error("PillowSDK operation failed: ${sdkError.message}")
    }
  }

  fun emitApiError(
    logger: AudienceLogger,
    errorState: AudienceErrorState,
  ) {
    emit(logger, errorState.toPillowSdkError())
  }
}
