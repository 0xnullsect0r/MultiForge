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

dependencies {
    // Wire-protocol types shared with the server side.
    implementation(project(":multiforge-runtime"))
    implementation(project(":multiforge-api"))

    // NOTE: SLF4J is transitively provided by moddev's neoForge{} block
    // (net.neoforged:minecraft-dependencies pins it strictly to 2.0.9);
    // declaring a compileOnly on 2.0.13 here caused an unresolvable
    // version-strictly conflict at compile time in v1.3.5.

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}
