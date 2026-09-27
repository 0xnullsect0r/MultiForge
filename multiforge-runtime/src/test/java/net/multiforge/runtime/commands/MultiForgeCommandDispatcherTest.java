/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.runtime.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.config.MultiForgeConfigStore;
import net.multiforge.runtime.diagnostics.ChunkCost;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.pin.RegionPinManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiForgeCommandDispatcherTest {

    @AfterEach
    void resetViolationLogger() {
        ViolationLogger.resetForTesting();
    }

    private MultiForgeCommandDispatcher make(Path tmp) throws IOException {
        MultiForgeConfigStore store = new MultiForgeConfigStore(tmp.resolve("mf.toml"), MultiForgeConfig.defaults());
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.toml"));
        return new MultiForgeCommandDispatcher(store, pins);
    }

    private MultiForgeCommandDispatcher makeWithScanner(
            Path tmp, Path modsDir, MultiForgeCommandDispatcher.ScannerRunner scannerRunner) throws IOException {
        MultiForgeConfigStore store = new MultiForgeConfigStore(tmp.resolve("mf.toml"), MultiForgeConfig.defaults());
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.toml"));
        return new MultiForgeCommandDispatcher(store, pins, null, modsDir, scannerRunner);
    }

    @Test
    void configCoresUpdatesConfig(@TempDir Path tmp) throws IOException {
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"config", "cores", "12"}, out::add)).isTrue();
        assertThat(out.get(0)).contains("cores = 12");
    }

    @Test
    void configModePolicyAndWarnRateAreStoredAndNotifySubscribers(@TempDir Path tmp) throws IOException {
        MultiForgeConfigStore store = new MultiForgeConfigStore(tmp.resolve("mf.toml"), MultiForgeConfig.defaults());
        MultiForgeCommandDispatcher d =
                new MultiForgeCommandDispatcher(store, new RegionPinManager(tmp.resolve("pins.toml")));
        List<MultiForgeConfig> seen = new ArrayList<>();
        store.subscribe(seen::add);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"config", "mode", "strict"}, out::add))
                .isTrue();
        assertThat(d.dispatch(new String[] {"config", "policy", "reroute-only"}, out::add))
                .isTrue();
        assertThat(d.dispatch(new String[] {"config", "warnPerMin", "0"}, out::add))
                .isTrue();
        assertThat(store.get().mode()).isEqualTo(MultiForgeConfig.Mode.STRICT);
        assertThat(store.get().violationPolicy()).isEqualTo(MultiForgeConfig.ViolationPolicy.REROUTE_ONLY);
        assertThat(store.get().warnPerMin()).isZero();
        assertThat(seen).hasSize(4); // initial snapshot + three updates
        assertThat(out).anyMatch(l -> l.contains("Mode strict is active"));
        assertThat(java.nio.file.Files.readString(tmp.resolve("mf.toml"))).contains("mode = \"strict\"");

        out.clear();
        assertThat(d.dispatch(new String[] {"config", "mode", "off"}, out::add)).isTrue();
        assertThat(out).anyMatch(l -> l.contains("next server start"));
        out.clear();
        assertThat(d.dispatch(new String[] {"config", "policy", "bogus"}, out::add))
                .isFalse();
        assertThat(out).anyMatch(l -> l.contains("reroute-only"));
    }

    @Test
    void configReloadRereadsTheFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("mf.toml");
        MultiForgeConfigStore store = new MultiForgeConfigStore(file, MultiForgeConfig.defaults());
        MultiForgeCommandDispatcher d =
                new MultiForgeCommandDispatcher(store, new RegionPinManager(tmp.resolve("pins.toml")));
        java.nio.file.Files.writeString(file, "[mtserver]\ncores = 3\nthreadsPerCore = 2\n");
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"config", "reload"}, out::add)).isTrue();
        assertThat(store.get().tickWorkerCount()).isEqualTo(6);
        out.clear();
        assertThat(d.dispatch(new String[] {"config", "show"}, out::add)).isTrue();
        assertThat(out.get(0)).contains("cores = 3").contains("threadsPerCore = 2");
    }

    @Test
    void regionSizePowerOfTwoOnly(@TempDir Path tmp) throws IOException {
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"region", "size", "6"}, out::add)).isFalse();
        out.clear();
        assertThat(d.dispatch(new String[] {"region", "size", "16"}, out::add)).isTrue();
    }

    @Test
    void regionSizeWithoutArgumentShowsTheCurrentSize(@TempDir Path tmp) throws IOException {
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"region", "size"}, out::add)).isTrue();
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).startsWith("Region size is 16 chunks per side (shift=4, 256 blocks)");
    }

    @Test
    void largeRegionSizeIsAllowedButWarned(@TempDir Path tmp) throws IOException {
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"region", "size", "128"}, out::add)).isTrue();
        assertThat(out).anyMatch(l -> l.startsWith("Region size set to 128"));
        assertThat(out).anyMatch(l -> l.startsWith("Warning: 128-chunk sections are large"));
        out.clear();
        d.dispatch(new String[] {"region", "size"}, out::add);
        assertThat(out.get(0)).contains("128 chunks per side (shift=7");
        assertThat(out).anyMatch(l -> l.startsWith("Warning:"));
        out.clear();
        d.dispatch(new String[] {"region", "size", "32"}, out::add);
        assertThat(out).noneMatch(l -> l.startsWith("Warning:"));
    }

    @Test
    void regionCostIsShownAsP50AndP95() {
        net.multiforge.runtime.region.RegionMspt mspt = new net.multiforge.runtime.region.RegionMspt(100);
        assertThat(MultiForgeCommandDispatcher.describeCost(mspt)).isEmpty();
        for (int i = 1; i <= 100; i++) mspt.recordNanos(i * 100_000L); // 0.1 .. 10 ms
        assertThat(MultiForgeCommandDispatcher.describeCost(mspt)).isEqualTo(", tick 5.1/9.6 ms (p50/p95, last 5 s)");
        assertThat(MultiForgeCommandDispatcher.describeCost(null)).isEmpty();
    }

    @Test
    void pinListRoundTrip(@TempDir Path tmp) throws IOException {
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(
                        new String[] {"region", "pin", "base", "minecraft:overworld", "-2", "-2", "2", "2"}, out::add))
                .isTrue();
        out.clear();
        d.dispatch(new String[] {"region", "list"}, out::add);
        assertThat(out).anyMatch(l -> l.contains("base"));

        out.clear();
        assertThat(d.dispatch(new String[] {"region", "unpin", "base"}, out::add))
                .isTrue();
        out.clear();
        d.dispatch(new String[] {"region", "list"}, out::add);
        assertThat(out).contains("No pinned regions.");
    }

    @Test
    void unknownSubcommandFails(@TempDir Path tmp) throws IOException {
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"nonsense"}, out::add)).isFalse();
    }

    // /multiforge probes — exposes ProbeRegistry counters to operators.
    @Test
    void probesEmptySnapshotReportsNone(@TempDir Path tmp) throws IOException {
        net.multiforge.runtime.diagnostics.ProbeRegistry.resetForTesting();
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"probes"}, out::add)).isTrue();
        assertThat(out).contains("(no probes recorded)");
    }

    @Test
    void probesDumpsAllCountersSorted(@TempDir Path tmp) throws IOException {
        net.multiforge.runtime.diagnostics.ProbeRegistry.resetForTesting();
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump("zeta.thing");
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump("alpha.thing");
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump("alpha.thing");

        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"probes"}, out::add)).isTrue();
        // Alphabetical order because ProbeRegistry.snapshot returns a sorted TreeMap.
        assertThat(out).containsExactly("alpha.thing = 2", "zeta.thing = 1");
    }

    @Test
    void probesPrefixFiltersMatching(@TempDir Path tmp) throws IOException {
        net.multiforge.runtime.diagnostics.ProbeRegistry.resetForTesting();
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump("region-tick.overrun");
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump("Level.setBlock:off-thread");
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump("Level.setBlock:off-thread");

        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"probes", "region-tick"}, out::add)).isTrue();
        assertThat(out).containsExactly("region-tick.overrun = 1");
    }

    @Test
    void probesPrefixNoMatchReportsMissing(@TempDir Path tmp) throws IOException {
        net.multiforge.runtime.diagnostics.ProbeRegistry.resetForTesting();
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump("region-tick.overrun");

        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"probes", "nomatch"}, out::add)).isTrue();
        assertThat(out).anyMatch(l -> l.contains("no probes matching prefix 'nomatch'"));
    }

    @Test
    void probesTopListsTheLargestCountersFirst(@TempDir Path tmp) throws IOException {
        net.multiforge.runtime.diagnostics.ProbeRegistry.resetForTesting();
        net.multiforge.runtime.diagnostics.ProbeRegistry.add("event.dispatch.serial.event.A", 5);
        net.multiforge.runtime.diagnostics.ProbeRegistry.add("event.dispatch.serial.event.B", 50);
        net.multiforge.runtime.diagnostics.ProbeRegistry.add("event.dispatch.serial.event.C", 20);
        net.multiforge.runtime.diagnostics.ProbeRegistry.add("region-tick.overrun", 999);

        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        // As the command binder passes it: the rest of the line in one argument.
        assertThat(d.dispatch(new String[] {"probes", "top event.dispatch.serial 2"}, out::add))
                .isTrue();
        assertThat(out).containsExactly("event.dispatch.serial.event.B = 50", "event.dispatch.serial.event.C = 20");
    }

    // /multiforge chunks — loaded chunks per region, from the world's ChunkHolderManager.
    @Test
    void chunksNotInstalledReportsFailure(@TempDir Path tmp) throws IOException {
        // The 2-arg constructor leaves chunkManagers null (no runtime).
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"chunks", "minecraft:overworld"}, out::add))
                .isFalse();
        assertThat(out).anyMatch(l -> l.contains("runtime not installed"));
    }

    @Test
    void chunksMissingWorldArgFails(@TempDir Path tmp) throws IOException {
        java.util.Map<net.multiforge.api.world.WorldRef, net.multiforge.runtime.chunk.ChunkHolderManager> map =
                new java.util.HashMap<>();
        MultiForgeCommandDispatcher d = makeWithChunks(tmp, map::get);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"chunks"}, out::add)).isFalse();
        assertThat(out).anyMatch(l -> l.contains("Usage: /multiforge chunks"));
    }

    @Test
    void chunksReportsLoadedChunksPerRegion(@TempDir Path tmp) throws IOException {
        net.multiforge.api.world.WorldRef world = net.multiforge.api.world.WorldRef.of("minecraft:overworld");
        net.multiforge.runtime.chunk.ChunkHolderManager manager =
                new net.multiforge.runtime.chunk.ChunkHolderManager(world);
        net.multiforge.runtime.region.RegionId a = net.multiforge.runtime.region.RegionId.next();
        net.multiforge.runtime.region.RegionId b = net.multiforge.runtime.region.RegionId.next();
        manager.createHolder(new net.multiforge.api.world.ChunkPos(0, 0), a);
        manager.createHolder(new net.multiforge.api.world.ChunkPos(1, 0), a);
        manager.createHolder(new net.multiforge.api.world.ChunkPos(2, 0), a);
        manager.createHolder(new net.multiforge.api.world.ChunkPos(90, 0), b);

        java.util.Map<net.multiforge.api.world.WorldRef, net.multiforge.runtime.chunk.ChunkHolderManager> map =
                new java.util.HashMap<>();
        map.put(world, manager);
        MultiForgeCommandDispatcher d = makeWithChunks(tmp, map::get);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"chunks", "minecraft:overworld"}, out::add))
                .isTrue();
        assertThat(out).anyMatch(l -> l.contains("loaded chunks=4") && l.contains("regions=2"));
        assertThat(out).anyMatch(l -> l.contains("region " + a) && l.contains(": 3 chunk(s)"));
        assertThat(out).anyMatch(l -> l.contains("region " + b) && l.contains(": 1 chunk(s)"));
    }

    @Test
    void chunksUnknownWorldReportsNone(@TempDir Path tmp) throws IOException {
        java.util.Map<net.multiforge.api.world.WorldRef, net.multiforge.runtime.chunk.ChunkHolderManager> map =
                new java.util.HashMap<>();
        MultiForgeCommandDispatcher d = makeWithChunks(tmp, map::get);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"chunks", "minecraft:bogus"}, out::add))
                .isTrue();
        assertThat(out).anyMatch(l -> l.contains("No chunks of"));
    }

    // /multiforge warn — operator view over ViolationLogger's recent-violation history.
    @Test
    void warnListEmptyReportsNone(@TempDir Path tmp) throws IOException {
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"warn", "list"}, out::add)).isTrue();
        assertThat(out).containsExactly("(no recent violations)");
    }

    @Test
    void warnListShowsFiredViolations(@TempDir Path tmp) throws IOException {
        ViolationLogger.warn("examplemod", "Level.setBlock:off-thread", "called from wrong thread");
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"warn", "list"}, out::add)).isTrue();
        assertThat(out).anyMatch(l -> l.contains("examplemod") && l.contains("Level.setBlock:off-thread"));
    }

    @Test
    void warnClearEmptiesHistory(@TempDir Path tmp) throws IOException {
        ViolationLogger.warn("examplemod", "some-site", "detail");
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"warn", "clear"}, out::add)).isTrue();
        assertThat(out).anyMatch(l -> l.contains("Cleared 1"));

        out.clear();
        assertThat(d.dispatch(new String[] {"warn", "list"}, out::add)).isTrue();
        assertThat(out).containsExactly("(no recent violations)");
    }

    @Test
    void warnUnknownSubcommandFails(@TempDir Path tmp) throws IOException {
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"warn", "nonsense"}, out::add)).isFalse();
    }

    // /multiforge certify — thin wrapper over the scanner CLI (ScannerRunner injected for tests).
    @Test
    void certifyMissingArgFails(@TempDir Path tmp) throws IOException {
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"certify"}, out::add)).isFalse();
    }

    @Test
    void certifyNoModsDirReportsNone(@TempDir Path tmp) throws IOException {
        MultiForgeCommandDispatcher d = makeWithScanner(
                tmp, tmp.resolve("mods"), jar -> new MultiForgeCommandDispatcher.ScanResult(0, "{}", ""));
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"certify", "all"}, out::add)).isFalse();
        assertThat(out).anyMatch(l -> l.contains("No mod jars found"));
    }

    @Test
    void certifyAllPassesWhenScannerFindsNothing(@TempDir Path tmp) throws IOException {
        Path modsDir = tmp.resolve("mods");
        Files.createDirectories(modsDir);
        Files.createFile(modsDir.resolve("examplemod-1.0.0.jar"));

        MultiForgeCommandDispatcher d = makeWithScanner(
                tmp,
                modsDir,
                jar -> new MultiForgeCommandDispatcher.ScanResult(
                        0, "{\"summary\":{\"errors\":0,\"warnings\":0},\"findings\":[]}", ""));
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"certify", "examplemod"}, out::add)).isTrue();
        assertThat(out).anyMatch(l -> l.contains("R01: PASS"));
        assertThat(out).anyMatch(l -> l.contains("CERTIFIED: examplemod-1.0.0.jar"));
        assertThat(out).noneMatch(l -> l.startsWith("NOT CERTIFIED"));
    }

    @Test
    void certifyFailsOnErrorFinding(@TempDir Path tmp) throws IOException {
        Path modsDir = tmp.resolve("mods");
        Files.createDirectories(modsDir);
        Files.createFile(modsDir.resolve("badmod-1.0.0.jar"));

        MultiForgeCommandDispatcher d = makeWithScanner(
                tmp,
                modsDir,
                jar -> new MultiForgeCommandDispatcher.ScanResult(
                        1,
                        "{\"summary\":{\"errors\":1,\"warnings\":0},\"findings\":"
                                + "[{\"ruleId\":\"R03\",\"severity\":\"ERROR\",\"message\":\"blocking .get()\"}]}",
                        ""));
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"certify", "badmod"}, out::add)).isFalse();
        assertThat(out).anyMatch(l -> l.contains("R03: FAIL (ERROR)"));
        assertThat(out).anyMatch(l -> l.contains("NOT CERTIFIED: badmod-1.0.0.jar"));
    }

    @Test
    void certifyReportsScannerInternalFailure(@TempDir Path tmp) throws IOException {
        Path modsDir = tmp.resolve("mods");
        Files.createDirectories(modsDir);
        Files.createFile(modsDir.resolve("brokenmod-1.0.0.jar"));

        MultiForgeCommandDispatcher d = makeWithScanner(
                tmp,
                modsDir,
                jar -> new MultiForgeCommandDispatcher.ScanResult(2, "", "scanner jar not found or unreadable"));
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"certify", "brokenmod"}, out::add)).isFalse();
        assertThat(out).anyMatch(l -> l.contains("scanner internal failure"));
        assertThat(out).anyMatch(l -> l.contains("NOT CERTIFIED"));
    }

    @Test
    void certifyAllReportsAMissingScannerOnceInsteadOfFailingEveryJar(@TempDir Path tmp) throws IOException {
        Path modsDir = tmp.resolve("mods");
        Files.createDirectories(modsDir);
        Files.createFile(modsDir.resolve("a-1.0.jar"));
        Files.createFile(modsDir.resolve("b-1.0.jar"));

        MultiForgeCommandDispatcher d = makeWithScanner(tmp, modsDir, jar -> {
            throw new MultiForgeCommandDispatcher.ScannerUnavailableException("scanner jar not found");
        });
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"certify", "all"}, out::add)).isFalse();
        assertThat(out).containsExactly("Cannot certify: scanner jar not found");
    }

    private MultiForgeCommandDispatcher makeWithChunks(
            Path tmp,
            java.util.function.Function<
                            net.multiforge.api.world.WorldRef, net.multiforge.runtime.chunk.ChunkHolderManager>
                    chunkManagers)
            throws IOException {
        MultiForgeConfigStore store = new MultiForgeConfigStore(tmp.resolve("mf.toml"), MultiForgeConfig.defaults());
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.toml"));
        return new MultiForgeCommandDispatcher(store, pins, chunkManagers);
    }

    @Test
    void chunkCostReportSplitsNearFromTopAndDividesByTicks() {
        long busy = ChunkCost.pack(0, 0);
        long far = ChunkCost.pack(-60, 220);
        ChunkCost.Drained d = new ChunkCost.Drained(new long[] {busy, far}, new long[] {40_000_000L, 200_000L}, 2, 20);

        String line = MultiForgeCommandDispatcher.renderChunkCost(d, "minecraft:overworld", -60, 220, 2);

        assertThat(line)
                .contains("ticks=20 chunks=2")
                .contains("total=2.010ms")
                .contains("near=[-60,220]r2 chunks=1 sum=0.010ms max=0.010ms")
                .contains("top=[0,0]=2.000 [-60,220]=0.010");
    }

    @Test
    void chunkCostReportNeedsTimingOn(@TempDir Path tmp) throws IOException {
        ChunkCost.setReporting(false);
        List<String> out = new ArrayList<>();
        boolean ok = make(tmp)
                .dispatch(new String[] {"chunkcost", "report", "minecraft:overworld", "0", "0", "2"}, out::add);
        assertThat(ok).isFalse();
        assertThat(out).anyMatch(l -> l.contains("chunkcost on"));
    }
}
