package com.pillow.mobile.study.runtime

import com.pillow.mobile.audience.runtime.AudienceJson
import com.pillow.mobile.audience.runtime.AudienceSecureStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

internal const val LAUNCH_STUDY_INSTRUCTION_KEY: String = "launch_study_instruction"

@Serializable
internal data class LaunchStudyInstruction(
  @SerialName("study_id") val studyId: String,
  @SerialName("web_display") val webDisplay: JsonObject? = null,
  @SerialName("distribution_token") val distributionToken: String? = null,
)

internal suspend fun readLaunchStudyInstruction(
  secureStore: AudienceSecureStore,
): LaunchStudyInstruction? {
  val stored = secureStore.readValue(LAUNCH_STUDY_INSTRUCTION_KEY) ?: return null
  val decoded = runCatching {
    AudienceJson.instance.decodeFromString(LaunchStudyInstruction.serializer(), stored)
  }.getOrNull()
    ?: run {
      secureStore.clearValue(LAUNCH_STUDY_INSTRUCTION_KEY)
      return null
    }

  val sanitized = decoded.sanitized() ?: run {
    secureStore.clearValue(LAUNCH_STUDY_INSTRUCTION_KEY)
    return null
  }
  if (sanitized != decoded) {
    writeLaunchStudyInstruction(secureStore, sanitized)
  }
  return sanitized
}

internal suspend fun writeLaunchStudyInstruction(
  secureStore: AudienceSecureStore,
  instruction: LaunchStudyInstruction?,
) {
  val sanitized = instruction?.sanitized()
  if (sanitized == null) {
    secureStore.clearValue(LAUNCH_STUDY_INSTRUCTION_KEY)
    return
  }

  secureStore.writeValue(
    LAUNCH_STUDY_INSTRUCTION_KEY,
    AudienceJson.instance.encodeToString(LaunchStudyInstruction.serializer(), sanitized),
  )
}

private fun LaunchStudyInstruction.sanitized(): LaunchStudyInstruction? {
  val trimmedStudyId = studyId.trim()
  if (trimmedStudyId.isEmpty()) {
    return null
  }
  return copy(studyId = trimmedStudyId)
}
