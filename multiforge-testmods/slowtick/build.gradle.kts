plugins {
    id("multiforge.base")
    id("net.neoforged.moddev") version "2.0.78"
}

description = "MultiForge fixture mod mftest_slowtick — see multiforge-testmods/README.md."

neoForge {
    version = providers.gradleProperty("neoForgeVersion").get()
}

tasks.processResources {
    val tokens = mapOf("version" to project.version.toString())
    inputs.properties(tokens)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(tokens)
    }
}

tasks.jar {
    archiveBaseName.set("mftest-slowtick")
}
