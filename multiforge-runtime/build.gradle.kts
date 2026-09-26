plugins {
    id("multiforge.base")
    `maven-publish`
}

description = "MultiForge runtime — region manager, schedulers, diagnostics."

dependencies {
    implementation(project(":multiforge-api"))

    // Slf4j API only. Minecraft ships slf4j-api (pinned strictly to 2.0.9) and
    // the logger binding; depend on the same version so the fork's classpath
    // resolves.
    implementation("org.slf4j:slf4j-api:2.0.20")

    // TOML parser for multiforge-server.toml and the region pin file:
    // night-config (LGPL-3.0), the library NeoForge itself ships and loads its
    // own configs with, so the server gets no extra parser. Version matches
    // upstream/neoforge-1.21.1/gradle.properties:nightconfig_version.
    implementation("com.electronwill.night-config:toml:3.8.3")

    // net.neoforged:bus — the standalone event-bus library NeoForge itself uses
    // (net.neoforged.bus.api.*), not a Minecraft dependency. M12's DispatchingEventBus
    // implements IEventBus directly so the fork bridge (multiforge-patches/09-events/,
    // M12.2) can swap it in for NeoForge.EVENT_BUS. Version pinned to match
    // upstream/neoforge-1.21.1/gradle.properties:eventbus_version so the wrapped
    // instance is binary-compatible with what the vendored NeoForge tree ships.
    implementation("net.neoforged:bus:8.0.2")

    // Event-type resolution for lambda listeners, as the bus itself does it.
    // Apache-2.0; NeoForge ships the same version (typetools_version).
    implementation("net.jodah:typetools:0.6.3")

    // JetBrains annotations for @ApiStatus.Internal etc.
    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.20")
    testImplementation("org.awaitility:awaitility:4.2.1")
    // Property tests (EPL-2.0, test scope only: never shipped).
    testImplementation("net.jqwik:jqwik:1.9.1")
}

// Templated version file baked into the runtime jar.
//
// NOTE: `name = ...` on the FileCopyDetails inside filesMatching, NOT
// the outer AbstractCopyTask.rename(Closure) — the latter runs the
// closure against *every* file in the copy, which in v1.3.3 renamed
// META-INF/services/net.multiforge.api.spi.SchedulerHost to
// META-INF/services/multiforge-runtime.properties, breaking the SPI
// AND crashing securejarhandler on boot with "Invalid service type
// name" because "multiforge-runtime" is not a valid Java identifier.
tasks.processResources {
    val tokens = mapOf(
        "version" to project.version.toString(),
        "buildTime" to System.currentTimeMillis().toString(),
    )
    inputs.properties(tokens)
    filesMatching("**/multiforge-runtime.properties.in") {
        expand(tokens)
        name = "multiforge-runtime.properties"
    }
}

// Published to mavenLocal so the vendored NeoForge fork build
// (upstream/neoforge-1.21.1, a separate Gradle build with no includeBuild
// wiring to this one) can depend on it as a normal versioned artifact —
// see multiforge-patches/README.md and docs/blueprint.md M7.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "net.multiforge"
            artifactId = "multiforge-runtime"
            pom {
                name.set("MultiForge Runtime")
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
}
