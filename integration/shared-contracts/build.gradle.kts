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
    // AGP 9 built-in Kotlin does not compile Kotlin roots registered as Java sources.
    // Keep this directory linked to the immutable MMRL-Shared Git submodule.
    sourceSets.named("main") {
        kotlin.directories.add(
            rootProject.file("third_party/mmrl-shared/src/main/kotlin").absolutePath
        )
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
