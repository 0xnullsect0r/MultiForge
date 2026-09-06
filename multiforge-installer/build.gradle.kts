plugins {
    id("multiforge.base")
    application
}

description = "MultiForge installer — self-contained runnable jar that lays out a fresh server dir or builds the drop-in replacement zip."

dependencies {
    // We embed the runtime as a bundled resource so the installer is a
    // single self-sufficient artifact operators can run without any
    // separate download.
    implementation(project(":multiforge-runtime"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.3")
}

application {
    mainClass.set("net.multiforge.installer.Main")
}

// Copy the runtime jar into a resource path the installer reads with
// getResourceAsStream. Rename to a version-free filename so Main.java
// can look it up by a stable name.
val bundleJars =
        tasks.register<Copy>("bundleJars") {
            dependsOn(":multiforge-runtime:jar")
            from(project(":multiforge-runtime").tasks.named("jar")) { rename { "multiforge-runtime.jar" } }
            into(layout.buildDirectory.dir("bundled-jars/net/multiforge/installer/bundle"))
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("bundled-jars"))
}

tasks.processResources { dependsOn(bundleJars) }

// The sourcesJar task (added by the `multiforge.base` convention plugin)
// also scans main resources; declaring the dependency here keeps Gradle
// from complaining about implicit input.
tasks.matching { it.name == "sourcesJar" }.configureEach { dependsOn(bundleJars) }

// Make the installer jar itself runnable (java -jar) — the application
// plugin's start scripts also work, but a single-file .jar is what
// operators actually download from a GitHub Release.
tasks.jar {
    dependsOn(bundleJars)
    manifest {
        attributes(
                "Main-Class" to "net.multiforge.installer.Main",
                "Implementation-Title" to "MultiForge Installer",
                "Implementation-Version" to project.version,
        )
    }
    // The bundled runtime jar is already picked up via
    // sourceSets.main.resources.srcDir(bundled-jars) above; no explicit
    // from() here to avoid double-includes.
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
