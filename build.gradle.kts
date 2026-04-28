import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.library)
  alias(libs.plugins.sqldelight)
}

// Read VERSION_NAME from gradle.properties (mirrored to public/android/gradle.properties
// for Maven Central and public/ios/version.txt for CocoaPods/SPM). Embedding it as a
// Kotlin constant ensures audience telemetry reports the SDK version, not the host app's
// CFBundleShortVersionString / package versionName.
val sdkVersionName: String = providers.gradleProperty("VERSION_NAME").get()

val generatedSdkBuildConfigDir = layout.buildDirectory.dir("generated/sources/sdkBuildConfig/commonMain/kotlin")

val generateSdkBuildConfig =
  tasks.register("generateSdkBuildConfig") {
    val outputDir = generatedSdkBuildConfigDir
    val versionName = sdkVersionName
    inputs.property("sdkVersion", versionName)
    outputs.dir(outputDir)
    doLast {
      val target = outputDir.get().asFile
        .resolve("com/pillow/mobile/sdk/PillowSdkBuildConfig.kt")
      target.parentFile.mkdirs()
      target.writeText(
        """
        package com.pillow.mobile.sdk

        internal object PillowSdkBuildConfig {
          const val SDK_VERSION: String = "$versionName"
        }
        """.trimIndent() + "\n",
      )
    }
  }

kotlin {
  androidTarget()
  jvm("desktop") {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_17)
    }
  }
  iosX64()
  iosArm64()
  iosSimulatorArm64()
  jvmToolchain(17)

  targets.withType<KotlinNativeTarget>().configureEach {
    binaries.framework {
      baseName = "PillowSDKCore"
      isStatic = true
      linkerOpts("-lsqlite3")
    }
    compilations.getByName("main") {
      cinterops {
        create("NoAccessoryWebView") {
          defFile(project.file("src/nativeInterop/cinterop/NoAccessoryWebView.def"))
        }
      }
    }
  }

  sourceSets {
    val commonMain by getting {
      // Passing the TaskProvider (not just the dir) lets Gradle auto-wire the
      // generation task as a dependency of every Kotlin compile task that
      // consumes commonMain — no eager `tasks.matching` traversal needed.
      kotlin.srcDir(generateSdkBuildConfig)
    }
    val commonTest by getting
    val androidMain by getting
    val desktopMain by getting
    val desktopTest by getting
    val iosX64Main by getting
    val iosArm64Main by getting
    val iosSimulatorArm64Main by getting
    val iosMain by creating {
      dependsOn(commonMain)
      iosX64Main.dependsOn(this)
      iosArm64Main.dependsOn(this)
      iosSimulatorArm64Main.dependsOn(this)
    }

    commonMain.dependencies {
      implementation(libs.coroutines.core)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.serialization.kotlinx.json)
      implementation(libs.serialization.json)
      implementation(libs.sqldelight.runtime)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.coroutines.test)
    }
    androidMain.dependencies {
      implementation(libs.androidx.security.crypto)
      implementation(libs.androidx.webkit)
      implementation(libs.coroutines.android)
      implementation(libs.ktor.client.android)
      implementation(libs.sqldelight.android.driver)
    }
    desktopMain.dependencies {
      implementation(libs.ktor.client.cio)
      implementation(libs.sqldelight.sqlite.driver)
    }
    desktopTest.dependencies {
      implementation(libs.sqldelight.sqlite.driver)
    }
    iosMain.dependencies {
      implementation(libs.ktor.client.darwin)
      implementation(libs.sqldelight.native.driver)
    }
  }
}

android {
  namespace = "com.pillow.mobile"
  compileSdk = libs.versions.android.compile.sdk.get().toInt()

  defaultConfig {
    minSdk = libs.versions.android.min.sdk.get().toInt()
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

sqldelight {
  databases {
    create("AudienceDatabase") {
      packageName.set("com.pillow.mobile.audience.persistence")
    }
  }
}


tasks.register("compileAllTargets") {
  group = "verification"
  description = "Compiles the desktop, iOS simulator, and Android debug targets."
  dependsOn(
    "compileKotlinDesktop",
    "compileKotlinIosSimulatorArm64",
    "compileDebugKotlinAndroid",
  )
}

tasks.register("verifyCore") {
  group = "verification"
  description = "Runs shared core tests and compiles all primary targets."
  dependsOn(
    "desktopTest",
    "compileAllTargets",
  )
}


