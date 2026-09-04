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
}
