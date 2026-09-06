/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
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
    void regionSizePowerOfTwoOnly(@TempDir Path tmp) throws IOException {
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"region", "size", "6"}, out::add)).isFalse();
        out.clear();
        assertThat(d.dispatch(new String[] {"region", "size", "16"}, out::add)).isTrue();
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

    // M9 sub-step 1: /multiforge chunks — reports chunk-holder counts by ChunkLoadLevel,
    // using the ChunkHolderManager the fork bridge populates from Vanilla ticket events.
    @Test
    void chunksNotInstalledReportsFailure(@TempDir Path tmp) throws IOException {
        // The 2-arg constructor leaves chunkManagers null (bridge not wired).
        MultiForgeCommandDispatcher d = make(tmp);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"chunks", "minecraft:overworld"}, out::add))
                .isFalse();
        assertThat(out).anyMatch(l -> l.contains("bridge not installed"));
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
    void chunksReportsPerLevelCounts(@TempDir Path tmp) throws IOException {
        net.multiforge.api.world.WorldRef world = net.multiforge.api.world.WorldRef.of("minecraft:overworld");
        net.multiforge.runtime.chunk.ChunkHolderManager manager =
                new net.multiforge.runtime.chunk.ChunkHolderManager(world);
        net.multiforge.runtime.region.RegionId regionId = net.multiforge.runtime.region.RegionId.next();
        net.multiforge.runtime.chunk.NewChunkHolder h1 =
                manager.createHolder(new net.multiforge.api.world.ChunkPos(0, 0), regionId);
        h1.setLevel(net.multiforge.runtime.chunk.ChunkLoadLevel.TICKING);
        net.multiforge.runtime.chunk.NewChunkHolder h2 =
                manager.createHolder(new net.multiforge.api.world.ChunkPos(1, 0), regionId);
        h2.setLevel(net.multiforge.runtime.chunk.ChunkLoadLevel.TICKING);
        net.multiforge.runtime.chunk.NewChunkHolder h3 =
                manager.createHolder(new net.multiforge.api.world.ChunkPos(2, 0), regionId);
        h3.setLevel(net.multiforge.runtime.chunk.ChunkLoadLevel.BORDER);
        // h4 stays at the default INACCESSIBLE
        manager.createHolder(new net.multiforge.api.world.ChunkPos(3, 0), regionId);

        java.util.Map<net.multiforge.api.world.WorldRef, net.multiforge.runtime.chunk.ChunkHolderManager> map =
                new java.util.HashMap<>();
        map.put(world, manager);
        MultiForgeCommandDispatcher d = makeWithChunks(tmp, map::get);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"chunks", "minecraft:overworld"}, out::add))
                .isTrue();
        assertThat(out).anyMatch(l -> l.contains("holders=4"));
        assertThat(out).anyMatch(l -> l.contains("TICKING") && l.contains(": 2"));
        assertThat(out).anyMatch(l -> l.contains("BORDER") && l.contains(": 1"));
        assertThat(out).anyMatch(l -> l.contains("INACCESSIBLE") && l.contains(": 1"));
    }

    @Test
    void chunksUnknownWorldReportsNone(@TempDir Path tmp) throws IOException {
        java.util.Map<net.multiforge.api.world.WorldRef, net.multiforge.runtime.chunk.ChunkHolderManager> map =
                new java.util.HashMap<>();
        MultiForgeCommandDispatcher d = makeWithChunks(tmp, map::get);
        List<String> out = new ArrayList<>();
        assertThat(d.dispatch(new String[] {"chunks", "minecraft:bogus"}, out::add))
                .isTrue();
        assertThat(out).anyMatch(l -> l.contains("No chunk manager"));
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
}
