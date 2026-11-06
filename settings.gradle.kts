pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io") // pinned libsu 6.0.0 dependency
    }
}

rootProject.name = "MMRL-LSPosed"
include(":app")
include(":hook-lab-module", ":hook-lab-target")

// Source-pinned shared library: Gradle project, not a Maven or dynamic Git dependency.
include(":shared-contracts")
project(":shared-contracts").projectDir = file("integration/shared-contracts")
