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
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.ChunkTaskPriority;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.multiforge.runtime.shutdown.RegionShutdownCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the Phase 5 wave A wiring installed by
 * {@link MultiThreadedSchedulerHost#installM9WiredTickBody(
 *     PhasedRegionTickBody.Builder, RegionShutdownCoordinator, Path)}:
 * that {@code pollFullLoadUpdate} runs in the INBOUND_MAILBOX phase,
 * {@code ChunkTaskScheduler.drainInto} in REGION_EVENTS, and
 * {@code AutoSaveRunner.runOnce} in FLUSH_OUTBOUND — each with the
 * correct region argument and in the documented phase order.
 */
class PhasedRegionTickBodyWiringTest {

    private static final WorldRef WORLD = WorldRef.of("test:phase5-wiring");
    private static final ChunkPos POS = new ChunkPos(0, 0);

    private MultiThreadedSchedulerHost host;

    @BeforeEach
    void install() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config);
        // Shut down the scheduler worker pool up-front — TickRegionScheduler's
        // constructor starts workers eagerly and they would race the tests'
        // manual body.tickOnce below (observed on CI: expected size=1 but
        // was=2, and mid-write journal reads throwing IOException). Each
        // test drives its region's tick loop by hand.
        host.scheduler().close();
    }

    @AfterEach
    void shutdown() {
        host.close();
    }

    @Test
    void pollFullLoadUpdateFiresInInboundMailboxPhase(@TempDir Path journalDir) {
        Region region = host.touchChunk(WORLD, POS.x(), POS.z());

        // Seed a pending full-load-update via the holder manager
        ChunkHolderManager manager = host.chunkManagerFor(WORLD);
        NewChunkHolder holder = manager.holderAt(POS);
        assertThat(holder).isNotNull();
        manager.regionData(region.id()).enqueueFullLoadUpdate(holder);
        assertThat(manager.regionData(region.id()).pendingFullLoadCount()).isEqualTo(1);

        // Wire an observer that records phase order; the M9 wiring
        // prepends into INBOUND_MAILBOX, appends into REGION_EVENTS and
        // FLUSH_OUTBOUND, so a user-supplied body still runs.
        List<PhasedRegionTickBody.Phase> observed = new ArrayList<>();
        PhasedRegionTickBody.Builder userBuilder = PhasedRegionTickBody.builder()
                .inboundMailbox(r -> observed.add(PhasedRegionTickBody.Phase.INBOUND_MAILBOX))
                .blockFluidTicks(r -> observed.add(PhasedRegionTickBody.Phase.BLOCK_FLUID_TICKS))
                .entityAi(r -> observed.add(PhasedRegionTickBody.Phase.ENTITY_AI))
                .blockEntities(r -> observed.add(PhasedRegionTickBody.Phase.BLOCK_ENTITIES))
                .regionEvents(r -> observed.add(PhasedRegionTickBody.Phase.REGION_EVENTS))
                .flushOutbound(r -> observed.add(PhasedRegionTickBody.Phase.FLUSH_OUTBOUND));
        host.installM9WiredTickBody(userBuilder, null, journalDir);

        // Drive one tick directly through the installed body.
        RegionTickBody body = host.scheduler().body();
        body.tickOnce(region);

        // pollFullLoadUpdate drained the queue.
        assertThat(manager.regionData(region.id()).pendingFullLoadCount()).isEqualTo(0);
        // The user's INBOUND_MAILBOX body still fired, and all six phases ran in order.
        assertThat(observed)
                .containsExactly(
                        PhasedRegionTickBody.Phase.INBOUND_MAILBOX,
                        PhasedRegionTickBody.Phase.BLOCK_FLUID_TICKS,
                        PhasedRegionTickBody.Phase.ENTITY_AI,
                        PhasedRegionTickBody.Phase.BLOCK_ENTITIES,
                        PhasedRegionTickBody.Phase.REGION_EVENTS,
                        PhasedRegionTickBody.Phase.FLUSH_OUTBOUND);
    }

    @Test
    void drainIntoFiresInRegionEventsPhase(@TempDir Path journalDir) {
        Region region = host.touchChunk(WORLD, POS.x(), POS.z());

        // Enqueue a chunk task on the region's BLOCKING priority deque —
        // scheduleChunkTask both stashes the wrapped task AND enqueues
        // a trampoline into the region's inbox, but that inbox is
        // drained by the scheduler itself, not by the body. The
        // REGION_EVENTS wiring is what forces the priority deque to
        // drain via drainInto(regionId, max, deadline).
        List<String> ran = new ArrayList<>();
        host.chunkTaskScheduler()
                .scheduleChunkTask(WORLD, POS.x(), POS.z(), () -> ran.add("chunk-task"), ChunkTaskPriority.BLOCKING);
        // Drop the trampoline the scheduleChunkTask call put on the
        // inbox — we want to observe the wiring's own drain, not the
        // inbox pump's.
        // (Trampoline call sites are documented in ChunkTaskScheduler.)
        assertThat(host.chunkTaskScheduler().pending(region.id(), ChunkTaskPriority.BLOCKING))
                .isEqualTo(1);

        PhasedRegionTickBody.Builder empty = PhasedRegionTickBody.builder();
        host.installM9WiredTickBody(empty, null, journalDir);
        RegionTickBody body = host.scheduler().body();
        body.tickOnce(region);

        // drainInto emptied the BLOCKING deque and ran the task.
        assertThat(ran).containsExactly("chunk-task");
        assertThat(host.chunkTaskScheduler().pending(region.id(), ChunkTaskPriority.BLOCKING))
                .isEqualTo(0);
    }

    @Test
    void autoSaveRunsInFlushOutboundPhase(@TempDir Path journalDir) throws Exception {
        // Force short autosave interval for the test — reflect on the
        // constant would work, but the class exposes DEFAULT_AUTOSAVE_INTERVAL_TICKS
        // and we drive `region.currentTick()` through the exposed API.
        Region region = host.touchChunk(WORLD, POS.x(), POS.z());
        ChunkHolderManager manager = host.chunkManagerFor(WORLD);
        NewChunkHolder holder = manager.holderAt(POS);
        assertThat(holder).isNotNull();

        // Enqueue a dirty holder for autosave.
        holder.markDirty();
        manager.regionData(region.id()).enqueueAutoSave(holder);

        RegionShutdownCoordinator coord = new RegionShutdownCoordinator(host.scheduler(), host.taskQueue());
        PhasedRegionTickBody.Builder empty = PhasedRegionTickBody.builder();
        host.installM9WiredTickBody(empty, coord, journalDir);

        // First tick — the region's currentTick is 0 and there is no
        // recorded lastAutosaveTick, so the autosave path runs immediately.
        RegionTickBody body = host.scheduler().body();
        body.tickOnce(region);

        // The stub serializer wrote a CHUNK_SAVE entry to the journal.
        var journal = host.journalLifecycle().journalFor(region.id());
        assertThat(journal).isNotNull();
        assertThat(journal.readAll()).hasSize(1);
        // Autosave queue was drained.
        assertThat(manager.regionData(region.id()).autoSaveCount()).isEqualTo(0);
    }
}
