/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.region.PhasedRegionTickBody;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionTickBody;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.multiforge.runtime.shutdown.RegionShutdownCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for {@link MultiThreadedSchedulerHost#setChunkSerializer}
 * — the Phase 5 wave B pluggable chunk-payload serializer that replaces the
 * hardcoded {@code (r, holder) -> new byte[0]} stub previously baked into
 * {@link MultiThreadedSchedulerHost#autoSaveRunnerFor}.
 *
 * <p>Drives the FLUSH_OUTBOUND phase exactly like {@code
 * net.multiforge.runtime.region.PhasedRegionTickBodyWiringTest#autoSaveRunsInFlushOutboundPhase}
 * (one {@code body.tickOnce(region)} call through the M9-wired tick body),
 * but focuses on the serializer plumbing itself rather than phase ordering.
 *
 * <p>This module stays MC-free, so these tests can't exercise the real
 * {@code net.multiforge.neoforge.io.RegionChunkSerializer.serializeForJournal}
 * (that lives in the upstream/neoforge-1.21.1 fork module) — they instead
 * verify the contract that production serializer relies on: the exact
 * {@link Region} and {@link NewChunkHolder} reach the registered serializer,
 * a null {@code currentChunk} is observable through that holder so a real
 * serializer can degrade to {@code byte[0]} itself, and any serializer
 * exception is swallowed before it can reach the tick pipeline.
 */
class AutoSaveRunnerSerializerTest {

    private static final WorldRef WORLD = WorldRef.of("test:autosave-serializer-wiring");
    private static final ChunkPos POS = new ChunkPos(0, 0);

    private MultiThreadedSchedulerHost host;

    @BeforeEach
    void install() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config);
        // Shut down the scheduler worker pool up-front — see the
        // matching comment in PhasedRegionTickBodyWiringTest.install.
        host.scheduler().close();
    }

    @AfterEach
    void shutdown() {
        host.close();
    }

    /** Seed one dirty, autosave-enqueued holder and wire the M9 FLUSH_OUTBOUND phase. */
    private NewChunkHolder seedDirtyHolderAndInstallBody(Path journalDir) {
        Region region = host.touchChunk(WORLD, POS.x(), POS.z());
        ChunkHolderManager manager = host.chunkManagerFor(WORLD);
        NewChunkHolder holder = manager.holderAt(POS);
        assertThat(holder).isNotNull();
        holder.markDirty();
        manager.regionData(region.id()).enqueueAutoSave(holder);

        RegionShutdownCoordinator coord = new RegionShutdownCoordinator(host.scheduler(), host.taskQueue());
        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), coord, journalDir);
        return holder;
    }

    @Test
    void default_stub_serializer_returns_empty(@TempDir Path journalDir) throws Exception {
        NewChunkHolder holder = seedDirtyHolderAndInstallBody(journalDir);
        Region region = host.regionizerFor(WORLD).regionAtChunk(POS.x(), POS.z());

        // No setChunkSerializer call — the constructor default applies.
        RegionTickBody body = host.scheduler().body();
        body.tickOnce(region);

        RegionJournal journal = host.journalLifecycle().journalFor(region.id());
        assertThat(journal).isNotNull();
        assertThat(journal.readAll()).hasSize(1);
        assertThat(journal.readAll().get(0).payload()).isEmpty();
        assertThat(holder.isDirty()).isFalse();
    }

    @Test
    void custom_serializer_is_invoked(@TempDir Path journalDir) throws Exception {
        NewChunkHolder holder = seedDirtyHolderAndInstallBody(journalDir);
        Region region = host.regionizerFor(WORLD).regionAtChunk(POS.x(), POS.z());

        AtomicReference<Region> seenRegion = new AtomicReference<>();
        AtomicReference<NewChunkHolder> seenHolder = new AtomicReference<>();
        byte[] payload = "custom-payload".getBytes(StandardCharsets.UTF_8);
        host.setChunkSerializer((r, h) -> {
            seenRegion.set(r);
            seenHolder.set(h);
            return payload;
        });

        RegionTickBody body = host.scheduler().body();
        body.tickOnce(region);

        assertThat(seenRegion.get()).isNotNull();
        assertThat(seenRegion.get().id()).isEqualTo(region.id());
        assertThat(seenHolder.get()).isSameAs(holder);

        RegionJournal journal = host.journalLifecycle().journalFor(region.id());
        assertThat(journal).isNotNull();
        assertThat(journal.readAll()).hasSize(1);
        assertThat(journal.readAll().get(0).payload()).isEqualTo(payload);
    }

    @Test
    void serializer_exception_is_swallowed(@TempDir Path journalDir) throws Exception {
        seedDirtyHolderAndInstallBody(journalDir);
        Region region = host.regionizerFor(WORLD).regionAtChunk(POS.x(), POS.z());

        host.setChunkSerializer((r, h) -> {
            throw new RuntimeException("boom — simulated serializer bug");
        });

        RegionTickBody body = host.scheduler().body();
        // The throwing serializer must never escape the tick pipeline
        // (CLAUDE.md rule 5: auto-reroute + warn, never propagate).
        assertThatCode(() -> body.tickOnce(region)).doesNotThrowAnyException();

        RegionJournal journal = host.journalLifecycle().journalFor(region.id());
        assertThat(journal).isNotNull();
        assertThat(journal.readAll()).hasSize(1);
        assertThat(journal.readAll().get(0).payload()).isEmpty();
    }

    @Test
    void null_chunk_returns_empty(@TempDir Path journalDir) throws Exception {
        NewChunkHolder holder = seedDirtyHolderAndInstallBody(journalDir);
        Region region = host.regionizerFor(WORLD).regionAtChunk(POS.x(), POS.z());

        // Never promoted past BORDER in this test — currentChunk stays null,
        // exactly like a real holder whose chunk hasn't loaded (or has
        // already unloaded) when autosave fires. A production serializer
        // (RegionChunkSerializer.serializeForJournal) checks this and
        // returns byte[0]; verify the holder reaching the serializer
        // exposes that same null so the fork-side check can fire.
        assertThat(holder.getCurrentChunk()).isNull();
        host.setChunkSerializer((r, h) -> h.getCurrentChunk() == null
                ? new byte[0]
                : "unexpected-non-null-chunk".getBytes(StandardCharsets.UTF_8));

        RegionTickBody body = host.scheduler().body();
        body.tickOnce(region);

        RegionJournal journal = host.journalLifecycle().journalFor(region.id());
        assertThat(journal).isNotNull();
        assertThat(journal.readAll()).hasSize(1);
        assertThat(journal.readAll().get(0).payload()).isEmpty();
    }
}
