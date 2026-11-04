plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.mikeyphw.mmrl.lsposed"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.mikeyphw.mmrl.lsposed"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-s01"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
        }
    }

    buildFeatures {
        compose = true
        aidl = true // narrow read-only Binder IPC on AGP 9
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.coroutines.android)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // AndroidTest APK executes on the physical Vector/KernelSU device, not GitHub runners.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation(libs.compose.ui.tooling)
}
