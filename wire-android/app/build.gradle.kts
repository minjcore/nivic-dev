import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
}

android {
    namespace   = "app.saving.wire"
    compileSdk  = 35

    defaultConfig {
        applicationId = "app.saving.wire"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile     = rootProject.file(localProps["STORE_FILE"] as String)
            storePassword = localProps["STORE_PASSWORD"] as String
            keyAlias      = localProps["KEY_ALIAS"] as String
            keyPassword   = localProps["KEY_PASSWORD"] as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig   = signingConfigs.getByName("release")
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
}

dependencies {
    implementation(libs.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.core)
}
