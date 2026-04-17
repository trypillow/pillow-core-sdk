package com.pillow.mobile.study.runtime

import com.pillow.mobile.audience.runtime.AudienceClient
import com.pillow.mobile.audience.runtime.AudienceClientConfig
import com.pillow.mobile.audience.runtime.AudienceFatalException
import com.pillow.mobile.audience.runtime.AudienceHttpClient
import com.pillow.mobile.audience.runtime.AudienceJson
import com.pillow.mobile.audience.runtime.AudienceLoggedException
import com.pillow.mobile.audience.runtime.AudienceLogger
import com.pillow.mobile.audience.runtime.AudienceSecureStore
import com.pillow.mobile.audience.runtime.readAudienceRuntimeEndpoints
import com.pillow.mobile.study.api.PrepareCampaignRequest
import com.pillow.mobile.study.api.StudyApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

public data class PillowStudy(
  val id: String,
)

public data class PillowStudyPresentationOptions(
  val forceFreshSession: Boolean = false,
  val skipIfAlreadyExposed: Boolean = false,
)

@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("PillowStudyDelegateProtocol")
public interface PillowStudyDelegate {
  /** Called when the study modal appears on screen. */
  public fun studyDidPresent(study: PillowStudy) {}

  /** Called when the study was intentionally not presented. */
  public fun studyDidSkip(study: PillowStudy) {}

  /** Called when the user finishes or dismisses the study. */
  public fun studyDidFinish(study: PillowStudy) {}

  /** Called when the study could not be loaded or presented. */
  public fun studyDidFailToLoad(study: PillowStudy, error: Throwable) {}
}

internal data class PreparedCampaignPresentation(
  val alias: String,
  val studyUrl: String,
  val campaignHandoffToken: String,
  val webDisplay: JsonObject? = null,
)

internal sealed interface PrepareStudyPresentationResult

internal data class PresentStudy(
  val presentation: PreparedCampaignPresentation,
) : PrepareStudyPresentationResult

internal data class SkipStudy(
  val alias: String,
) : PrepareStudyPresentationResult

internal class StudyRuntime(
  private val config: AudienceClientConfig,
  private val audienceClient: AudienceClient,
  private val httpClient: AudienceHttpClient,
  private val secureStore: AudienceSecureStore,
  private val logger: AudienceLogger,
) {
  private val apiClient = StudyApiClient(
    publishableKey = config.publishableKey,
    httpClient = httpClient,
    apiBaseUrlProvider = {
      readAudienceRuntimeEndpoints(secureStore, config.baseUrl).apiBaseUrl
    },
  )

  suspend fun preparePresentation(
    study: PillowStudy,
    skipIfAlreadyExposed: Boolean,
    launchStudyInstruction: LaunchStudyInstruction? = null,
  ): PrepareStudyPresentationResult {
    val trimmedAlias = study.id.trim()
    require(trimmedAlias.isNotEmpty()) { "alias must not be blank" }

    val state = audienceClient.onAppForeground()
    val sessionToken = audienceClient.currentSessionToken()
      ?: state.lastError?.let { throw AudienceLoggedException() }
      ?: throw AudienceFatalException(
        code = "session_unavailable",
        message = "PillowSDK could not start a session",
      )

    val response = apiClient.prepareCampaign(
      PrepareCampaignRequest(
        sessionToken = sessionToken,
        alias = trimmedAlias,
        skipIfAlreadyExposed = skipIfAlreadyExposed,
        distributionToken = launchStudyInstruction?.distributionToken,
      ),
    )

    if (!response.shouldPresent) {
      return SkipStudy(alias = trimmedAlias)
    }

    val studyUrl = response.studyUrl
    val campaignHandoffToken = response.campaignHandoffToken
    if (studyUrl == null || campaignHandoffToken == null) {
      throw AudienceFatalException(
        code = "campaign_prepare_invalid_response",
        message = "Campaign prepare response is missing presentation fields",
      )
    }

    return PresentStudy(
      presentation =
        PreparedCampaignPresentation(
          alias = trimmedAlias,
          studyUrl = studyUrl,
          campaignHandoffToken = campaignHandoffToken,
          webDisplay = launchStudyInstruction?.webDisplay,
        ),
    )
  }

  suspend fun readLaunchStudyInstruction(): LaunchStudyInstruction? =
    readLaunchStudyInstruction(secureStore)

  suspend fun clearLaunchStudyInstruction() {
    writeLaunchStudyInstruction(secureStore, null)
  }

  suspend fun readStoredSessionToken(alias: String): String? = secureStore.readValue(sessionKey(alias))

  suspend fun resolveStoredSessionToken(
    alias: String,
    forceFreshSession: Boolean,
  ): String? {
    val trimmedAlias = alias.trim()
    if (trimmedAlias.isEmpty()) {
      return null
    }

    if (forceFreshSession) {
      clearStoredSessionToken(trimmedAlias)
      logger.info("Starting fresh study session for alias '$trimmedAlias'")
      return null
    }

    return readStoredSessionToken(trimmedAlias)
  }

  suspend fun writeStoredSessionToken(alias: String, sessionToken: String) {
    val trimmedAlias = alias.trim()
    if (trimmedAlias.isEmpty()) {
      return
    }
    secureStore.writeValue(sessionKey(trimmedAlias), sessionToken)
    writeAliasRegistry(readAliasRegistry() + trimmedAlias)
  }

  suspend fun clearStoredSessionToken(alias: String) {
    val trimmedAlias = alias.trim()
    if (trimmedAlias.isEmpty()) {
      return
    }
    secureStore.clearValue(sessionKey(trimmedAlias))
    writeAliasRegistry(readAliasRegistry() - trimmedAlias)
  }

  suspend fun clearAllStoredSessions() {
    readAliasRegistry().forEach { alias ->
      secureStore.clearValue(sessionKey(alias))
    }
    secureStore.clearValue(ALIAS_REGISTRY_KEY)
    logger.info("Cleared all stored study session refs")
  }

  private suspend fun readAliasRegistry(): Set<String> {
    val raw = secureStore.readValue(ALIAS_REGISTRY_KEY) ?: return emptySet()
    return runCatching {
      AudienceJson.instance.decodeFromString<List<String>>(raw)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
    }.getOrDefault(emptySet())
  }

  private suspend fun writeAliasRegistry(aliases: Set<String>) {
    if (aliases.isEmpty()) {
      secureStore.clearValue(ALIAS_REGISTRY_KEY)
      return
    }
    secureStore.writeValue(
      ALIAS_REGISTRY_KEY,
      AudienceJson.instance.encodeToString(aliases.toList().sorted()),
    )
  }

  private fun sessionKey(alias: String): String = "study_session_ref_${sanitizeAlias(alias)}"

  private fun sanitizeAlias(alias: String): String =
    alias.map { char ->
      if (char.isLetterOrDigit() || char == '_' || char == '-') {
        char
      } else {
        '_'
      }
    }.joinToString(separator = "")

  private companion object {
    const val ALIAS_REGISTRY_KEY = "study_session_ref_aliases"
  }
}
