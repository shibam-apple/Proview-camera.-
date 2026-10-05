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
    }
}

rootProject.name = "Proview"

include(":app")
include(":camera")

// Pure-Kotlin reference pipeline; buildable on its own (see pipeline-ref/settings.gradle.kts).
includeBuild("pipeline-ref")
