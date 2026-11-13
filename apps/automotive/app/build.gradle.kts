plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Public, non-secret settings. See gradle.properties. The user can also edit them in the app.
val apiBaseUrl: String = providers.gradleProperty("danim.apiBaseUrl").getOrElse("")
val siteUrl: String = providers.gradleProperty("danim.siteUrl").getOrElse("")

android {
    namespace = "app.danim.automotive"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.danim.automotive"
        minSdk = 28
        // API 34 target: the Pleos Connect emulator image is API 34 and specialUse FGS applies from 34.
        targetSdk = 34
        // 0.2.0: adds the labelled mock demo mode for the competition emulator demonstration (2026-10-28).
        // 0.3.0: the broad companion feature mock over the shared competition fixture 2026-10-28.1 (2026-10-28, superseded).
        // 0.4.0: fixed competition records, maintenance summary and web/AI connection over competition-service.json 2026-10-29.1 (2026-10-29).
        versionCode = 8
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "DEFAULT_SITE_URL", "\"$siteUrl\"")
        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"${libs.versions.vehicleSdk.get()}\"")
    }

    // Release signing comes only from the environment at build time (CI secret or a local shell):
    // DANIM_ANDROID_KEYSTORE (path), DANIM_ANDROID_KEYSTORE_PASSWORD, DANIM_ANDROID_KEY_ALIAS,
    // DANIM_ANDROID_KEY_PASSWORD. Without all four the release build stays unsigned, exactly as
    // before; nothing about the keystore is ever committed or printed.
    val releaseKeystore = System.getenv("DANIM_ANDROID_KEYSTORE")?.takeIf { it.isNotBlank() }
    val releaseKeystorePassword = System.getenv("DANIM_ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val releaseKeyAlias = System.getenv("DANIM_ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val releaseKeyPassword = System.getenv("DANIM_ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    val releaseSigningConfigured = releaseKeystore != null && releaseKeystorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null
    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    lint { abortOnError = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    implementation(libs.vehicle.sdk)
    implementation(libs.tts.sdk)
    implementation(libs.stt.sdk)
    implementation(libs.navihelper.sdk)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)

    // Instrumentation tests for real Room persistence/migration only (no UI driving, no Robolectric).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
