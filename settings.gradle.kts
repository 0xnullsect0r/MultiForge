pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases") {
            name = "NeoForge"
        }
    }
}

rootProject.name = "multiforge"

// Modules with no Minecraft dependency — always buildable.
// multiforge-client currently ships the pure-Java wire glue + HUD state
// model; the NeoForge-flavored bits are added by the M6 patch bundle
// under `-Pmc=true`.
include(
    "multiforge-license",
    "multiforge-license-cli",
    "multiforge-api",
    "multiforge-runtime",
    "multiforge-client",
    "multiforge-installer",
    // Determinism harness: pure-Java file diffing, no Minecraft dep — buildable
    // standalone. Only the world dirs it consumes come from an MC-enabled run.
    "multiforge-bench",
    // Static ASM-based mod-jar safety scanner. No Minecraft/NeoForge dep —
    // see docs/design/scanner-rules.md §1.1.
    "multiforge-scanner",
)

// MultiForge patches under `multiforge-patches/<NN-group>/` are applied
// against the vendored NeoForge workspace at `upstream/neoforge-1.21.1/`
// by the `applyMultiforgePatches` Gradle task inside that separate
// build — see `upstream/neoforge-1.21.1/projects/neoforge/multiforge-patches.gradle`.
// The MultiForge fixtures that exercise those patches (an off-thread
// GameTest for M7 today) live under `upstream/neoforge-1.21.1/tests/`
// (see net/multiforge/testfixtures/) and are picked up by NeoForge's
// own tests project — no outer-repo module needed.
//
// The `-Pmc` gradle property is no longer meaningful here; kept only for
// legacy scripts that may still pass it.
