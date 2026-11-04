// Disposable provider target. Never install over an existing unrelated package.
plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.mikeyphw.lsposed.hooklab.target"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mikeyphw.lsposed.hooklab.target"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-s08"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildTypes { getByName("debug") { isMinifyEnabled = false } }
}
