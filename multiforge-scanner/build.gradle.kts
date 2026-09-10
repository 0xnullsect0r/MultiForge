plugins {
    id("multiforge.base")
    application
}

description = "MultiForge mod-safety scanner — static ASM-based bytecode analysis for known unsafe patterns."

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-tree:9.7")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

application {
    mainClass.set("net.multiforge.scanner.Main")
}

// The scanner is invoked as `java -jar multiforge-scanner-<v>.jar` — by
// .github/workflows/scanner.yml, and by the local command that workflow's
// own comment names as authoritative. That needs two things the bare
// `application` plugin does not give a plain jar:
//
//   1. A Main-Class manifest attribute. Without it every invocation is
//      `no main manifest attribute, in multiforge-scanner-<v>.jar`,
//      exit 1 — which the workflow reads as an ERROR-severity finding.
//      The mod-safety check therefore failed on every release from
//      v1.3.17 through v1.5.0 whenever upstream/ changed, always for
//      this reason and never because the scanner found anything.
//   2. The ASM classes on the classpath. `java -jar` ignores -cp, so a
//      manifest alone would just move the failure to NoClassDefFoundError
//      on org.objectweb.asm. Hence the dependency merge below.
//
// ASM is BSD-3-Clause, which is GPL-3 compatible (CLAUDE.md ground rule 1),
// so bundling it is fine. It is also the only runtime dependency, so the
// jar stays small.
tasks.jar {
    manifest {
        attributes(
                "Main-Class" to "net.multiforge.scanner.Main",
                "Implementation-Title" to "MultiForge Scanner",
                "Implementation-Version" to project.version,
        )
    }
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } })
    // Signatures from the dependency jars do not cover the merged
    // contents and make the JVM reject the jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
