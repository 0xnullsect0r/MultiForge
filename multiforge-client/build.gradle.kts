plugins {
    id("multiforge.base")
}

description = "MultiForge client debug mod — F3 overlay, chunk borders, tick-cost heatmap, pin selection."

// The client debug mod is a full NeoForge mod (Track C1, M6): the
// `@Mod("multiforge_debug")` entry point and its four renderers under
// src/main/java/net/multiforge/client/ reference real NeoForge/Minecraft
// client API (RegisterPayloadHandlersEvent, RenderGuiEvent,
// RenderLevelStageEvent, GuiGraphics, LevelRenderer, ...), so this
// module needs the vendored fork's client-dev classpath to compile.
//
// TRACK C1 STATUS (M6): `net.neoforged:neoforge:<version>` CAN be
// published to mavenLocal via
//   cd upstream/neoforge-1.21.1 && ./gradlew :neoforge:publishToMavenLocal
// (this required manually creating an empty
// projects/neoforge/build/changelog.txt first -- the fork's own
// `createChangelog` task is gated behind an `onlyIf` that is false in
// this offline dev environment; see projects/neoforge/build.gradle's
// `changelog {}` block for the real generator, worth fixing upstream).
// Two gaps remain before `./gradlew :multiforge-client:compileJava`
// passes through the ordinary build, both confirmed by actually running
// it here:
//   1. The OUTER build's `dependencyResolutionManagement.repositories`
//      (root settings.gradle.kts) does not include `mavenLocal()` --
//      only mavenCentral() and the NeoForge releases repo -- so even a
//      successful local publish is invisible to this module today.
//      Adding it is a one-line root settings.gradle.kts change, kept
//      out of this commit (out of this task's file scope).
//   2. Even with (1) fixed, `net.neoforged:neoforge` publishes only
//      data-only Gradle variants for downstream consumption
//      (`modDevBundle` / `modDevConfig`, see the published `.module`
//      file), meant to be resolved by the `net.neoforged.moddev`
//      Gradle plugin, which merges NeoForge's own patches with a
//      NeoForm-produced, officially-mapped vanilla Minecraft jar
//      (`net.neoforged:neoform:1.21.1-20240808.144430`) into a real
//      compile classpath. Its `-universal` classifier jar (used below)
//      resolves fine as a plain jar but only has ~1.4k classes --
//      NeoForge's own patched subset, not the full ~8k-class vanilla
//      API surface this module's renderers use.
//
// Every net.multiforge.client.* source file was nonetheless verified to
// compile cleanly against the *real* API by javac'ing it directly
// against `upstream/neoforge-1.21.1/projects/{base,neoforge}/build/classes/java/main`
// (the fork's own already-built output -- `base` carries the full
// officially-mapped + patched vanilla surface, `neoforge` layers its
// own new API on top) plus this module's ordinary external
// dependencies. That combination is what the `net.neoforged.moddev`
// plugin would effectively reproduce for a real `:compileJava` task,
// so wiring that plugin (or reproducing this classpath assembly as a
// Gradle-native configuration) is the concrete follow-up to make
// `./gradlew :multiforge-client:compileJava` pass through the ordinary
// build.
dependencies {
    // The wire-protocol types are shared with the server side, so we
    // reuse them from multiforge-runtime rather than re-declaring them.
    implementation(project(":multiforge-runtime"))
    implementation(project(":multiforge-api"))

    // Best-effort per the comment above: resolves (the artifact is real,
    // published to mavenLocal), but is NOT a complete compile classpath by
    // itself -- see the comment block above for what's still missing and
    // why. Kept as `compileOnly` + non-transitive so it can't leak a
    // half-working NeoForge classpath into anything that depends on this
    // module.
    compileOnly("net.neoforged:neoforge:1.21.1-m456-design.2-beta-develop:universal") { isTransitive = false }

    // Slf4j binding is provided by NeoForge at runtime.
    compileOnly("org.slf4j:slf4j-api:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}
