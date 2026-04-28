package com.pillow.mobile.ios.sdk

import com.pillow.mobile.ios.audience.IosAudiencePropertyStore
import com.pillow.mobile.ios.audience.createComponents
import com.pillow.mobile.sdk.PillowSdkBuildConfig
import com.pillow.mobile.sdk.reportPillowSdkError
import com.pillow.mobile.audience.runtime.AudienceClient
import com.pillow.mobile.audience.runtime.AudienceClientConfig
import com.pillow.mobile.audience.runtime.AudienceLogger
import com.pillow.mobile.audience.runtime.AudiencePlatform
import com.pillow.mobile.audience.runtime.normalizeAudienceBaseUrl
import com.pillow.mobile.audience.runtime.resolveAudienceControlPlaneBaseUrl
import com.pillow.mobile.ios.study.PillowStudyPresenter
import com.pillow.mobile.study.runtime.LaunchStudyInstruction
import com.pillow.mobile.study.runtime.PillowStudy
import com.pillow.mobile.study.runtime.PillowStudyPresentationOptions
import com.pillow.mobile.study.runtime.PillowStudyDelegate
import com.pillow.mobile.study.runtime.PresentStudy
import com.pillow.mobile.study.runtime.SkipStudy
import com.pillow.mobile.study.runtime.StudyRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSBundle
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState

internal class IosPillowSdkRuntime {
  private val mutex = Mutex()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val propertyStore = IosAudiencePropertyStore()
  private var audienceClient: AudienceClient? = null
  private var studyRuntime: StudyRuntime? = null
  private var sdkLogger: AudienceLogger = IosPillowSdkLogger
  private var lifecycleObserver: IosPillowSdkLifecycleObserver? = null
  private var initializationJob: Job? = null
  private var activeStudyPresenter: PillowStudyPresenter? = null
  private var isAppInForeground: Boolean = false
  private var isReadyToPresentStudy: Boolean = false
  private var launchStudyPresentationInFlight: Boolean = false
  fun initialize(
    publishableKey: String,
  ) {
    val trimmedKey = publishableKey.trim()
    require(trimmedKey.isNotEmpty()) { "publishableKey must not be blank" }

    val config = AudienceClientConfig(
      baseUrl = resolveBaseUrl(),
      publishableKey = trimmedKey,
      platform = AudiencePlatform.IOS,
      sdkVersion = sdkVersion(),
      logger = IosPillowSdkLogger,
    )
    sdkLogger = config.logger
    val components = createComponents(config)
    val client = components.audienceClient

    lifecycleObserver?.dispose()
    lifecycleObserver = null
    audienceClient = client

    val runtime = StudyRuntime(
      config = config,
      audienceClient = client,
      httpClient = components.dependencies.httpClient,
      secureStore = components.dependencies.secureStore,
      logger = config.logger,
    )
    studyRuntime = runtime

    val appIsActive = isApplicationActive()
    isAppInForeground = appIsActive

    initializationJob = scope.launch {
      mutex.withLock {
        client.start()

        if (client.wasFreshInstallOnLastStart()) {
          propertyStore.clearExternalId()
          propertyStore.clearUserProperties()
          runtime.clearAllStoredSessions()
          runtime.clearLaunchStudyInstruction()
        }

        client.setUserProperties(propertyStore.readUserProperties())
        propertyStore.readExternalId()?.let { externalId ->
          client.identify(externalId)
        }
        if (appIsActive) {
          client.onAppForeground(forceHeartbeat = true)
        }
      }
      lifecycleObserver = createLifecycleObserver()
    }
  }

  fun setExternalId(externalId: String) {
    val trimmedExternalId = externalId.trim()
    require(trimmedExternalId.isNotEmpty()) { "externalId must not be blank" }

    propertyStore.writeExternalId(trimmedExternalId)
    launchSdkCall {
      val client = requireClient()
      client.identify(trimmedExternalId)
      maybePresentPendingLaunchStudyLocked()
    }
  }

  fun setStringUserProperty(key: String, value: String) {
    setUserPropertyInternal(key, JsonPrimitive(value))
  }

  fun setBooleanUserProperty(key: String, value: Boolean) {
    setUserPropertyInternal(key, JsonPrimitive(value))
  }

  fun setIntUserProperty(key: String, value: Int) {
    setUserPropertyInternal(key, JsonPrimitive(value))
  }

  fun setDoubleUserProperty(key: String, value: Double) {
    setUserPropertyInternal(key, JsonPrimitive(value))
  }

  fun clearUserProperty(key: String) {
    val trimmedKey = key.trim()
    require(trimmedKey.isNotEmpty()) { "key must not be blank" }

    propertyStore.clearUserProperty(trimmedKey)
    syncUserProperties()
  }

