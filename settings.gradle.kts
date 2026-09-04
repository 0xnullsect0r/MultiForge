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
)

// Modules that require a vendored NeoForge workspace at
// `upstream/neoforge-1.21.1/`. Included only when `-Pmc=true` is passed,
// so `./gradlew build` works standalone before `:setup` has run.
val mcEnabled = providers.gradleProperty("mc").getOrElse("false").toBoolean()
if (mcEnabled) {
    include(
        "multiforge-patches",
        "multiforge-installer",
        "multiforge-testmods",
        "multiforge-bench",
    )
}
