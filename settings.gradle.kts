pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // v1.3.5: `net.neoforged.moddev` plugin lives here.
        maven("https://maven.neoforged.net/releases") {
            name = "NeoForge"
        }
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS (softened from FAIL_ON_PROJECT_REPOS in v1.3.5) —
    // net.neoforged.moddev's RepositoriesPlugin adds "Mojang Minecraft
    // Libraries" at project scope during application; FAIL_ON_PROJECT_REPOS
    // treats that as an error. PREFER_SETTINGS lets moddev add its
    // required repos (with a warning) while still preferring
    // settings-declared repos everywhere else.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases") {
            name = "NeoForge"
        }
        // v1.3.5: the vendored fork's mavenLocal-published artifacts
        // (multiforge-runtime, multiforge-api) become visible outer-side
        // so :multiforge-client can resolve its runtime dep without
        // needing to re-publish through Maven Central.
        mavenLocal()
    }
}

rootProject.name = "multiforge"

// Modules with no Minecraft dependency — always buildable.
// multiforge-client currently ships the pure-Java wire glue + HUD state
// model; the NeoForge-flavored bits are added by the M6 patch bundle
// under `-Pmc=true`.
include(
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
