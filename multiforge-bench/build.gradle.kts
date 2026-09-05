plugins {
    id("multiforge.base")
    application
}

description =
        "MultiForge benchmarking and determinism-regression harness. " +
        "Given two world save directories (baseline vs. patched), " +
        "verifies byte-identical parity per docs/blueprint.md M7 exit gate."

dependencies {
    // Phase 3 task 3.7 parity regression exercises the MultiForge MCA
    // reader/writer end-to-end. Runtime is MC-free so it can be pulled in
    // here without dragging the Minecraft classpath into the bench module.
    testImplementation(project(":multiforge-runtime"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.3")
}

application {
    mainClass.set("net.multiforge.bench.determinism.DeterminismHarness")
}

// -------------------------------------------------------------------------
// Phase 7 verification runbook targets — see docs/design/m9-phase7-runbook.md
// -------------------------------------------------------------------------
//
// The four wall-clock verification runs required to cut v0.9.0-m9:
//   * `determinism`  — 7.2 (byte-identical, 1 worker) + 7.3 (semantic, N workers)
//   * `atm10`        — 7.4 ATM10 modpack bench baseline
//   * `vanilla`      — 7.4 vanilla-server bench baseline
//   * `swarm`        — 7.4 headless-bot swarm + 7.6 strict-mode watchdog carrier
//
// The `determinism` task below is the only one wired end-to-end today: it
// compares two pre-baked world save directories. Server launch + world
// capture happen out-of-band per the runbook. The other three tasks are
// stubs so `./gradlew tasks` documents the intended shape; each fails
// with a pointer to the runbook when invoked. Implementing the bot swarm
// (Phase 7.4a) and the automated baseline capture (Phase 7.2a) are
// tracked separately in the milestone-close plan.

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

// Phase 7.4 bench harness — ATM10 modpack profile.
// Stub: real implementation launches a headless MultiForge server with
// the ATM10 modpack, drives it for N minutes, records TPS/MSPT/heap, and
// diffs against docs/verification/m9/atm10.baseline.json. Blocked on the
// bench-bot swarm implementation (Phase 7.4a) — see runbook.
tasks.register("atm10") {
    group = "verification"
    description = "Phase 7.4 bench harness — ATM10 modpack profile (STUB — see docs/design/m9-phase7-runbook.md §4)."
    doLast {
        throw GradleException(
                "TODO: implement Phase 7.4 ATM10 bench harness — see docs/design/m9-phase7-runbook.md §4.\n" +
                "Requires: modpack fetch, headless launcher, TPS/MSPT recorder, baseline JSON.")
    }
}

// Phase 7.4 bench harness — vanilla baseline profile.
// Stub: same shape as atm10 but with no mods on the classpath, so the
// baseline captures upstream NeoForge behaviour on the same hardware.
tasks.register("vanilla") {
    group = "verification"
    description = "Phase 7.4 bench harness — vanilla NeoForge baseline (STUB — see docs/design/m9-phase7-runbook.md §4)."
    doLast {
        throw GradleException(
                "TODO: implement Phase 7.4 vanilla bench harness — see docs/design/m9-phase7-runbook.md §4.\n" +
                "Same launcher/recorder as :atm10 but with no mods on the classpath.")
    }
}

// Phase 7.4 bench harness — headless bot swarm.
// Stub: real implementation spawns N headless MC clients, connects them
// to a local MultiForge server, walks each on a randomised path for N
// minutes, records TPS/MSPT. Also the carrier for Phase 7.6 strict-mode
// watchdog: `--player-count=100 -Dmultiforge.regiontick.strict=on`.
tasks.register("swarm") {
    group = "verification"
    description =
            "Phase 7.4 bench harness — headless bot swarm at 20/100/500 players " +
            "(STUB — see docs/design/m9-phase7-runbook.md §4, §5)."
    doLast {
        val players = (project.findProperty("players") as String?) ?: "20"
        throw GradleException(
                "TODO: implement Phase 7.4 headless swarm bench — see docs/design/m9-phase7-runbook.md §4.\n" +
                "Invoked with players=$players. Also carries Phase 7.6 strict-mode watchdog " +
                "(add -Dmultiforge.regiontick.strict=on).")
    }
}
