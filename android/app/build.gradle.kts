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

    defaultConfig {
        applicationId = "io.github.aitm.pleosapp"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.trimEnd('/')}\"")
        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"${libs.versions.vehicle.get()}\"")
        buildConfigField("String", "NAVI_SDK_VERSION", "\"${libs.versions.navi.get()}\"")
    }

    flavorDimensions += "environment"
    productFlavors {
        create("demo") {
            dimension = "environment"
            applicationIdSuffix = ".demo"
            resValue("string", "app_name", "A+ ITM Demo")
        }
        create("pleos") {
            dimension = "environment"
            resValue("string", "app_name", "A+ ITM Pleos")
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

dependencies {
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
}
