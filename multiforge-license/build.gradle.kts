plugins {
    id("multiforge.base")
    `maven-publish`
}

description = "Ed25519-signed license token verifier. No Minecraft dependency."

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

// Published to mavenLocal only so multiforge-runtime (which project-depends
// on this module) is itself resolvable as a Maven artifact by the vendored
// NeoForge fork build — see multiforge-runtime/build.gradle.kts. No change
// to the verifier itself.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "net.multiforge"
            artifactId = "multiforge-license"
            pom {
                name.set("MultiForge License")
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
}
