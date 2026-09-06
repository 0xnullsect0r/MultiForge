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
            "Compare two world save directories for byte-identical (or semantic) parity. " +
            "Usage: ./gradlew :multiforge-bench:determinism " +
            "--args='<baseline-world-dir> <patched-world-dir>' " +
            "[-PdiffMode=BYTE_IDENTICAL|SEMANTIC] [-Pseed=<n>]. " +
            "-PdiffMode selects WorldDiff.DiffMode (default BYTE_IDENTICAL, per Phase 7 task 7.2; " +
            "use SEMANTIC for the N-worker Phase 7.3 run). " +
            "-Pseed is provenance-only — tags the run in the log, not consumed by the diff itself."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.multiforge.bench.determinism.DeterminismHarness")

    // Property pass-through: `--args` on the command line still supplies the two
    // positional world dirs (and, for direct control, its own --mode=/--seed=
    // flags); these -P properties are the ergonomic alternative for the flags
    // documented in docs/design/m9-phase7-runbook.md §3/§7 so a Phase 7.3 run
    // doesn't need to hand-splice --args itself.
    doFirst {
        val extra = mutableListOf<String>()
        (project.findProperty("diffMode") as String?)?.let { extra += "--mode=$it" }
        (project.findProperty("seed") as String?)?.let { extra += "--seed=$it" }
        if (extra.isNotEmpty()) {
            args = args.orEmpty() + extra
        }
    }
}

// -------------------------------------------------------------------------
// Phase 7.4a bench harness — real JavaExec entries backed by
// net.multiforge.bench.harness.* (HeadlessServerRunner + RconClient +
// MetricsCollector + BenchResult + {Vanilla,Swarm,Atm10}Bench). See
// docs/design/m9-phase7-runbook.md §5 and multiforge-bench/README.md for
// the swarm profile's RCON /summon armor-stand fallback and why it isn't
// a real Minecraft protocol client.
// -------------------------------------------------------------------------

val neoforgeWorkspaceDir = rootProject.projectDir.resolve("upstream/neoforge-1.21.1")
val benchVerificationDir = rootProject.projectDir.resolve("docs/verification/m9/7.4")

fun benchTicksProperty(): String = (project.findProperty("ticks") as String?) ?: "12000"

// Phase 7.4 bench harness — ATM10 modpack profile. Takes a user-provided,
// already-prepared ATM10-formatted server dir via -PmodpackDir=<path>;
// without it, Atm10Bench prints setup instructions and exits 0 rather
// than failing the build — this task never fetches the ~500 MB pack
// itself (fragile + expensive to do inside a bench task).
tasks.register<JavaExec>("atm10") {
    group = "verification"
    description =
            "Phase 7.4 bench harness — ATM10 modpack profile. Usage: ./gradlew " +
            ":multiforge-bench:atm10 -PmodpackDir=/path/to/atm10-server [-Pticks=<n>] [-Pworkers=<n>]. " +
            "Without -PmodpackDir, prints setup instructions and exits 0 " +
            "(see docs/design/m9-phase7-runbook.md §5)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.multiforge.bench.harness.Atm10Bench")
    systemProperty("bench.ticks", benchTicksProperty())
    systemProperty("bench.workspaceDir", neoforgeWorkspaceDir.absolutePath)
    systemProperty("bench.outputFile", benchVerificationDir.resolve("atm10/patched.json").absolutePath)
    systemProperty("bench.bootLog", layout.buildDirectory.file("bench-logs/atm10-boot.log").get().asFile.absolutePath)
    (project.findProperty("modpackDir") as String?)?.let { systemProperty("bench.modpackDir", it) }
    (project.findProperty("workers") as String?)?.let { systemProperty("bench.workers", it) }
}

// Phase 7.4 bench harness — vanilla baseline profile. workers=1, no mods
// on the classpath, so the result captures the ownership-guard overhead
// on an otherwise-idle server (pass criteria: >=95% of the pre-M9
// baseline TPS — see docs/design/m9-phase7-runbook.md §5).
tasks.register<JavaExec>("vanilla") {
    group = "verification"
    description =
            "Phase 7.4 bench harness — vanilla NeoForge baseline, workers=1, no mods. " +
            "Usage: ./gradlew :multiforge-bench:vanilla [-Pticks=<n>] (default 12000 = 10 game-min)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.multiforge.bench.harness.VanillaBench")
    systemProperty("bench.ticks", benchTicksProperty())
    systemProperty("bench.workspaceDir", neoforgeWorkspaceDir.absolutePath)
    systemProperty("bench.outputFile", benchVerificationDir.resolve("vanilla/patched.json").absolutePath)
    systemProperty(
            "bench.bootLog", layout.buildDirectory.file("bench-logs/vanilla-boot.log").get().asFile.absolutePath)
}

// Phase 7.4 bench harness — headless bot swarm. Simplified fallback (see
// multiforge-bench/README.md): RCON `/summon` armor-stand bots driven by
// a real-time random-walk + particle-burst churn loop, not a real
// Minecraft protocol client. Also the carrier for Phase 7.6's strict-mode
// watchdog run: add -Dmultiforge.regiontick.strict=on via extra JVM args
// once that carrier flag is wired through.
tasks.register<JavaExec>("swarm") {
    group = "verification"
    description =
            "Phase 7.4 bench harness — headless bot swarm at a configurable player count. " +
            "Simplified fallback: RCON /summon armor-stand bots, not a real MC protocol client " +
            "(see multiforge-bench/README.md). Usage: ./gradlew :multiforge-bench:swarm " +
            "-Pplayers=<n> [-Pticks=<n>] (players default 20; ticks default 12000, " +
            "converted to a real-time run duration of ticks/20 seconds for this profile)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.multiforge.bench.harness.SwarmBench")
    val players = (project.findProperty("players") as String?) ?: "20"
    systemProperty("bench.players", players)
    systemProperty("bench.ticks", benchTicksProperty())
    systemProperty("bench.workspaceDir", neoforgeWorkspaceDir.absolutePath)
    systemProperty("bench.outputFile", benchVerificationDir.resolve("swarm-$players/patched.json").absolutePath)
    systemProperty(
            "bench.bootLog",
            layout.buildDirectory.file("bench-logs/swarm-$players-boot.log").get().asFile.absolutePath)
}
