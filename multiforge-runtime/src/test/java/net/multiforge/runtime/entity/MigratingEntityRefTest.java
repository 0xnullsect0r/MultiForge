/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

class MigratingEntityRefTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");
    private static final WorldRef NETHER = WorldRef.of("minecraft:the_nether");

    @Test
    void newRefStartsResident() {
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        assertThat(ref.migrationState()).isEqualTo(MigrationState.RESIDENT);
        assertThat(ref.isRetired()).isFalse();
    }

    @Test
    void beginMigrationOnlyOncePerRoundTrip() {
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        assertThat(ref.beginMigration()).isTrue();
        assertThat(ref.beginMigration()).isFalse(); // already MIGRATING
        assertThat(ref.migrationState()).isEqualTo(MigrationState.MIGRATING);
        ref.completeMigration(NETHER, new ChunkPos(5, 5));
        assertThat(ref.migrationState()).isEqualTo(MigrationState.RESIDENT);
        assertThat(ref.world().dimensionId()).isEqualTo("minecraft:the_nether");
        assertThat(ref.chunkPos()).isEqualTo(new ChunkPos(5, 5));
    }

    @Test
    void abortReturnsToResident() {
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        assertThat(ref.beginMigration()).isTrue();
        ref.abortMigration();
        assertThat(ref.migrationState()).isEqualTo(MigrationState.RESIDENT);
    }

    @Test
    void retireIsTerminal() {
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        ref.retire();
        assertThat(ref.isRetired()).isTrue();
        assertThat(ref.beginMigration()).isFalse();
    }
}
