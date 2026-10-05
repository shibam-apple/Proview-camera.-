// The reference pipeline is a standalone, pure-Kotlin build so it can be built and tested
// without the Android SDK. The main build pulls it in with includeBuild("pipeline-ref").
rootProject.name = "pipeline-ref"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
