import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    java
    id("com.diffplug.spotless")
}

group = providers.gradleProperty("group").getOrElse("net.multiforge")
version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")

val javaVersion = providers.gradleProperty("javaVersion").getOrElse("21").toInt()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "-Xlint:all",
        "-Xlint:-serial",
        "-Xlint:-processing",
        "-Werror",
    ))
    options.release.set(javaVersion)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Bump the test JVM heap. Some concurrent-stress tests (e.g.
    // RegionFileIntegrationTest.concurrentReadStress) drive ~16 threads
    // × 5s of random reads producing many transient byte[] allocations
    // and OOM under the JVM's default heap on shared CI runners; local
    // dev boxes with more RAM never trip it. 2 GiB is comfortably above
    // any single test's working set while still fitting the CI runner.
    maxHeapSize = "2g"
}

configure<SpotlessExtension> {
    java {
        target("src/**/*.java")
        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        licenseHeaderFile(rootProject.file("buildSrc/src/main/resources/license-header.txt"))
    }
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}
