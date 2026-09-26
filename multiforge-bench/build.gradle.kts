import java.time.Duration

plugins {
    id("multiforge.base")
    application
}

description =
        "MultiForge bench and vanilla-parity harness: boots installed servers (MultiForge or stock " +
        "NeoForge), drives them over RCON and real protocol bots, and diffs world saves."

dependencies {
    // LZ4 chunk payloads (server.properties region-file-compression=lz4) in
    // WorldDiff. Same library and version Minecraft 1.21.1 ships; Apache-2.0.
    implementation("org.lz4:lz4-java:1.8.0")
    // SwarmBench protocol bots. MCProtocolLib is MIT; its tree is Apache-2.0
    // (netty, cloudburst nbt/math, nukkitx fastutil), MIT (adventure, gson is
    // Apache-2.0, checker-qual) and LGPL-3.0 (MinecraftAuth, unused offline).
    // Bench-only: none of it ships in the server or the installer. There is no
    // 1.21.1 release; 1.21 and 1.21.1 share protocol 767, so pin the last
    // 1.21 snapshot build by its immutable timestamped version.
    implementation("org.geysermc.mcprotocollib:protocol:1.21-20241010.155958-24")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.20")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.3")
}

application {
    mainClass.set("net.multiforge.bench.harness.DeterminismRun")
}

// `:multiforge-bench:worldDiff` compares two world save directories
// (BYTE_IDENTICAL or SEMANTIC) — the offline half, for captures made by hand.
tasks.register<JavaExec>("worldDiff") {
    group = "verification"
    description =
            "Compare two world save directories. Usage: ./gradlew :multiforge-bench:worldDiff " +
            "--args='<baseline-world-dir> <patched-world-dir>' [-PdiffMode=BYTE_IDENTICAL|SEMANTIC]."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.multiforge.bench.determinism.DeterminismHarness")
    doFirst {
        (project.findProperty("diffMode") as String?)?.let { args = args.orEmpty() + "--mode=$it" }
    }
}

// -------------------------------------------------------------------------
// Bench profiles — net.multiforge.bench.harness.*. Each boots an installed
// server (BenchSetup): by default the MultiForge installer that
// `./gradlew :neoforge:installerJar` leaves in upstream/neoforge-1.21.1/
// projects/neoforge/build/libs, installed once into build/server/multiforge;
// -Pserver=stock runs the plain NeoForge installer of the same version
// instead (the baseline). Timing is /multiforge tickstats over the measured
// window (true max MSPT, 10-minute TPS count); see MetricsCollector.
//
//   vanilla  no mods, /tick sprint, -Pticks (default 12000), -Pworkers (1)
//   atm10    a modpack: -PmodpackDir=<unpacked> or -PmodpackUrl=<zip> -PmodpackSha256=<hex>
//   swarm    -Pplayers real protocol bots (MCProtocolLib), real time for ticks/20 s;
//            -PswarmMode=armor-stand for the RCON armor-stand fallback
//
// Common: -Pserver=multiforge|stock, -Pinstaller=<jar>, -PextraJvmArgs="...",
// -PoutputFile=<json>, -PbootLog=<log>.
// -------------------------------------------------------------------------

val benchResultsDir = layout.buildDirectory.dir("bench-results").get().asFile

fun JavaExec.benchCommon(profile: String) {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    systemProperty("bench.ticks", (project.findProperty("ticks") as String?) ?: "12000")
    systemProperty("bench.serverRoot", layout.buildDirectory.dir("server").get().asFile.absolutePath)
    systemProperty("bench.cacheDir", layout.buildDirectory.get().asFile.absolutePath)
    systemProperty(
            "bench.forkLibs",
            rootProject.projectDir.resolve("upstream/neoforge-1.21.1/projects/neoforge/build/libs").absolutePath)
    systemProperty(
            "bench.neoforgeVersion", rootProject.findProperty("neoForgeVersion") as String? ?: "21.1.251")
    systemProperty(
            "bench.bootLog",
            (project.findProperty("bootLog") as String?)
                    ?: layout.buildDirectory.file("bench-logs/$profile-boot.log").get().asFile.absolutePath)
    val passThrough = mapOf(
            "server" to "bench.server",
            "installer" to "bench.installer",
            "stockInstaller" to "bench.stockInstaller",
            "workers" to "bench.workers",
            "extraJvmArgs" to "bench.extraJvmArgs",
            "modpackDir" to "bench.modpackDir",
            "modpackUrl" to "bench.modpackUrl",
            "modpackSha256" to "bench.modpackSha256",
            "outputFile" to "bench.outputFile")
    passThrough.forEach { (prop, sys) -> (project.findProperty(prop) as String?)?.let { systemProperty(sys, it) } }
}

