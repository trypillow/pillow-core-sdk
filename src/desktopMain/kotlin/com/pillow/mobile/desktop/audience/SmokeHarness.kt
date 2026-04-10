package com.pillow.mobile.desktop.audience

import com.pillow.mobile.audience.runtime.AudienceClientConfig
import com.pillow.mobile.audience.runtime.AudiencePlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.time.Duration.Companion.seconds

public fun main() {
  runBlocking {
    val baseUrl = System.getenv("AUDIENCE_BASE_URL") ?: "http://127.0.0.1:4000"
    val publishableKey = requireNotNull(System.getenv("AUDIENCE_PUBLISHABLE_KEY")) {
      "AUDIENCE_PUBLISHABLE_KEY is required"
    }
    val databaseFile = System.getenv("AUDIENCE_SMOKE_DB")
      ?: File(System.getProperty("java.io.tmpdir"), "pillow-mobile-core-smoke.db").absolutePath

    val client = DesktopAudienceClientFactory.create(
      config = AudienceClientConfig(
        baseUrl = baseUrl,
        publishableKey = publishableKey,
        platform = AudiencePlatform.IOS,
        sdkVersion = "0.1.0-local",
        heartbeatInterval = 1.seconds,
      ),
      databasePath = databaseFile,
    )

    client.start()
    println("foreground-1: ${client.onAppForeground()}")
    delay(1_100)
    println("foreground-2: ${client.onAppForeground()}")
    println(
      "identify: ${
        client.identify(
          externalId = "smoke-user",
          userProperties = mapOf("plan" to JsonPrimitive("pro")),
        )
      }",
    )
    println("reset: ${client.reset()}")
    client.endSession()
    println("final-state: ${client.state().value}")
  }
}
