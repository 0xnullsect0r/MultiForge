plugins {
    id("multiforge.base")
}

description = "MultiForge client debug mod — F3 overlay, chunk borders, tick-cost heatmap, pin selection."

// The client debug mod is a full NeoForge mod: it requires the MDK
// (NeoGradle plugin, mappings, etc.). It is only included in the root
// settings.gradle.kts when `-Pmc=true` is set and the NeoForge
// workspace has been vendored via `./gradlew :setup`, so this script
// can safely assume those preconditions.
//
// Until the M6 patch supplies the vendored NeoForge classpath, this
// module only compiles the pure-Java glue types that the mod's server
// half and client half share with `multiforge-runtime`. The
// NeoGradle-flavored bits live in `src/main/mod/` and are wired in
// by the M6 patch bundle.

dependencies {
    // The wire-protocol types are shared with the server side, so we
    // reuse them from multiforge-runtime rather than re-declaring them.
    implementation(project(":multiforge-runtime"))
    implementation(project(":multiforge-api"))

    // Slf4j binding is provided by NeoForge at runtime.
    compileOnly("org.slf4j:slf4j-api:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}