// The vanilla-parity gate (CLAUDE.md): one frozen fixed-seed world (four
// forceloaded squares far enough apart to be separate regions), ticked on
// stock NeoForge and on MultiForge (-Pworkers, comma-separated, default 1);
// the terrain of every full chunk in the squares must match. Needs
// both installers (-PstockInstaller or network access to maven.neoforged.net).
tasks.register<JavaExec>("determinism") {
    description = "Vanilla-parity gate: tick one seed world on stock NeoForge and MultiForge, compare terrain. " +
            "[-Pworkers=1[,4,...]] [-Pticks=1200] [-Pradius=3] [-Pspacing=48] [-Pseed=1234567890] [-Pregenerate]."
    benchCommon("determinism")
    mainClass.set("net.multiforge.bench.harness.DeterminismRun")
    if (project.findProperty("ticks") == null) systemProperty("bench.ticks", "1200")
    listOf("radius", "spacing", "seed").forEach { prop ->
        (project.findProperty(prop) as String?)?.let { systemProperty("bench.$prop", it) }
    }
    if (project.hasProperty("regenerate")) systemProperty("bench.regenerate", "true")
}

// Phase X behaviour checks (ScenarioRun): each scenario on stock NeoForge and
// on MultiForge from the same seed; observations must match.
tasks.register<JavaExec>("scenario") {
    description = "Phase X scenarios on stock vs MultiForge: -Pscenario=x1|x2|x3|x4|all (default all) [-Pworkers=4]."
    benchCommon("scenario")
    // x4 runs with the fixture mods in mods/.
    val testmodJars = listOf(":multiforge-testmods:writer", ":multiforge-testmods:legacy")
            .map { project(it).tasks.named<Jar>("jar") }
    dependsOn(testmodJars)
    doFirst {
        systemProperty(
                "bench.testmods",
                testmodJars.joinToString(",") { it.get().archiveFile.get().asFile.absolutePath })
    }
    mainClass.set("net.multiforge.bench.harness.ScenarioRun")
    providers.gradleProperty("scenario").orNull?.let { systemProperty("bench.scenario", it) }
    (project.findProperty("seed") as String?)?.let { systemProperty("bench.seed", it) }
}

// Relay stdin lines to a booted server over RCON (see ServerConsole).
tasks.register<JavaExec>("console") {
    description = "Boot an installed server and relay stdin commands over RCON. [-Pserver] [-Pworkers] [-PworldSource=<dir>]."
    benchCommon("console")
    mainClass.set("net.multiforge.bench.harness.ServerConsole")
    standardInput = System.`in`
    (project.findProperty("worldSource") as String?)?.let { systemProperty("bench.worldSource", it) }
}

tasks.register<JavaExec>("atm10") {
    description = "Modpack MSPT profile. -PmodpackDir=<dir> or -PmodpackUrl=<zip> -PmodpackSha256=<hex>; " +
            "[-Pticks] [-Pworkers] [-Pserver=stock]. Exits 2 when no pack is given."
    benchCommon("atm10")
    mainClass.set("net.multiforge.bench.harness.Atm10Bench")
}

tasks.register<JavaExec>("vanilla") {
    description = "No-mods MSPT profile via /tick sprint. [-Pticks=12000] [-Pworkers=1] [-Pserver=stock]."
    benchCommon("vanilla")
    mainClass.set("net.multiforge.bench.harness.VanillaBench")
}

tasks.register<JavaExec>("swarm") {
    description = "Real-time player swarm: -Pplayers protocol bots walk, place and break for ticks/20 s. " +
            "[-Pplayers=20] [-Pticks=12000] [-Pworkers] [-Pspread=512] [-PrenderDistance=8] " +
            "[-PswarmMode=bots|armor-stand] [-PmodpackDir=<dir>] [-Pserver=stock]."
    val players = (project.findProperty("players") as String?) ?: "20"
    benchCommon("swarm-$players")
    mainClass.set("net.multiforge.bench.harness.SwarmBench")
    systemProperty("bench.players", players)
    if (project.findProperty("outputFile") == null) {
        val server = (project.findProperty("server") as String?) ?: "multiforge"
        systemProperty("bench.outputFile", benchResultsDir.resolve("swarm-$players-$server.json").absolutePath)
    }
    listOf("spread", "renderDistance", "swarmMode").forEach { prop ->
        (project.findProperty(prop) as String?)?.let { systemProperty("bench.$prop", it) }
    }
}

// Phase X.8: the strict-mode swarm — 100 protocol bots for 60 minutes with
// -Dmultiforge.mode=strict; fails on any ownership violation or region
// overrun. A preset of `swarm`; override -Pplayers / -Pticks for a shorter run.
tasks.register<JavaExec>("x8StrictSwarm") {
    description = "X.8 strict-mode swarm: [-Pplayers=100] [-Pticks=72000] (60 min), fails on violations/overruns."
    val players = (project.findProperty("players") as String?) ?: "100"
    benchCommon("x8-swarm-$players")
    mainClass.set("net.multiforge.bench.harness.SwarmBench")
    systemProperty("bench.players", players)
    systemProperty("bench.ticks", (project.findProperty("ticks") as String?) ?: "72000")
    systemProperty("bench.extraJvmArgs", "-Dmultiforge.mode=strict " + ((project.findProperty("extraJvmArgs") as String?) ?: ""))
    systemProperty("bench.failOnViolations", "true")
    if (project.findProperty("outputFile") == null) {
        systemProperty(
                "bench.outputFile",
                benchResultsDir.resolve("x8-swarm-$players.json").absolutePath)
    }
    listOf("spread", "renderDistance").forEach { prop ->
        (project.findProperty(prop) as String?)?.let { systemProperty("bench.$prop", it) }
    }
}
