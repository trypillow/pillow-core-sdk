package com.pillow.mobile.ios.sdk

import com.pillow.mobile.audience.runtime.AudienceLogger
import platform.Foundation.NSLog

internal object IosPillowSdkLogger : AudienceLogger {
  override fun debug(message: String) = Unit

  override fun info(message: String) = Unit

  override fun warn(message: String, throwable: Throwable?) {
    NSLog("PillowSDK WARN: %s", formatMessage(message, throwable))
  }

  override fun error(message: String, throwable: Throwable?) {
    NSLog("PillowSDK ERROR: %s", formatMessage(message, throwable))
  }

  private fun formatMessage(message: String, throwable: Throwable?): String =
    listOfNotNull(
      message.trim().takeIf { it.isNotEmpty() },
      throwable?.message?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(separator = " | ")
}
