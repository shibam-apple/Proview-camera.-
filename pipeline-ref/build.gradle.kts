import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
}

group = "app.proview"
version = "0.1.0"

// Target Java 17 bytecode (what Android's toolchain consumes) on whatever JDK runs the build.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Desktop tuning harness for the Lab enhancer (not part of the app or CI).
tasks.register<JavaExec>("enhanceSamples") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.proview.pipeline.tools.EnhanceSamplesKt")
    args = listOfNotNull(
        project.findProperty("in") as String?,
        project.findProperty("out") as String?,
        (project.findProperty("look") as String?) ?: "NATURAL",
        (project.findProperty("p") as String?) ?: "-",
        project.findProperty("only") as String?,
    )
    maxHeapSize = "4g"
}
