import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.library)
  alias(libs.plugins.sqldelight)
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
    val commonMain by getting
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


