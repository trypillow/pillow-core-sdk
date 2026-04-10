package com.pillow.mobile.desktop.audience

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.pillow.mobile.audience.client.AudienceClientImpl
import com.pillow.mobile.audience.persistence.AudienceDatabaseSchema
import com.pillow.mobile.audience.runtime.AudienceClient
import com.pillow.mobile.audience.runtime.AudienceClientConfig
import com.pillow.mobile.audience.runtime.AudienceClock
import com.pillow.mobile.audience.runtime.AudienceDependencies
import com.pillow.mobile.audience.runtime.AudienceInstallSentinel
import com.pillow.mobile.audience.runtime.AudienceMetadata
import com.pillow.mobile.audience.runtime.AudienceMetadataProvider
import com.pillow.mobile.audience.runtime.AudienceSecureStore
import com.pillow.mobile.audience.runtime.AudienceSqlDriverFactory
import com.pillow.mobile.audience.runtime.AudienceUuidGenerator
import com.pillow.mobile.audience.runtime.KtorAudienceHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

public object DesktopAudienceClientFactory {
  public fun create(
    config: AudienceClientConfig,
    databasePath: String,
    secureStore: AudienceSecureStore = InMemoryAudienceSecureStore(),
    metadataProvider: AudienceMetadataProvider = DesktopAudienceMetadataProvider(),
  ): AudienceClient =
    AudienceClientImpl(
      config = config,
      dependencies = AudienceDependencies(
        httpClient = KtorAudienceHttpClient(
          client = HttpClient(CIO) {
            expectSuccess = false
          },
        ),
        secureStore = secureStore,
        metadataProvider = metadataProvider,
        sqlDriverFactory = DesktopAudienceSqlDriverFactory(databasePath),
        installSentinel = DesktopAudienceInstallSentinel(databasePath),
        clock = object : AudienceClock {
          override fun nowEpochMillis(): Long = System.currentTimeMillis()
        },
        uuidGenerator = object : AudienceUuidGenerator {
          override fun generate(): String = UUID.randomUUID().toString()
        },
        logger = config.logger,
      ),
    )
}

public class InMemoryAudienceSecureStore : AudienceSecureStore {
  private val values = mutableMapOf<String, String>()

  override suspend fun readSessionToken(): String? = readValue(SESSION_TOKEN_KEY)

  override suspend fun writeSessionToken(token: String) {
    writeValue(SESSION_TOKEN_KEY, token)
  }

  override suspend fun clearSessionToken() {
    clearValue(SESSION_TOKEN_KEY)
  }

  override suspend fun readValue(key: String): String? = values[key]

  override suspend fun writeValue(key: String, value: String) {
    values[key] = value
  }

  override suspend fun clearValue(key: String) {
    values.remove(key)
  }

  private companion object {
    const val SESSION_TOKEN_KEY = "audience_session_token"
  }
}

public class DesktopAudienceMetadataProvider : AudienceMetadataProvider {
  override fun current(): AudienceMetadata =
    AudienceMetadata(
      appName = "Pillow Desktop Smoke Harness",
      appVersion = "1.0.0",
      appBuild = "1",
      osName = "Desktop",
      osVersion = System.getProperty("os.version"),
      deviceManufacturer = System.getProperty("os.name"),
      deviceModel = System.getProperty("os.arch"),
      locale = Locale.getDefault().toLanguageTag(),
      timezone = TimeZone.getDefault().id,
    )
}

private class DesktopAudienceInstallSentinel(
  databasePath: String,
) : AudienceInstallSentinel {
  private val sentinelFile = File(File(databasePath).parentFile, ".pillow_install_sentinel")

  override fun exists(): Boolean = sentinelFile.exists()

  override fun mark() {
    sentinelFile.parentFile?.mkdirs()
    sentinelFile.createNewFile()
  }
}

private class DesktopAudienceSqlDriverFactory(
  private val databasePath: String,
) : AudienceSqlDriverFactory {
  override fun create(): app.cash.sqldelight.db.SqlDriver {
    File(databasePath).parentFile?.mkdirs()
    val driver = JdbcSqliteDriver(url = "jdbc:sqlite:$databasePath")
    runCatching { AudienceDatabaseSchema.create(driver) }
    return driver
  }
}
