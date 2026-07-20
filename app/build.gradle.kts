plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.ksp)
    kotlin("plugin.serialization") version "1.9.22"
}

val releaseKeystorePath = System.getenv("KEYSTORE_FILE_PATH")?.takeIf { it.isNotBlank() }
val releaseKeystorePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = releaseKeystorePath != null &&
    releaseKeystorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    file(releaseKeystorePath).isFile

android {
    namespace = "com.shiraka.locatiobprovid"
    compileSdk = 36

    fun getLocalConfig(key: String): String? {
        val localYml = file("../local.yml")
        if (localYml.exists()) {
            val line = localYml.readLines().find { it.startsWith("$key:") }
            if (line != null) {
                return line.substringAfter(":").trim().removeSurrounding("\"")
                    .removeSurrounding("'")
            }
        }
        return null
    }

    val googleMapsApiKey =
        System.getenv("GOOGLE_MAPS_API_KEY") ?: getLocalConfig("GOOGLE_MAPS_API_KEY") ?: ""

    defaultConfig {
        applicationId = "com.shiraka.locatiobprovid"
        minSdk = 26
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 14300
        versionName = "1.4.3"

        vectorDrawables {
            useSupportLibrary = true
        }

        manifestPlaceholders["googleMapsApiKey"] = googleMapsApiKey

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }
    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$googleMapsApiKey\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$googleMapsApiKey\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xskip-metadata-version-check",
            "-opt-in=kotlinx.serialization.InternalSerializationApi"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.xposed.service)
    implementation(libs.koin.androidx.compose)
    implementation(libs.google.maps)
    implementation(libs.google.places)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)
}
