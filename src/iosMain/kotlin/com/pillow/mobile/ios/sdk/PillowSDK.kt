package com.pillow.mobile.ios.sdk

import com.pillow.mobile.study.runtime.PillowStudy
import com.pillow.mobile.study.runtime.PillowStudyPresentationOptions
import com.pillow.mobile.study.runtime.PillowStudyDelegate
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

public object PillowSDK {
  private val runtime = IosPillowSdkRuntime()

  public fun initialize(
    publishableKey: String,
  ) {
    runtime.initialize(publishableKey = publishableKey)
  }

  public fun setExternalId(externalId: String) {
    runtime.setExternalId(externalId)
  }

  @OptIn(ExperimentalObjCName::class)
  @ObjCName(name = "setStringUserProperty", swiftName = "setUserProperty")
  public fun setUserProperty(key: String, @ObjCName("stringValue") value: String) {
    runtime.setStringUserProperty(key, value)
  }

  @OptIn(ExperimentalObjCName::class)
  @ObjCName(name = "setBooleanUserProperty", swiftName = "setUserProperty")
  public fun setUserProperty(key: String, @ObjCName("booleanValue") value: Boolean) {
    runtime.setBooleanUserProperty(key, value)
  }

  @OptIn(ExperimentalObjCName::class)
  @ObjCName(name = "setIntUserProperty", swiftName = "setUserProperty")
  public fun setUserProperty(key: String, @ObjCName("intValue") value: Int) {
    runtime.setIntUserProperty(key, value)
  }

  @OptIn(ExperimentalObjCName::class)
  @ObjCName(name = "setDoubleUserProperty", swiftName = "setUserProperty")
  public fun setUserProperty(key: String, @ObjCName("doubleValue") value: Double) {
    runtime.setDoubleUserProperty(key, value)
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
    study: PillowStudy,
    options: PillowStudyPresentationOptions = PillowStudyPresentationOptions(),
    delegate: PillowStudyDelegate? = null,
  ) {
    runtime.presentStudy(
      study = study,
      options = options,
      delegate = delegate,
    )
  }

  public fun presentLaunchStudyIfAvailable(
    delegate: PillowStudyDelegate? = null,
  ) {
    runtime.presentLaunchStudyIfAvailable(delegate = delegate)
  }

  public fun onReadyToPresentStudy() {
    runtime.onReadyToPresentStudy()
  }
}
