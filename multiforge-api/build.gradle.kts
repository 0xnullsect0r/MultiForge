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

plugins {
    id("multiforge.base")
    `maven-publish`
}

description = "MultiForge public API — schedulers, event dispatch annotations, marker types. This is the jar mod authors compile against."

dependencies {
    // Public API stays framework-free. The runtime that binds it may pull
    // in slf4j, but callers of the API do not need to.
    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

// Templated version file baked into the api jar so callers can read
// MultiForgeApi.VERSION at runtime and refuse to run against an older
// runtime binding.
tasks.processResources {
    val tokens = mapOf(
        "version" to project.version.toString(),
        "buildTime" to System.currentTimeMillis().toString(),
    )
    inputs.properties(tokens)
    // `name = ...` on the file, not `rename { }`: inside filesMatching that
    // resolves to the task's rename and never applied, so the jar carried the
    // raw template and MultiForgeApi.VERSION read "dev" (see the runtime module).
    filesMatching("**/multiforge-api.properties.in") {
        expand(tokens)
        name = "multiforge-api.properties"
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "net.multiforge"
            artifactId = "multiforge-api"
            pom {
                name.set("MultiForge API")
                description.set(project.description)
                licenses {
                    license {
                        name.set("GNU General Public License, Version 3")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
    // Publication repositories are declared by the CI job at release time.
}
