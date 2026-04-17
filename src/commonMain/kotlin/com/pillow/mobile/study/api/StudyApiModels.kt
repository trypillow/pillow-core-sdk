package com.pillow.mobile.study.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PrepareCampaignRequest(
  @SerialName("session_token") val sessionToken: String,
  val alias: String,
  @SerialName("skip_if_already_exposed") val skipIfAlreadyExposed: Boolean,
  @SerialName("distribution_token") val distributionToken: String? = null,
)

@Serializable
internal data class PrepareCampaignResponse(
  @SerialName("should_present") val shouldPresent: Boolean,
  @SerialName("study_url") val studyUrl: String? = null,
  @SerialName("campaign_handoff_token") val campaignHandoffToken: String? = null,
  @SerialName("campaign_exposure_id") val campaignExposureId: Long? = null,
)
