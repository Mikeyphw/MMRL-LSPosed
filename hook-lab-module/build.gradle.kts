// Adapted from Mikeyphw/MMRL Phase05 disposable hook lab (GPL-3.0).
plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.mikeyphw.lsposed.hooklab.module"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mikeyphw.lsposed.hooklab.module"
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
    packaging { resources { merges += "META-INF/xposed/*" } }
}
dependencies { compileOnly(libs.libxposed.api) }
