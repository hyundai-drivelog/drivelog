import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// The release key and its passwords live outside git. Without this file, release builds stay unsigned.
val signingFile = rootProject.file("keystore.properties")
val signing = Properties().apply { if (signingFile.isFile) signingFile.reader().use { load(it) } }
val submissionTeam = providers.gradleProperty("submissionTeam")
val submissionApp = providers.gradleProperty("submissionApp").orElse("DRIVELOG")

val pleosSdkEnabled = providers.gradleProperty("pleosSdkEnabled")
    .orElse("true").get().toBooleanStrict()
// The vehicle-only submission leaves the phone hand-off out. -PphoneLinkEnabled=true brings it back.
val phoneLinkEnabled = providers.gradleProperty("phoneLinkEnabled")
    .orElse("false").get().toBooleanStrict()
val releaseApiBaseUrl = providers.gradleProperty("releaseApiBaseUrl").orElse("").get()
// Where a phone card page is published (the old web/card/ was removed on 2026-11-10). Without it the car offers no QR.
val cardWebUrl = providers.gradleProperty("cardWebUrl").orElse("").get()
require(cardWebUrl.isBlank() || (cardWebUrl.startsWith("https://") && cardWebUrl.endsWith("/") && !cardWebUrl.contains('"') && !cardWebUrl.contains('#'))) {
    "cardWebUrl must be an HTTPS URL ending in /"
}
require(releaseApiBaseUrl.isBlank() ||
    (releaseApiBaseUrl.startsWith("https://") && releaseApiBaseUrl.endsWith("/") && !releaseApiBaseUrl.contains('"'))) {
    "releaseApiBaseUrl must be an HTTPS base URL ending in /"
}

android {
    namespace = "com.drivelog.app"
    useLibrary("android.car")
    sourceSets.named("main") {
        kotlin.directories += if (pleosSdkEnabled) "src/pleos/java" else "src/noPleos/java"
    }
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }
    defaultConfig {
        applicationId = "com.drivelog.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("boolean", "PLEOS_SDK_INCLUDED", pleosSdkEnabled.toString())
        buildConfigField("boolean", "PHONE_LINK_ENABLED", phoneLinkEnabled.toString())
        buildConfigField("String", "CARD_WEB_URL", "\"$cardWebUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The map library is native. Only vehicle hardware and the emulator image are packaged.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        if (signingFile.isFile) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Contest format: apk/팀명_앱이름_release.apk
tasks.register("packageSubmissionApk") {
    dependsOn("assembleRelease")
    val signed = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val outputDir = rootProject.layout.projectDirectory.dir("apk")
    val hasSigning = signingFile.isFile
    doLast {
        val team = submissionTeam.orNull?.trim().orEmpty()
        if (team.isEmpty()) throw GradleException("팀명이 필요합니다: -PsubmissionTeam=<팀명>")
        if (!hasSigning) throw GradleException("서명 설정이 없습니다: keystore.properties.example을 참고하세요")
        val target = outputDir.file("${team}_${submissionApp.get()}_release.apk").asFile
        signed.get().asFile.copyTo(target, overwrite = true)
        println("제출용 APK: $target")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.maplibre)
    implementation(libs.zxing.core)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    // The SDK-specific source directory is excluded in the SDK-free build.
    // -PpleosSdkEnabled=false also removes the Pleos repository from settings.
    if (pleosSdkEnabled) {
        implementation(libs.pleos.vehicle)
        implementation(libs.pleos.fused.location)
        implementation(libs.pleos.navi.helper)
        implementation(libs.pleos.text.to.speech)
        implementation(libs.pleos.speech.to.text)
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
