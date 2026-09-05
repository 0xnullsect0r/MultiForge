/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.config.MultiForgeConfigStore;
import net.multiforge.runtime.region.pin.RegionPinManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiForgeCommandDispatcherTest {

    private MultiForgeCommandDispatcher make(Path tmp) throws IOException {
        MultiForgeConfigStore store = new MultiForgeConfigStore(tmp.resolve("mf.toml"), MultiForgeConfig.defaults());
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.toml"));
        return new MultiForgeCommandDispatcher(store, pins);
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
}
