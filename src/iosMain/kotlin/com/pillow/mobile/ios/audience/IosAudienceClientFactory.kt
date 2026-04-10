@file:OptIn(
  kotlinx.cinterop.BetaInteropApi::class,
  kotlinx.cinterop.ExperimentalForeignApi::class,
)

package com.pillow.mobile.ios.audience

import app.cash.sqldelight.driver.native.NativeSqliteDriver
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
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIDevice
import platform.posix.time

public object IosAudienceClientFactory {
  public fun create(config: AudienceClientConfig): AudienceClient =
    createComponents(config).audienceClient
}

internal data class IosAudienceClientComponents(
  val audienceClient: AudienceClient,
  val dependencies: AudienceDependencies,
)

internal fun createComponents(config: AudienceClientConfig): IosAudienceClientComponents {
  val dependencies =
    AudienceDependencies(
      httpClient = KtorAudienceHttpClient(
        client = HttpClient(Darwin) {
          expectSuccess = false
        },
      ),
      secureStore = IosAudienceSecureStore(),
      metadataProvider = IosAudienceMetadataProvider(),
      sqlDriverFactory = IosAudienceSqlDriverFactory(),
      installSentinel = IosAudienceInstallSentinel(),
      clock = object : AudienceClock {
        override fun nowEpochMillis(): Long = time(null).toLong() * 1_000L
      },
      uuidGenerator = object : AudienceUuidGenerator {
        override fun generate(): String = NSUUID().UUIDString()
      },
      logger = config.logger,
    )

  return IosAudienceClientComponents(
    audienceClient = AudienceClientImpl(config = config, dependencies = dependencies),
    dependencies = dependencies,
  )
}

private class IosAudienceSqlDriverFactory : AudienceSqlDriverFactory {
  // TODO: Add SQLCipher encryption via a SQLCipher-backed NativeSqliteDriver. Use a key derived
  //  from the iOS Keychain to encrypt the database at rest, protecting audience state and queued
  //  operations on jailbroken or compromised devices.
  override fun create(): app.cash.sqldelight.db.SqlDriver =
    NativeSqliteDriver(
      schema = AudienceDatabaseSchema,
      name = "pillow_mobile_core.db",
    )
}

private class IosAudienceMetadataProvider : AudienceMetadataProvider {
  override fun current(): AudienceMetadata =
    AudienceMetadata(
      appName = (
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleDisplayName")
          ?: NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleName")
        ) as? String,
      appVersion = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String,
      appBuild = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String,
      osName = "iOS",
      osVersion = UIDevice.currentDevice.systemVersion,
      deviceManufacturer = "Apple",
      deviceModel = UIDevice.currentDevice.model,
      locale = null,
      timezone = null,
    )
}

/**
 * Uses NSUserDefaults which lives in the app sandbox and is deleted on uninstall.
 * Unlike Keychain items (used by IosAudienceSecureStore), NSUserDefaults does not
 * persist across reinstalls, making it suitable for detecting fresh installs.
 */
private class IosAudienceInstallSentinel : AudienceInstallSentinel {
  override fun exists(): Boolean =
    NSUserDefaults.standardUserDefaults.boolForKey(SENTINEL_KEY)

  override fun mark() {
    NSUserDefaults.standardUserDefaults.setBool(true, SENTINEL_KEY)
  }

  private companion object {
    const val SENTINEL_KEY = "com.pillow.mobile.install_sentinel"
  }
}

private class IosAudienceSecureStore : AudienceSecureStore {
  private val keychain = IosKeychainStore()

  override suspend fun readSessionToken(): String? = readValue(ACCOUNT)

  override suspend fun writeSessionToken(token: String) {
    writeValue(ACCOUNT, token)
  }

  override suspend fun clearSessionToken() {
    clearValue(ACCOUNT)
  }

  override suspend fun readValue(key: String): String? = keychain.readValue(key)

  override suspend fun writeValue(key: String, value: String) {
    keychain.writeValue(key, value)
  }

  override suspend fun clearValue(key: String) {
    keychain.clearValue(key)
  }

  private companion object {
    const val ACCOUNT = "audience_session_token"
  }
}

internal class IosKeychainStore(
  private val service: String = SERVICE,
) {
  fun readValue(key: String): String? = memScoped {
    val result = alloc<CFTypeRefVar>()
    val query = buildQuery(account = key) { query ->
      CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
      CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
    }
    val status = try {
      SecItemCopyMatching(query, result.ptr)
    } finally {
      CFRelease(query)
    }
    if (status != 0) {
      return null
    }

    @Suppress("UNCHECKED_CAST")
    val data = CFBridgingRelease(result.value) as? NSData ?: return null
    NSString.create(data = data, encoding = NSUTF8StringEncoding.toULong())?.toString()
  }

  fun writeValue(key: String, value: String) {
    clearValue(key)
    val valueData = value.encodeToByteArray().toNSData()
    val retainedData = CFBridgingRetain(valueData)
    val query = buildQuery(account = key) { query ->
      CFDictionarySetValue(query, kSecValueData, retainedData)
    }
    try {
      SecItemAdd(query, null)
    } finally {
      CFRelease(query)
      if (retainedData != null) CFRelease(retainedData)
    }
  }

  fun clearValue(key: String) {
    val query = buildQuery(account = key)
    try {
      SecItemDelete(query)
    } finally {
      CFRelease(query)
    }
  }

  private fun buildQuery(
    account: String,
    extras: ((CFMutableDictionaryRef) -> Unit)? = null,
  ): CFDictionaryRef {
    val capacity = 6L
    val query = CFDictionaryCreateMutable(
      null, capacity,
      kCFTypeDictionaryKeyCallBacks.ptr,
      kCFTypeDictionaryValueCallBacks.ptr,
    )!!
    CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
    val retainedService = CFBridgingRetain(service as NSString)
    val retainedAccount = CFBridgingRetain(account as NSString)
    CFDictionarySetValue(query, kSecAttrService, retainedService)
    CFDictionarySetValue(query, kSecAttrAccount, retainedAccount)
    if (retainedService != null) CFRelease(retainedService)
    if (retainedAccount != null) CFRelease(retainedAccount)
    extras?.invoke(query)
    return query
  }

  private companion object {
    const val SERVICE = "com.pillow.mobile"
  }
}

private fun ByteArray.toNSData(): NSData =
  pin().let { pinned ->
    try {
      NSData.create(
        bytes = pinned.addressOf(0).reinterpret<ByteVar>(),
        length = size.toULong(),
      )
    } finally {
      pinned.unpin()
    }
  }
