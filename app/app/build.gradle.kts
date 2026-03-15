import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load signing config from local.properties if exists
val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        load(localFile.inputStream())
    }
}

val repoDir = rootProject.projectDir.parentFile ?: rootProject.projectDir

fun getSigningConfig(key: String, envVar: String, defaultValue: String): String {
    return System.getenv(envVar)
        ?: localProperties.getProperty(key)
        ?: defaultValue
}

fun runGitCommand(vararg args: String): String? {
    return try {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.waitFor() == 0 && output.isNotBlank()) output else null
    } catch (_: Exception) {
        null
    }
}

fun getAppVersionName(defaultValue: String): String {
    return System.getenv("APP_VERSION_NAME")
        ?.trim()
        ?.removePrefix("v")
        ?.ifBlank { defaultValue }
        ?: runGitCommand("describe", "--tags", "--abbrev=0", "--match", "v*")
            ?.removePrefix("v")
            ?.ifBlank { defaultValue }
        ?: defaultValue
}

fun getAppVersionCode(defaultValue: Int): Int {
    return System.getenv("APP_VERSION_CODE")
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: runGitCommand("rev-list", "--count", "HEAD")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        ?: defaultValue
}

android {
    namespace = "com.plwd.audiochannelguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.plwd.audiochannelguard"
        minSdk = 31
        targetSdk = 34
        versionCode = getAppVersionCode(29)
        versionName = getAppVersionName("1.0.1")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = getSigningConfig("SIGNING_STORE_FILE", "SIGNING_STORE_FILE", "${System.getProperty("user.home")}/.android/debug.keystore")
            storeFile = file(storeFilePath)
            storePassword = getSigningConfig("SIGNING_STORE_PASSWORD", "SIGNING_STORE_PASSWORD", "android")
            keyAlias = getSigningConfig("SIGNING_KEY_ALIAS", "SIGNING_KEY_ALIAS", "androiddebugkey")
            keyPassword = getSigningConfig("SIGNING_KEY_PASSWORD", "SIGNING_KEY_PASSWORD", "android")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
