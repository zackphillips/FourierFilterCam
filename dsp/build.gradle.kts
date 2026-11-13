plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    // Shared parity fixtures, also read by the iOS port's tests (see FixtureExportTest).
    val fixturesDir = rootProject.layout.projectDirectory.dir("fixtures")
    inputs.files(fixturesDir.asFileTree).withPropertyName("fixtures")
    systemProperty("fixtures.dir", fixturesDir.asFile.absolutePath)
    systemProperty("fixtures.update", providers.gradleProperty("updateFixtures").isPresent.toString())
    if (providers.gradleProperty("updateFixtures").isPresent) outputs.upToDateWhen { false }
}

dependencies {
    // The Android app supplies org.opencv at runtime from the official AAR.
    // This module only compiles against the API, so it stays a plain JVM
    // library that can be unit-tested on a dev machine with desktop OpenCV.
    compileOnly(libs.opencv.desktop)

    testImplementation(libs.opencv.desktop)
    testImplementation(libs.junit)
}
