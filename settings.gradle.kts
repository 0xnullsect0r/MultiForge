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
include(
    "multiforge-license",
    "multiforge-license-cli",
    "multiforge-api",
    "multiforge-runtime",
)

// Modules that require a vendored NeoForge workspace at
// `upstream/neoforge-1.21.1/`. Included only when `-Pmc=true` is passed,
// so `./gradlew build` works standalone before `:setup` has run.
val mcEnabled = providers.gradleProperty("mc").getOrElse("false").toBoolean()
if (mcEnabled) {
    include(
        "multiforge-patches",
        "multiforge-installer",
        "multiforge-client",
        "multiforge-testmods",
        "multiforge-bench",
    )
}
