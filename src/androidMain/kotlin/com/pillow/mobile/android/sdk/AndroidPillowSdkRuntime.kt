package com.pillow.mobile.android.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import com.pillow.mobile.android.study.PillowStudyPresenter
import com.pillow.mobile.sdk.reportPillowSdkError
import com.pillow.mobile.audience.runtime.AudienceClient
import com.pillow.mobile.audience.runtime.AudienceClientConfig
import com.pillow.mobile.audience.runtime.AudienceLogger
import com.pillow.mobile.audience.runtime.AudiencePlatform
import com.pillow.mobile.audience.runtime.DEFAULT_AUDIENCE_API_BASE_URL
import com.pillow.mobile.audience.runtime.normalizeAudienceBaseUrl
import com.pillow.mobile.audience.runtime.resolveAudienceControlPlaneBaseUrl
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal class AndroidPillowSdkRuntime {
  private val mutex = Mutex()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var audienceClient: AudienceClient? = null
  private var lifecycleObserver: AndroidPillowSdkLifecycleObserver? = null
  private var propertyStore: AndroidPillowSdkPropertyStore? = null
  private var studyRuntime: StudyRuntime? = null
  private var sdkLogger: AudienceLogger = AndroidPillowSdkLogger
  private var initializationJob: Job? = null
  private var activeStudyPresenter: PillowStudyPresenter? = null
  private var activeSdkVersion: String = "0.1.0"
  fun initialize(
    context: Context,
    publishableKey: String,
  ) {
    val application = context.applicationContext as? Application
      ?: error("PillowSDK requires an Application context")
    val trimmedKey = publishableKey.trim()
    require(trimmedKey.isNotEmpty()) { "publishableKey must not be blank" }

    val store = AndroidPillowSdkPropertyStore(
      sharedPreferences = createEncryptedPreferences(
        context = application,
        name = PREFS_NAME,
      ),
    )
    val sdkVersion = sdkVersion(application)
    val components = AndroidAudienceClientFactory.create(
      context = application,
      config = AudienceClientConfig(
        baseUrl = resolveBaseUrl(application),
        publishableKey = trimmedKey,
        platform = AudiencePlatform.ANDROID,
        sdkVersion = sdkVersion,
        logger = AndroidPillowSdkLogger,
      ),
    )
    sdkLogger = components.config.logger
    activeSdkVersion = sdkVersion

    val freshInstall = !components.dependencies.installSentinel.exists()

    initializationJob = scope.launch {
      mutex.withLock {
        lifecycleObserver?.dispose(application)
        audienceClient = components.audienceClient
        propertyStore = store

        val runtime = StudyRuntime(
          config = components.config,
          audienceClient = components.audienceClient,
          httpClient = components.dependencies.httpClient,
          secureStore = components.dependencies.secureStore,
          logger = components.config.logger,
        )
        studyRuntime = runtime

        if (freshInstall) {
          store.clearExternalId()
          store.clearUserProperties()
          runtime.clearAllStoredSessions()
        }

        lifecycleObserver = AndroidPillowSdkLifecycleObserver(
          onForeground = { launchSdkCall { requireClient().onAppForeground() } },
          onBackground = { launchSdkCall { requireClient().onAppBackground() } },
        ).also { observer -> observer.install(application) }
        components.audienceClient.start()
        components.audienceClient.setUserProperties(store.readUserProperties())
        store.readExternalId()?.let { externalId ->
          components.audienceClient.identify(externalId)
        }
        components.audienceClient.onAppForeground()
      }
    }
  }

  fun setExternalId(externalId: String) {
    val trimmedExternalId = externalId.trim()
    require(trimmedExternalId.isNotEmpty()) { "externalId must not be blank" }

    requirePropertyStore().writeExternalId(trimmedExternalId)
    launchSdkCall {
      val client = requireClient()
      client.identify(trimmedExternalId)
    }
  }

  fun setUserProperty(key: String, value: String) {
    setUserPropertyInternal(key, JsonPrimitive(value))
  }

  fun setUserProperty(key: String, value: Boolean) {
    setUserPropertyInternal(key, JsonPrimitive(value))
  }

  fun setUserProperty(key: String, value: Int) {
    setUserPropertyInternal(key, JsonPrimitive(value))
  }

  fun setUserProperty(key: String, value: Double) {
    setUserPropertyInternal(key, JsonPrimitive(value))
  }

  fun clearUserProperty(key: String) {
    val trimmedKey = key.trim()
    require(trimmedKey.isNotEmpty()) { "key must not be blank" }

    requirePropertyStore().clearUserProperty(trimmedKey)
    syncUserProperties()
  }

  fun clearAllProperties() {
    requirePropertyStore().clearUserProperties()
    syncUserProperties()
  }

  fun reset() {
    requirePropertyStore().clearExternalId()
    requirePropertyStore().clearUserProperties()
    launchSdkCall {
      val client = requireClient()
      requireStudyRuntime().clearAllStoredSessions()
      client.setUserProperties(null)
      client.reset()
    }
  }

  fun presentStudy(
    activity: Activity,
    study: PillowStudy,
    options: PillowStudyPresentationOptions,
    delegate: PillowStudyDelegate?,
  ) {
    sdkLogger.info("Presenting study with alias '${study.id}'")
    val pendingInitialization = initializationJob
    scope.launch {
      pendingInitialization?.join()
      try {
        mutex.withLock {
          val runtime = requireStudyRuntime()
          when (val prepared = runtime.preparePresentation(study, options.skipIfAlreadyExposed)) {
            is SkipStudy -> {
              sdkLogger.info("Skipping study '${study.id}' because it was already exposed")
              launchOnMainThread {
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

              launchOnMainThread {
                if (activeStudyPresenter != null) {
                  sdkLogger.warn("A study is already being presented, ignoring request for '${study.id}'")
                  delegate?.studyDidFailToLoad(study, IllegalStateException("A study is already being presented"))
                  return@launchOnMainThread
                }

                val presenter = PillowStudyPresenter(
                  activity = activity,
                  alias = presentation.alias,
                  studyUrl = presentation.studyUrl,
                  campaignHandoffToken = presentation.campaignHandoffToken,
                  restoredSessionToken = restoredSessionToken,
                  forceFreshSession = options.forceFreshSession,
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
                  },
                  logger = sdkLogger,
                )

                if (presenter.present()) {
                  activeStudyPresenter = presenter
                  delegate?.studyDidPresent(study)
                } else {
                  sdkLogger.error("Failed to present study modal")
                  delegate?.studyDidFailToLoad(study, IllegalStateException("Failed to present study modal"))
                }
              }
            }
          }
        }
      } catch (error: Throwable) {
        reportPillowSdkError(error = error, logger = sdkLogger)
        scope.launch(Dispatchers.Main) { delegate?.studyDidFailToLoad(study, error) }
      }
    }
  }

  fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray,
  ) {
    activeStudyPresenter?.handlePermissionResult(permissions, grantResults)
  }

  private fun setUserPropertyInternal(key: String, value: JsonElement) {
    val trimmedKey = key.trim()
    require(trimmedKey.isNotEmpty()) { "key must not be blank" }

    requirePropertyStore().writeUserProperty(trimmedKey, value)
    syncUserProperties()
  }

  private fun syncUserProperties() {
    sdkLogger.debug("syncUserProperties: launching SDK call")
    launchSdkCall {
      val client = requireClient()
      val userProperties = requirePropertyStore().readUserProperties()
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

  private fun launchOnMainThread(block: () -> Unit) {
    scope.launch(Dispatchers.Main) {
      block()
    }
  }

  private fun requireClient(): AudienceClient =
    requireNotNull(audienceClient) { "PillowSDK.initialize must be called first" }

  private fun requirePropertyStore(): AndroidPillowSdkPropertyStore =
    requireNotNull(propertyStore) { "PillowSDK.initialize must be called first" }

  private fun requireStudyRuntime(): StudyRuntime =
    requireNotNull(studyRuntime) { "PillowSDK.initialize must be called first" }

  private fun sdkVersion(context: Context): String =
    try {
      context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
    } catch (_: Exception) {
      "0.1.0"
    }

  private fun resolveBaseUrl(context: Context): String {
    val appInfo = try {
      context.packageManager.getApplicationInfo(context.packageName, 128)
    } catch (_: Exception) {
      return DEFAULT_AUDIENCE_API_BASE_URL
    }
    val metadata = appInfo.metaData
    val configuredBaseUrl = metadata?.getString("com.pillow.mobile.audience.base_url")
    if (configuredBaseUrl != null && normalizeAudienceBaseUrl(configuredBaseUrl) == null) {
      AndroidPillowSdkLogger.warn(
        "Ignoring invalid Android audience base URL override: $configuredBaseUrl",
      )
    }
    return resolveAudienceControlPlaneBaseUrl(configuredBaseUrl)
  }

  private fun sdkUserAgent(): String = "PillowSDK/$activeSdkVersion (Android)"

  private companion object {
    const val PREFS_NAME: String = "com.pillow.mobile.audience"
  }
}
