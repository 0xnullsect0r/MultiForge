/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.multiforge.api.entity.EntityRef;
import net.multiforge.api.spi.SchedulerHost;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerDomainsInstallTest {

    @BeforeEach
    @AfterEach
    void reset() {
        ServerDomains.resetForTesting();
    }

    @Test
    void noHostThrows() {
        assertThatThrownBy(() -> ServerDomains.global()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void installReplaceFailsWithDifferentInstance() {
        SchedulerHost a = new StubHost();
        SchedulerHost b = new StubHost();
        ServerDomains.install(a);
        assertThatThrownBy(() -> ServerDomains.install(b)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void installIsIdempotentWithSameInstance() {
        SchedulerHost a = new StubHost();
        ServerDomains.install(a);
        ServerDomains.install(a); // no throw
        assertThat(ServerDomains.global()).isNotNull();
    }

    private static final class StubHost implements SchedulerHost {
        @Override
        public RegionDomain region(WorldRef world, ChunkPos pos) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EntityDomain entity(EntityRef entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GlobalDomain global() {
            return new GlobalDomain() {
                @Override
                public ScheduledTask execute(net.multiforge.api.mod.ModIdentifier mod, Runnable task) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public ScheduledTask run(
                        net.multiforge.api.mod.ModIdentifier mod, java.util.function.Consumer<ScheduledTask> task) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public ScheduledTask runDelayed(
                        net.multiforge.api.mod.ModIdentifier mod,
                        java.util.function.Consumer<ScheduledTask> task,
                        long delayTicks) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public ScheduledTask runAtFixedRate(
                        net.multiforge.api.mod.ModIdentifier mod,
                        java.util.function.Consumer<ScheduledTask> task,
                        long initialTicks,
                        long periodTicks) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public AsyncDomain async() {
            throw new UnsupportedOperationException();
        }
    }
}
