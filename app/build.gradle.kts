import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildTimestamp = DateTimeFormatter
    .ofPattern("yyyyMMdd-HHmmss")
    .withZone(ZoneId.systemDefault())
    .format(Instant.now())

android {
    namespace = "com.cdg.app.cbrowxr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cdg.app.cbrowxr"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-alpha"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    applicationVariants.all {
        val variantName = name
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val packageType = output.filters
                .joinToString("-") { it.identifier }
                .ifBlank { "universal" }
            output.outputFileName =
                "C-Browxr-${defaultConfig.versionName}-${variantName}-${packageType}-${buildTimestamp}.apk"
        }
    }
}

val cleanBuildArtifacts = tasks.register("cleanBuildArtifacts") {
    group = "build"
    description = "Deletes build outputs before starting a fresh compile"
    delete(layout.buildDirectory)
}

tasks.configureEach {
    if (name.matches(Regex("""pre[A-Za-z]*Build"""))) {
        dependsOn(cleanBuildArtifacts)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Mozilla GeckoView (Sin telemetría ni Google)
    implementation("org.mozilla.geckoview:geckoview:130.0.20240829075237")

    // yt-dlp + FFmpeg desacoplados
    val ytdlVersion = "0.17.2"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlVersion")
    implementation("org.apache.commons:commons-compress:1.12")
    implementation("commons-io:commons-io:2.5")

    // Red, Proxy Local y WorkManager
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
