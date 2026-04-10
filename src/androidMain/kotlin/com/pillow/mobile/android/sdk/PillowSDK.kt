package com.pillow.mobile.android.sdk

import android.app.Activity
import android.content.Context
import com.pillow.mobile.study.runtime.PillowStudy
import com.pillow.mobile.study.runtime.PillowStudyPresentationOptions
import com.pillow.mobile.study.runtime.PillowStudyDelegate

public object PillowSDK {
  private val runtime = AndroidPillowSdkRuntime()

  public fun initialize(
    context: Context,
    publishableKey: String,
  ) {
    runtime.initialize(context = context, publishableKey = publishableKey)
  }

  public fun setExternalId(externalId: String) {
    runtime.setExternalId(externalId)
  }

  public fun setUserProperty(key: String, value: String) {
    runtime.setUserProperty(key, value)
  }

  public fun setUserProperty(key: String, value: Boolean) {
    runtime.setUserProperty(key, value)
  }

  public fun setUserProperty(key: String, value: Int) {
    runtime.setUserProperty(key, value)
  }

  public fun setUserProperty(key: String, value: Double) {
    runtime.setUserProperty(key, value)
  }

  public fun clearUserProperty(key: String) {
    runtime.clearUserProperty(key)
  }

  public fun clearAllProperties() {
    runtime.clearAllProperties()
  }

  public fun reset() {
    runtime.reset()
  }

  public fun presentStudy(
    activity: Activity,
    study: PillowStudy,
    options: PillowStudyPresentationOptions = PillowStudyPresentationOptions(),
    delegate: PillowStudyDelegate? = null,
  ) {
    runtime.presentStudy(
      activity = activity,
      study = study,
      options = options,
      delegate = delegate,
    )
  }

  public fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray,
  ) {
    runtime.onRequestPermissionsResult(
      requestCode = requestCode,
      permissions = permissions,
      grantResults = grantResults,
    )
  }
}
