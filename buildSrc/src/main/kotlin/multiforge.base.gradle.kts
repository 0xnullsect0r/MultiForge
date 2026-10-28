/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
