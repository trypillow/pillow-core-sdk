package com.pillow.mobile.android.sdk

import android.util.Log
import com.pillow.mobile.audience.runtime.AudienceLogger

internal object AndroidPillowSdkLogger : AudienceLogger {
  override fun debug(message: String) = Unit

  override fun info(message: String) = Unit

  override fun warn(message: String, throwable: Throwable?) {
    Log.w(LOG_TAG, message, throwable)
  }

  override fun error(message: String, throwable: Throwable?) {
    Log.e(LOG_TAG, message, throwable)
  }

  private const val LOG_TAG: String = "PillowSDK"
}
