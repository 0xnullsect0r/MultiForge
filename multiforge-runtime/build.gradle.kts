plugins {
    id("multiforge.base")
}

description = "MultiForge runtime — region manager, schedulers, diagnostics."

dependencies {
    implementation(project(":multiforge-api"))
    implementation(project(":multiforge-license"))

    // Slf4j API only. NeoForge already ships the logger binding at runtime.
    implementation("org.slf4j:slf4j-api:2.0.13")

    // JetBrains annotations for @ApiStatus.Internal etc.
    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation("org.awaitility:awaitility:4.2.1")
}

// Templated version file baked into the runtime jar.
tasks.processResources {
    val tokens = mapOf(
        "version" to project.version.toString(),
        "buildTime" to System.currentTimeMillis().toString(),
    )
    inputs.properties(tokens)
    filesMatching("multiforge-runtime.properties.in") {
        expand(tokens)
        rename { "multiforge-runtime.properties" }
    }
}
