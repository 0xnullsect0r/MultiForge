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
package net.multiforge.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link MultiForgeConfigStore}: persist before publishing, and every listener sees a change. */
class MultiForgeConfigStoreTest {

    @Test
    void aFailedWriteChangesNothing(@TempDir Path tmp) throws IOException {
        // The config "file" is a directory, so the atomic write fails.
        Path file = tmp.resolve("multiforge-server.toml");
        Files.createDirectories(file);
        MultiForgeConfigStore store = new MultiForgeConfigStore(file, MultiForgeConfig.defaults());
        List<MultiForgeConfig> seen = new ArrayList<>();
        store.subscribe(seen::add);
        seen.clear();
        assertThatThrownBy(() -> store.update(c -> c.withCores(3))).isInstanceOf(IOException.class);
        assertThat(store.get()).isEqualTo(MultiForgeConfig.defaults());
        assertThat(seen).isEmpty();
    }

    @Test
    void aThrowingListenerDoesNotStopTheOthers(@TempDir Path tmp) throws IOException {
        MultiForgeConfigStore store = new MultiForgeConfigStore(tmp.resolve("c.toml"), MultiForgeConfig.defaults());
        List<Integer> seen = new ArrayList<>();
        store.subscribe(c -> {
            if (c.cores() == 3) throw new IllegalStateException("boom");
        });
        store.subscribe(c -> seen.add(c.cores()));
        seen.clear();
        assertThatThrownBy(() -> store.update(c -> c.withCores(3))).hasMessage("boom");
        assertThat(seen).containsExactly(3);
        assertThat(store.get().cores()).isEqualTo(3);
    }
}