  fun clearAllProperties() {
    propertyStore.clearUserProperties()
    syncUserProperties()
  }

  fun reset() {
    propertyStore.clearExternalId()
    propertyStore.clearUserProperties()
    launchSdkCall {
      val client = requireClient()
      requireStudyRuntime().clearAllStoredSessions()
      requireStudyRuntime().clearLaunchStudyInstruction()
      client.setUserProperties(null)
      client.reset()
      maybePresentPendingLaunchStudyLocked()
    }
  }

  fun onReadyToPresentStudy() {
    isReadyToPresentStudy = true
    launchSdkCall {
      maybePresentPendingLaunchStudyLocked()
    }
  }

  fun presentStudy(
    study: PillowStudy,
    options: PillowStudyPresentationOptions,
    delegate: PillowStudyDelegate?,
  ) {
    presentStudyInternal(
      study = study,
      options = options,
      delegate = delegate,
      launchStudyInstruction = null,
      clearLaunchStudyOnPresent = false,
    )
  }

  fun presentLaunchStudyIfAvailable(
    delegate: PillowStudyDelegate?,
  ) {
    val pendingInitialization = initializationJob
    scope.launch {
      pendingInitialization?.join()
      val launchStudyInstruction = try {
        mutex.withLock {
          if (launchStudyPresentationInFlight || activeStudyPresenter != null) {
            return@withLock null
          }
          requireClient().onAppForeground()
          val instruction = requireStudyRuntime().readLaunchStudyInstruction()
          if (instruction != null) {
            launchStudyPresentationInFlight = true
          }
          instruction
        }
      } catch (error: Throwable) {
        mutex.withLock {
          launchStudyPresentationInFlight = false
        }
        reportPillowSdkError(error = error, logger = sdkLogger)
        return@launch
      }
      if (launchStudyInstruction == null) {
        return@launch
      }
      presentStudyInternal(
        study = PillowStudy(id = launchStudyInstruction.studyId),
        options = PillowStudyPresentationOptions(),
        delegate = delegate,
        launchStudyInstruction = launchStudyInstruction,
        clearLaunchStudyOnPresent = true,
      )
    }
  }

  private fun presentStudyInternal(
    study: PillowStudy,
    options: PillowStudyPresentationOptions,
    delegate: PillowStudyDelegate?,
    launchStudyInstruction: LaunchStudyInstruction?,
    clearLaunchStudyOnPresent: Boolean,
  ) {
    sdkLogger.info("Presenting study with alias '${study.id}'")
    val pendingInitialization = initializationJob
    scope.launch {
      pendingInitialization?.join()
      try {
        mutex.withLock {
          val runtime = requireStudyRuntime()
          when (
            val prepared = runtime.preparePresentation(
              study = study,
              skipIfAlreadyExposed = options.skipIfAlreadyExposed,
              launchStudyInstruction = launchStudyInstruction,
            )
          ) {
            is SkipStudy -> {
              if (launchStudyInstruction != null) {
                launchStudyPresentationInFlight = false
              }
              if (clearLaunchStudyOnPresent) {
                runtime.clearLaunchStudyInstruction()
              }
              sdkLogger.info("Skipping study '${study.id}' because it was already exposed")
              withContext(Dispatchers.Main) {
                delegate?.studyDidSkip(study)
              }
              return@withLock
            }
            is PresentStudy -> {
              val presentation = prepared.presentation
              val restoredSessionToken =
                runtime.resolveStoredSessionToken(
                  alias = presentation.alias,
                  forceFreshSession = options.forceFreshSession,
                )

              withContext(Dispatchers.Main) {
                if (activeStudyPresenter != null) {
                  if (launchStudyInstruction != null) {
                    launchStudyPresentationInFlight = false
                  }
                  sdkLogger.warn("A study is already being presented, ignoring request for '${study.id}'")
                  delegate?.studyDidFailToLoad(study, IllegalStateException("A study is already being presented"))
                  return@withContext
                }

                val presenter = PillowStudyPresenter(
                  alias = presentation.alias,
                  studyUrl = presentation.studyUrl,
                  campaignHandoffToken = presentation.campaignHandoffToken,
                  restoredSessionToken = restoredSessionToken,
                  forceFreshSession = options.forceFreshSession,
                  webDisplay = presentation.webDisplay,
                  userAgent = sdkUserAgent(),
                  onStudySession = { alias, sessionToken ->
                    launchSdkCall {
                      requireStudyRuntime().writeStoredSessionToken(alias, sessionToken)
                    }
                  },
                  onConversationEnded = { alias ->
                    launchSdkCall {
                      requireStudyRuntime().clearStoredSessionToken(alias)
                    }
                  },
                  onDismiss = {
                    activeStudyPresenter = null
                    delegate?.studyDidFinish(study)
                    launchSdkCall { maybePresentPendingLaunchStudyLocked() }
                  },
                  logger = sdkLogger,
                )

                if (presenter.present()) {
                  activeStudyPresenter = presenter
                  if (launchStudyInstruction != null) {
                    launchStudyPresentationInFlight = false
                  }
                  if (clearLaunchStudyOnPresent) {
                    launchSdkCall {
                      requireStudyRuntime().clearLaunchStudyInstruction()
                    }
                  }
                  delegate?.studyDidPresent(study)
                } else {
                  if (launchStudyInstruction != null) {
                    launchStudyPresentationInFlight = false
                  }
                  sdkLogger.error("Failed to present study modal")
                  delegate?.studyDidFailToLoad(study, IllegalStateException("Failed to present study modal"))
                }
              }
            }
          }
        }
      } catch (error: Throwable) {
        mutex.withLock {
          if (launchStudyInstruction != null) {
            launchStudyPresentationInFlight = false
          }
        }
        reportPillowSdkError(error = error, logger = sdkLogger)
        withContext(Dispatchers.Main) { delegate?.studyDidFailToLoad(study, error) }
      }
    }
  }

