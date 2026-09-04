plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Spotless plugin for code-formatting checks.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:6.25.0")
}
