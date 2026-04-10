package com.pillow.mobile.android.audience

import android.content.Context
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.pillow.mobile.audience.client.AudienceClientImpl
import com.pillow.mobile.audience.persistence.AudienceDatabaseSchema
import com.pillow.mobile.audience.runtime.AudienceClient
import com.pillow.mobile.audience.runtime.AudienceClientConfig
import com.pillow.mobile.audience.runtime.AudienceDependencies
import com.pillow.mobile.audience.runtime.AudienceClock
import com.pillow.mobile.audience.runtime.AudienceInstallSentinel
import com.pillow.mobile.audience.runtime.AudienceMetadata
import com.pillow.mobile.audience.runtime.AudienceMetadataProvider
import com.pillow.mobile.audience.runtime.AudienceSecureStore
import com.pillow.mobile.audience.runtime.AudienceSqlDriverFactory
import com.pillow.mobile.audience.runtime.AudienceUuidGenerator
import com.pillow.mobile.audience.runtime.KtorAudienceHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

public object AndroidAudienceClientFactory {
  internal fun create(
    context: Context,
    config: AudienceClientConfig,
  ): AndroidAudienceComponents {
    val dependencies = AudienceDependencies(
      httpClient = KtorAudienceHttpClient(
        client = HttpClient(Android) {
          expectSuccess = false
        },
      ),
      secureStore = AndroidAudienceSecureStore(context.applicationContext),
      metadataProvider = AndroidAudienceMetadataProvider(context.applicationContext),
      sqlDriverFactory = AndroidAudienceSqlDriverFactory(context.applicationContext),
      installSentinel = AndroidAudienceInstallSentinel(context.applicationContext),
      clock = object : AudienceClock {
        override fun nowEpochMillis(): Long = System.currentTimeMillis()
      },
      uuidGenerator = object : AudienceUuidGenerator {
        override fun generate(): String = UUID.randomUUID().toString()
      },
      logger = config.logger,
    )
    return AndroidAudienceComponents(
      audienceClient = AudienceClientImpl(
        config = config,
        dependencies = dependencies,
      ),
      dependencies = dependencies,
      config = config,
    )
  }
}

internal data class AndroidAudienceComponents(
  val audienceClient: AudienceClient,
  val dependencies: AudienceDependencies,
  val config: AudienceClientConfig,
)

private class AndroidAudienceSqlDriverFactory(
  private val context: Context,
) : AudienceSqlDriverFactory {
  // TODO: Add SQLCipher encryption via `androidx.sqlite:sqlite` + `net.zetetic:sqlcipher-android`.
  //  Replace AndroidSqliteDriver with SupportSqliteOpenHelper backed by SQLCipher, passing an
  //  encryption key derived from the Android Keystore. This protects audience state and queued
  //  operations at rest on rooted or compromised devices.
  override fun create(): app.cash.sqldelight.db.SqlDriver =
    AndroidSqliteDriver(
      schema = AudienceDatabaseSchema,
      context = context,
      name = "pillow_mobile_core.db",
    )
}

private class AndroidAudienceSecureStore(
  context: Context,
) : AudienceSecureStore {
  private val sharedPreferences = createEncryptedPreferences(
    context = context,
    name = "pillow_mobile_core_secure_store",
  )

  override suspend fun readSessionToken(): String? =
    readValue(SESSION_TOKEN_KEY)

  override suspend fun writeSessionToken(token: String) {
    writeValue(SESSION_TOKEN_KEY, token)
  }

  override suspend fun clearSessionToken() {
    clearValue(SESSION_TOKEN_KEY)
  }

  override suspend fun readValue(key: String): String? =
    sharedPreferences.getString(key, null)

  override suspend fun writeValue(key: String, value: String) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override suspend fun clearValue(key: String) {
    sharedPreferences.edit().remove(key).apply()
  }

  private companion object {
    const val SESSION_TOKEN_KEY = "audience_session_token"
  }
}

internal fun createEncryptedPreferences(
  context: Context,
  name: String,
): android.content.SharedPreferences =
  try {
    buildEncryptedPreferences(context, name)
  } catch (_: Exception) {
    // EncryptedSharedPreferences can throw AEADBadTagException when the Android
    // Keystore master key is invalidated (app reinstall, OS update, Samsung Keystore
    // quirks). Delete the corrupted file and recreate from scratch.
    context.deleteSharedPreferences(name)
    buildEncryptedPreferences(context, name)
  }

private fun buildEncryptedPreferences(
  context: Context,
  name: String,
): android.content.SharedPreferences =
  EncryptedSharedPreferences.create(
    context,
    name,
    MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
  )

/**
 * Uses a marker file in `noBackupFilesDir` which is excluded from Android auto-backup
 * and always deleted on app uninstall. This lets the SDK detect reinstalls even when
 * the SQLite database is restored from a cloud backup.
 */
private class AndroidAudienceInstallSentinel(
  context: Context,
) : AudienceInstallSentinel {
  private val sentinelFile = File(context.noBackupFilesDir, ".pillow_install_sentinel")

  override fun exists(): Boolean = sentinelFile.exists()

  override fun mark() {
    sentinelFile.parentFile?.mkdirs()
    sentinelFile.createNewFile()
  }
}

private class AndroidAudienceMetadataProvider(
  private val context: Context,
) : AudienceMetadataProvider {
  override fun current(): AudienceMetadata {
    val packageInfo = try {
      context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (_: Exception) {
      null
    }
    val applicationLabel = try {
      context.packageManager
        .getApplicationLabel(context.applicationInfo)
        .toString()
        .trim()
        .takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
      null
    }
    return AudienceMetadata(
      appName = applicationLabel,
      appVersion = packageInfo?.versionName,
      appBuild = packageInfo?.let { packageLongVersionCode(it).toString() },
      osName = "Android",
      osVersion = Build.VERSION.RELEASE,
      deviceManufacturer = Build.MANUFACTURER,
      deviceModel = Build.MODEL,
      locale = Locale.getDefault().toLanguageTag(),
      timezone = TimeZone.getDefault().id,
    )
  }

  private fun packageLongVersionCode(packageInfo: android.content.pm.PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageInfo.longVersionCode
    } else {
      @Suppress("DEPRECATION")
      packageInfo.versionCode.toLong()
    }
}
