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
    id("net.neoforged.moddev") version "2.0.78"
}

description = "MultiForge client debug mod — F3 overlay, chunk borders, tick-cost heatmap, pin selection."

// v1.3.5: net.neoforged.moddev supplies a real NeoForge compile classpath
// (NeoForm-produced vanilla + NeoForge patches, merged) so this module
// builds through the ordinary Gradle build. Prior to v1.3.5 the module
// carried a compileOnly on the -universal classifier that only resolved
// ~1.4k of the ~8k needed classes; six of the nine source files failed
// to compile, so the built jar contained three classes and no FMLModType
// manifest — not a distributable mod. See CHANGELOG v1.3.5 for the
// end-to-end story.
neoForge {
    version = providers.gradleProperty("neoForgeVersion").get()
    validateAccessTransformers = true
}

// The debug wire protocol (DebugPayload / DebugPacketCodec / DebugPacketKind) is
// shared with the server, whose copy lives in multiforge-runtime. The client takes
// its own copy, relocated to net.multiforge.client.wire, instead of depending on
// (and shipping) the runtime: a mod jar that also carried net.multiforge.runtime.*
// would split those packages with the runtime module a MultiForge server already
// loads, and the module layer refuses split packages. The wire classes use only
// java.*, so a source copy with the package renamed is enough.
val relocatedWire = layout.buildDirectory.dir("generated/sources/wire/java")
val relocateWire by tasks.registering(Copy::class) {
    from(project(":multiforge-runtime").layout.projectDirectory.dir("src/main/java/net/multiforge/runtime/diagnostics/wire"))
    into(relocatedWire.map { it.dir("net/multiforge/client/wire") })
    filter { line ->
        line.replace("package net.multiforge.runtime.diagnostics.wire;", "package net.multiforge.client.wire;")
            .replace("net.multiforge.runtime.diagnostics.wire.", "net.multiforge.client.wire.")
    }
}
sourceSets.main {
    java.srcDir(relocateWire.map { relocatedWire.get() })
}

dependencies {

    // NOTE: SLF4J is transitively provided by moddev's neoForge{} block
    // (net.neoforged:minecraft-dependencies pins it strictly to 2.0.9);
    // declaring a compileOnly on 2.0.13 here caused an unresolvable
    // version-strictly conflict at compile time in v1.3.5.

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

// v1.3.11: neoforge.mods.toml carries `version = "${version}"` which
// moddev 2.0.78 does NOT auto-expand. Without this block the shipped
// jar contains the literal string "${version}", and FML rejects the
// mod at scan with "Illegal version number specified version". Mirrors
// the pattern in multiforge-runtime/build.gradle.kts (processResources
// + filesMatching + expand). No buildTime token here — that would
// defeat Gradle's build cache and the mods.toml has no such need.
tasks.processResources {
    val tokens = mapOf("version" to project.version.toString())
    inputs.properties(tokens)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(tokens)
    }
}
