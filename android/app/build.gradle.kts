import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val developerProperties = Properties().apply {
    val file = rootProject.file("developer.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val supabaseUrl = providers.environmentVariable("SUPABASE_URL")
    .orElse(developerProperties.getProperty("SUPABASE_URL", ""))
    .get().trim()
require(supabaseUrl.isEmpty() || supabaseUrl.matches(Regex("https://[A-Za-z0-9.-]+/?"))) {
    "SUPABASE_URL must be an HTTPS project origin without a path, query, or credentials."
}

android {
    namespace = "io.github.aitm.pleosapp"
    compileSdk = 35
    useLibrary("android.car", false)

    defaultConfig {
        applicationId = "io.github.aitm.pleosapp"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.5.0-safetystep"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.trimEnd('/')}\"")
        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"${libs.versions.vehicle.get()}\"")
        buildConfigField("String", "NAVI_SDK_VERSION", "\"${libs.versions.navi.get()}\"")
    }

    flavorDimensions += "environment"
    productFlavors {
        create("demo") {
            dimension = "environment"
            applicationIdSuffix = ".demo"
            resValue("string", "app_name", "세이프티스탭 Demo")
        }
        create("pleos") {
            dimension = "environment"
            resValue("string", "app_name", "세이프티스탭")
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
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
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

configurations.maybeCreate("pleosDebugImplementation")

dependencies {
    // Pinned emulator-only occupancy adapter for Vehicle 2.3.2's Boolean conversion defect.
    add("pleosDebugImplementation", "ai.pleos.playground:vehicle-aidl:1.1.1")
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    "pleosImplementation"(libs.pleos.vehicle)
    "pleosImplementation"(libs.pleos.navi)
    "pleosDebugImplementation"(libs.pleos.tts)
    "pleosDebugImplementation"(libs.pleos.stt)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
