plugins {
    id("multiforge.base")
    application
}

description =
        "MultiForge benchmarking and determinism-regression harness. " +
        "Given two world save directories (baseline vs. patched), " +
        "verifies byte-identical parity per docs/blueprint.md M7 exit gate."

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.3")
}

application {
    mainClass.set("net.multiforge.bench.determinism.DeterminismHarness")
}

// Wire the `:multiforge-bench:determinism` task name that CLAUDE.md
// references. It just runs the harness's main entry point; harness
// inputs (paths to the two world directories) come from arguments.
tasks.register<JavaExec>("determinism") {
    group = "verification"
    description =
            "Compare two world save directories for byte-identical parity. " +
            "Usage: ./gradlew :multiforge-bench:determinism " +
            "--args='<baseline-world-dir> <patched-world-dir>'"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.multiforge.bench.determinism.DeterminismHarness")
}
