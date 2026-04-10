package com.pillow.mobile.android.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal class AndroidPillowSdkLifecycleObserver(
  private val onForeground: () -> Unit,
  private val onBackground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
  private var startedActivities: Int = 0

  fun install(application: Application) {
    application.registerActivityLifecycleCallbacks(this)
  }

  fun dispose(application: Application) {
    application.unregisterActivityLifecycleCallbacks(this)
  }

  override fun onActivityStarted(activity: Activity) {
    startedActivities += 1
    if (startedActivities == 1) {
      onForeground()
    }
  }

  override fun onActivityStopped(activity: Activity) {
    startedActivities = (startedActivities - 1).coerceAtLeast(0)
    if (startedActivities == 0) {
      onBackground()
    }
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

  override fun onActivityResumed(activity: Activity) = Unit

  override fun onActivityPaused(activity: Activity) = Unit

  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

  override fun onActivityDestroyed(activity: Activity) = Unit
}
