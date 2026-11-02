plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val pleosSdkEnabled = providers.gradleProperty("pleosSdkEnabled")
    .orElse("true").get().toBooleanStrict()
val releaseApiBaseUrl = providers.gradleProperty("releaseApiBaseUrl").orElse("").get()
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
        }
        release {
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
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
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
