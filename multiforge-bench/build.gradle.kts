import java.time.Duration

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

    // Phase X task X.8 (docs/verification/m456/x8-strict-mode-swarm.md) is
    // the carrier the comment above anticipated: it needs the strict-mode
    // flag AND its own evidence-dir output/boot-log paths instead of the
    // M9-era swarm-$players/ defaults above. These three are the "once
    // that carrier flag is wired through" follow-up.
    (project.findProperty("extraJvmArgs") as String?)?.let { systemProperty("bench.extraJvmArgs", it) }
    (project.findProperty("outputFile") as String?)?.let { systemProperty("bench.outputFile", it) }
    (project.findProperty("bootLog") as String?)?.let { systemProperty("bench.bootLog", it) }
}

// -------------------------------------------------------------------------
// Phase X (m456) bench-verification harness — see docs/verification/m456/
// and multiforge-bench/verification/m456/*.sh. These four tasks each shell
// out to the matching script; the script (not this task) owns the actual
// server-launch/RCON/WorldDiff logic, so it can also be run directly
// outside Gradle (e.g. `bash multiforge-bench/verification/m456/x1-cross-
// region-teleport.sh --dry-run`). Every script accepts --dry-run and ends
// with a machine-parseable "PASS"/"FAIL" as its last stdout line.
//
// Real runs need a real headless-server-capable workstation (vendored
// upstream/neoforge-1.21.1 + a valid ~/.multiforge/license.key) — nothing
// CI can do — so by default these tasks pass --dry-run themselves; add
// -PrealRun to actually launch a server (see docs/verification/m456/
// README.md "How to run" for the full prerequisites and expected wall
// clock per task).
// -------------------------------------------------------------------------

val m456VerificationDir = project.projectDir.resolve("verification/m456")

fun registerM456VerificationTask(taskName: String, scriptName: String, timeoutMinutes: Long, taskDescription: String) {
    tasks.register<Exec>(taskName) {
        group = "verification"
        description = taskDescription
        workingDir = rootProject.projectDir
        val scriptArgs = mutableListOf("bash", m456VerificationDir.resolve(scriptName).absolutePath)
        if (!project.hasProperty("realRun")) {
            scriptArgs += "--dry-run"
        }
        commandLine(scriptArgs)
        timeout.set(Duration.ofMinutes(timeoutMinutes))
    }
}

registerM456VerificationTask(
        "x1CrossRegionTeleport",
        "x1-cross-region-teleport.sh",
        15,
        "Phase X.1 — cross-region entity teleport regression: 100 rapid /tp calls across 4 regions, " +
                "SEMANTIC world-save parity vs a 1-worker baseline of the same seed. " +
                "Usage: ./gradlew :multiforge-bench:x1CrossRegionTeleport [-PrealRun].")

registerM456VerificationTask(
        "x2RaidStress",
        "x2-raid-stress.sh",
        15,
        "Phase X.2 — cross-region raid stress: 20 raid-capture seeds spanning 4 region-boundary " +
                "quadrants; asserts zero raider-spawn-routing failures and zero ownership violations. " +
                "Usage: ./gradlew :multiforge-bench:x2RaidStress [-PrealRun].")

registerM456VerificationTask(
        "x3DragonFight",
        "x3-dragon-fight-regression.sh",
        15,
        "Phase X.3 — dragon fight regression: fixed seed, forced kill cycle, end-podium/gateway " +
                "SEMANTIC parity vs a 1-worker baseline. " +
                "Usage: ./gradlew :multiforge-bench:x3DragonFight [-PrealRun].")

registerM456VerificationTask(
        "x8StrictSwarm",
        "x8-strict-mode-swarm.sh",
        65,
        "Phase X.8 — 60-minute strict-mode headless swarm at 100 bots (-Dmultiforge.regiontick.strict=on); " +
                "asserts zero RegionTickOverrunException and zero OwnershipEnforcer REROUTE hits. " +
                "Usage: ./gradlew :multiforge-bench:x8StrictSwarm [-PrealRun].")
