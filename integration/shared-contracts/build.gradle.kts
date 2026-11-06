// N05B: compile the source-pinned, independent pure-Kotlin shared core as an
// Android library for this AGP 9 consumer. No source copying, Maven repository,
// extra Kotlin Android plugin or privileged code is introduced.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.mikeyphw.mmrl.shared.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    sourceSets.getByName("main").java.srcDir(
        rootProject.file("third_party/mmrl-shared/src/main/kotlin")
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
