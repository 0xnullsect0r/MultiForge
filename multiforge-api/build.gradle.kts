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
    filesMatching("multiforge-api.properties.in") {
        expand(tokens)
        rename { "multiforge-api.properties" }
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
                        name.set("MultiForge Proprietary")
                        url.set("https://github.com/0xnullsect0r/MultiForge/blob/main/LICENSE")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
    // Publication repositories are declared by the CI job at release time.
}
