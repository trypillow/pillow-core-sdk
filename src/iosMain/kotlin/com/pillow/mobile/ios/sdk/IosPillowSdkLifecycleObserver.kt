package com.pillow.mobile.ios.sdk

import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

internal class IosPillowSdkLifecycleObserver(
  private val onForeground: () -> Unit,
  private val onBackground: () -> Unit,
) {
  private val notificationCenter = NSNotificationCenter.defaultCenter
  private val observers = mutableListOf<Any>()

  init {
    observers += notificationCenter.addObserverForName(
      name = UIApplicationDidBecomeActiveNotification,
      `object` = null,
      queue = null,
    ) { _: NSNotification? ->
      onForeground()
    }
    observers += notificationCenter.addObserverForName(
      name = UIApplicationDidEnterBackgroundNotification,
      `object` = null,
      queue = null,
    ) { _: NSNotification? ->
      onBackground()
    }
  }

  fun dispose() {
    observers.forEach(notificationCenter::removeObserver)
    observers.clear()
  }
}
