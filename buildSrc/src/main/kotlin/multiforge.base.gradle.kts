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
    // Concurrent-stress tests allocate heavily; the default heap is too small
    // on shared CI runners. 2 GiB fits every test's working set.
    maxHeapSize = "2g"
    // Opt-in throughput benches (@EnabledIfSystemProperty runBench=true):
    // ./gradlew test -PrunBench=true, or -DrunBench=true on the Gradle JVM.
    val runBench = providers.gradleProperty("runBench").orNull ?: System.getProperty("runBench")
    if (runBench != null) systemProperty("runBench", runBench)
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