  private fun setUserPropertyInternal(key: String, value: JsonElement) {
    val trimmedKey = key.trim()
    require(trimmedKey.isNotEmpty()) { "key must not be blank" }

    propertyStore.writeUserProperty(trimmedKey, value)
    syncUserProperties()
  }

  private fun syncUserProperties() {
    sdkLogger.debug("syncUserProperties: launching SDK call")
    launchSdkCall {
      val client = requireClient()
      val userProperties = propertyStore.readUserProperties()
      sdkLogger.debug(
        "syncUserProperties: userProperties=${userProperties?.keys}, calling setUserProperties",
      )
      client.setUserProperties(userProperties)
    }
  }

  private fun launchSdkCall(block: suspend () -> Unit) {
    val pendingInitialization = initializationJob
    scope.launch {
      pendingInitialization?.join()
      try {
        mutex.withLock {
          block()
        }
      } catch (error: Throwable) {
        reportPillowSdkError(error = error, logger = sdkLogger)
      }
    }
  }

  private fun requireClient(): AudienceClient =
    requireNotNull(audienceClient) { "PillowSDK.initialize must be called first" }

  private fun requireStudyRuntime(): StudyRuntime =
    requireNotNull(studyRuntime) { "PillowSDK.initialize must be called first" }

  private suspend fun maybePresentPendingLaunchStudyLocked() {
    if (
      !isReadyToPresentStudy ||
      !isAppInForeground ||
      activeStudyPresenter != null ||
      launchStudyPresentationInFlight
    ) {
      return
    }

    val launchStudyInstruction = requireStudyRuntime().readLaunchStudyInstruction() ?: return
    launchStudyPresentationInFlight = true
    presentStudyInternal(
      study = PillowStudy(id = launchStudyInstruction.studyId),
      options = PillowStudyPresentationOptions(),
      delegate = null,
      launchStudyInstruction = launchStudyInstruction,
      clearLaunchStudyOnPresent = true,
    )
  }

  private fun sdkVersion(): String = PillowSdkBuildConfig.SDK_VERSION

  private fun sdkUserAgent(): String = "PillowSDK/${sdkVersion()} (iOS)"

  private fun resolveBaseUrl(): String {
    val configuredBaseUrl = (
      NSBundle.mainBundle.objectForInfoDictionaryKey("PillowAudienceBaseURL")
        ?: NSBundle.mainBundle.objectForInfoDictionaryKey("PillowAPIBaseURL")
      ) as? String

    if (configuredBaseUrl != null && normalizeAudienceBaseUrl(configuredBaseUrl) == null) {
      IosPillowSdkLogger.warn("Ignoring invalid iOS audience base URL override: $configuredBaseUrl")
    }

    return resolveAudienceControlPlaneBaseUrl(configuredBaseUrl)
  }

  private fun createLifecycleObserver(): IosPillowSdkLifecycleObserver =
    IosPillowSdkLifecycleObserver(
      onForeground = {
        if (markAppForeground()) {
          launchSdkCall {
            requireClient().onAppForeground()
            maybePresentPendingLaunchStudyLocked()
          }
        }
      },
      onBackground = {
        if (markAppBackground()) {
          isReadyToPresentStudy = false
          launchSdkCall { requireClient().onAppBackground() }
        }
      },
    )

  private fun isApplicationActive(): Boolean =
    UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive

  private fun markAppForeground(): Boolean {
    if (isAppInForeground) {
      return false
    }
    isAppInForeground = true
    return true
  }

  private fun markAppBackground(): Boolean {
    if (!isAppInForeground) {
      return false
    }
    isAppInForeground = false
    return true
  }
}
