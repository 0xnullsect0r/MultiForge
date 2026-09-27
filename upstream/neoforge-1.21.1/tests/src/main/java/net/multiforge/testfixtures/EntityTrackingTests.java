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
package net.multiforge.testfixtures;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.phys.Vec3;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * GameTests for indexed entity tracking (ChunkMap in
 * multiforge-patches/05-entity-migration/). The GameTest server runs with
 * {@code -Dmultiforge.tracking.verify=true}, so every indexed update is also
 * checked against Vanilla's full pass; any disagreement bumps {@code
 * tracking.mismatch}, and any drift of the reverse index {@code
 * tracking.reconciled}. Each test moves a player or an entity the ways that
 * start and stop tracking, and requires both probes to stay unchanged along
 * with the visible outcome.
 */
@ForEachTest(groups = "multiforge.tracking")
public class EntityTrackingTests {
    private static boolean watches(GameTestHelper helper, Pig pig, ServerPlayer player) {
        return helper.getLevel().getChunkSource().chunkMap.getPlayersWatching(pig).contains(player);
    }

    /**
     * A player in the level (so ChunkMap tracks entities for it) over an in-memory
     * connection, but not in the player list: the test framework sends its own
     * payloads to every listed player, which a mock connection cannot accept.
     */
    private static ServerPlayer addPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "mf-tracking-test");
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(
                level.getServer(), connection, player, CommonListenerCookie.createInitial(profile, false));
        level.addNewPlayer(player);
        return player;
    }

    private static void removePlayer(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
    }

    /**
     * Sends the player's pending chunks, as its connection's tick would, and
     * acknowledges them as a client does: isChunkTracked (so tracking) only counts
     * chunks already sent, and this connection is not ticked by the server.
     */
    private static void sendChunks(ServerPlayer player) {
        player.connection.chunkSender.onChunkBatchReceivedByClient(64.0F);
        player.connection.chunkSender.sendNextChunks(player);
    }

    /** Moves {@code player} as a movement packet would, including the tracking update. */
    private static void moveTo(GameTestHelper helper, ServerPlayer player, Vec3 pos) {
        player.moveTo(pos.x, pos.y, pos.z);
        helper.getLevel().getChunkSource().move(player);
    }

    private static void assertNoTrackingErrors(GameTestHelper helper, long mismatches, long reconciled) {
        helper.assertTrue(
                ProbeRegistry.get("tracking.mismatch") == mismatches,
                "indexed tracking disagreed with Vanilla's full pass ("
                        + (ProbeRegistry.get("tracking.mismatch") - mismatches) + " mismatches)");
        helper.assertTrue(
                ProbeRegistry.get("tracking.reconciled") == reconciled,
                "the tracking reverse index drifted (" + (ProbeRegistry.get("tracking.reconciled") - reconciled)
                        + " repairs)");
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 200)
    @TestHolder(description = {
            "A player that comes near an entity starts tracking it, and stops when it moves",
            "out of range, with every indexed update matching Vanilla's full pass."
    })
    static void playerMovesIntoAndOutOfView(final DynamicTest test) {
        test.onGameTest(helper -> {
            long mismatches = ProbeRegistry.get("tracking.mismatch");
            long reconciled = ProbeRegistry.get("tracking.reconciled");
            Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(1, 2, 1));
            ServerPlayer player = addPlayer(helper);
            Vec3 near = helper.absoluteVec(new Vec3(1.5, 2, 4.5));
            Vec3 far = near.add(1000, 0, 0);
            helper.startSequence()
                    .thenExecute(() -> moveTo(helper, player, near))
                    .thenWaitUntil(() -> {
                        sendChunks(player);
                        moveTo(helper, player, near);
                        helper.assertTrue(watches(helper, pig, player), "the nearby player never started tracking the pig");
                    })
                    .thenExecute(() -> moveTo(helper, player, far))
                    .thenExecute(() -> helper.assertFalse(
                            watches(helper, pig, player), "a player 1000 blocks away still tracks the pig"))
                    .thenExecute(() -> assertNoTrackingErrors(helper, mismatches, reconciled))
                    .thenExecute(() -> removePlayer(helper, player))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 200)
    @TestHolder(description = {
            "An entity that moves away from a player, or is removed, stops being tracked by it,",
            "and a player that leaves is no longer tracking anything."
    })
    static void entityMovesAwayAndIsRemoved(final DynamicTest test) {
        test.onGameTest(helper -> {
            long mismatches = ProbeRegistry.get("tracking.mismatch");
            long reconciled = ProbeRegistry.get("tracking.reconciled");
            Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(1, 2, 1));
            Pig other = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(1, 2, 2));
            ServerPlayer player = addPlayer(helper);
            Vec3 near = helper.absoluteVec(new Vec3(1.5, 2, 4.5));
            helper.startSequence()
                    .thenExecute(() -> moveTo(helper, player, near))
                    .thenWaitUntil(() -> {
                        sendChunks(player);
                        moveTo(helper, player, near);
                        helper.assertTrue(watches(helper, pig, player), "the nearby player never started tracking the pig");
                        helper.assertTrue(watches(helper, other, player), "the nearby player never started tracking the second pig");
                    })
                    // The pig leaves: ChunkMap.tick re-checks it for every player when it changes section.
                    .thenExecute(() -> pig.teleportTo(pig.getX() + 1000, pig.getY(), pig.getZ()))
                    .thenWaitUntil(() -> helper.assertFalse(
                            watches(helper, pig, player), "the player still tracks a pig 1000 blocks away"))
                    .thenExecute(other::discard)
                    .thenExecute(() -> moveTo(helper, player, near))
                    .thenExecute(() -> removePlayer(helper, player))
                    .thenExecute(() -> helper.assertFalse(
                            watches(helper, pig, player), "a player that left still tracks the pig"))
                    .thenExecute(() -> assertNoTrackingErrors(helper, mismatches, reconciled))
                    .thenExecute(pig::discard)
                    .thenSucceed();
        });
    }
}
