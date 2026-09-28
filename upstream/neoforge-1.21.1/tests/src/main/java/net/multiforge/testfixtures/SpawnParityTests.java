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
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
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
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.neoforge.world.SpawnChunkCredit;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

/**
 * GameTests for natural-spawn caps per region (multiforge-patches/02-region-tick/,
 * {@code ServerChunkCache.mfQueueRegionChunkTicks}): each region's spawn state is
 * scaled by its share of Vanilla's {@code getNaturalSpawnChunkCount()} (the
 * 17×17 squares around player chunks, deduped level-wide), so a lone player's
 * region gets exactly the cap mode {@code off} computes, and overlapping squares
 * are counted once whichever region they fall in.
 */
@ForEachTest(groups = "multiforge.spawning")
public class SpawnParityTests {
    private static final int SQUARE = 17 * 17;

    /** A player the chunk map tracks, outside the player list (see EntityTrackingTests). */
    private static ServerPlayer addPlayer(ExtendedGameTestHelper helper, BlockPos at) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "mf-spawn-test");
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(
                level.getServer(), connection, player, CommonListenerCookie.createInitial(profile, false));
        player.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        level.addNewPlayer(player);
        // Keep the player's chunk loaded (so in a region) whatever the test server's view distance.
        ChunkPos chunk = new ChunkPos(at);
        level.setChunkForced(chunk.x, chunk.z, true);
        helper.addEndListener(passed -> {
            if (!player.isRemoved()) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            level.setChunkForced(chunk.x, chunk.z, false);
        });
        return player;
    }

    /** Vanilla's count for {@code seeds}: chunks within Chebyshev distance 8 of any of them. */
    private static int vanillaCount(long... seeds) {
        LongOpenHashSet chunks = new LongOpenHashSet();
        for (long seed : seeds) {
            for (int dx = -8; dx <= 8; dx++) {
                for (int dz = -8; dz <= 8; dz++) chunks.add(ChunkPos.asLong(ChunkPos.getX(seed) + dx, ChunkPos.getZ(seed) + dz));
            }
        }
        return chunks.size();
    }

    private static long regionOf(GameTestHelper helper, ChunkPos pos) {
        return RegionizedTickCoordinator.regionIdAt(helper.getLevel(), pos.x, pos.z);
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "Region size 4: the spawn squares of players in different regions are deduped level-wide,",
            "each chunk credited once, and the credits add up to Vanilla's natural-spawn chunk count."
    })
    static void creditsAddUpToVanillasCount(final DynamicTest test) {
        test.onGameTest(helper -> {
            // Region size 4: a region is a group of 4×4-chunk sections, and regions are at least one
            // section apart. Model the owner as the section column (sections 0 and 3 are distinct regions).
            java.util.function.LongUnaryOperator size4 = seed -> Math.floorDiv(ChunkPos.getX(seed), 4);
            long a = ChunkPos.asLong(0, 0);
            long b = ChunkPos.asLong(12, 0); // 12 chunks apart: the squares overlap in 5 columns
            long c = ChunkPos.asLong(1, 2); // shares a's region, square mostly a's
            LongOpenHashSet seen = new LongOpenHashSet();
            Long2IntOpenHashMap credits = new Long2IntOpenHashMap();

            int one = SpawnChunkCredit.credit(LongLinkedOpenHashSet.of(a), size4, seen, credits);
            helper.assertValueEqual(one, SQUARE, "one player's spawn chunks");
            helper.assertValueEqual(credits.get(0L), SQUARE, "a lone player's region credit");

            LongSet two = LongLinkedOpenHashSet.of(a, b);
            int total = SpawnChunkCredit.credit(two, size4, seen, credits);
            helper.assertValueEqual(total, vanillaCount(a, b), "two players' spawn chunks");
            helper.assertValueEqual(total, 2 * SQUARE - 5 * 17, "two overlapping squares");
            helper.assertValueEqual(credits.get(0L), SQUARE, "the first player's region credit");
            helper.assertValueEqual(credits.get(3L), SQUARE - 5 * 17, "the second player's region credit (overlap deduped)");
            helper.assertValueEqual(credits.get(0L) + credits.get(3L), total, "region credits vs Vanilla's count");

            int three = SpawnChunkCredit.credit(LongLinkedOpenHashSet.of(a, b, c), size4, seen, credits);
            helper.assertValueEqual(three, vanillaCount(a, b, c), "three players' spawn chunks");
            int sum = 0;
            for (Long2IntMap.Entry e : credits.long2IntEntrySet()) sum += e.getIntValue();
            helper.assertValueEqual(sum, three, "region credits vs Vanilla's count");
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 600)
    @TestHolder(description = {
            "One player alone in its region: the region's spawn chunk count, and so every mob cap,",
            "is exactly the one mode = off computes for the level (289 chunks)."
    })
    static void lonePlayerGetsVanillasCap(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (MultiForgeRegionizedRuntime.current() == null) {
                helper.succeed(); // mode = off: Vanilla's level-wide spawn state is the only one
                return;
            }
            BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1)).offset(3000, 0, 0);
            ChunkPos chunk = new ChunkPos(at);
            addPlayer(helper, at);
            helper.startSequence()
                    .thenWaitUntil(() -> {
                        long region = regionOf(helper, chunk);
                        helper.assertTrue(region >= 0, "the player's chunk is not in a region yet");
                        helper.assertTrue(
                                helper.getLevel().getChunkSource().mfSpawnChunkCredits().containsKey(region),
                                "no spawn chunks credited to the player's region yet");
                    })
                    .thenExecute(() -> {
                        int credit = helper.getLevel().getChunkSource().mfSpawnChunkCredits().get(regionOf(helper, chunk));
                        int vanilla = vanillaCount(chunk.toLong());
                        helper.assertValueEqual(credit, vanilla, "the lone player's region spawn chunk count");
                        for (MobCategory category : MobCategory.values()) {
                            // NaturalSpawner.SpawnState.canSpawnForCategory's cap.
                            helper.assertValueEqual(
                                    category.getMaxInstancesPerChunk() * credit / SQUARE,
                                    category.getMaxInstancesPerChunk() * vanilla / SQUARE,
                                    category + " cap");
                        }
                    })
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 600)
    @TestHolder(description = {
            "Two players 12 chunks apart: their overlapping spawn squares are credited once between",
            "their regions, adding up to Vanilla's count for the two."
    })
    static void twoPlayersShareTheOverlap(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (MultiForgeRegionizedRuntime.current() == null) {
                helper.succeed();
                return;
            }
            BlockPos atA = helper.absolutePos(new BlockPos(1, 2, 1)).offset(-3000, 0, 0);
            BlockPos atB = atA.offset(12 * 16, 0, 0);
            ChunkPos a = new ChunkPos(atA);
            ChunkPos b = new ChunkPos(atB);
            addPlayer(helper, atA);
            addPlayer(helper, atB);
            helper.startSequence()
                    .thenWaitUntil(() -> {
                        Long2IntMap credits = helper.getLevel().getChunkSource().mfSpawnChunkCredits();
                        long ra = regionOf(helper, a);
                        long rb = regionOf(helper, b);
                        helper.assertTrue(ra >= 0 && rb >= 0, "the players' chunks are not in regions yet");
                        helper.assertTrue(credits.containsKey(ra) && credits.containsKey(rb), "no spawn chunks credited yet");
                    })
                    .thenExecute(() -> {
                        Long2IntMap credits = helper.getLevel().getChunkSource().mfSpawnChunkCredits();
                        long ra = regionOf(helper, a);
                        long rb = regionOf(helper, b);
                        int sum = ra == rb ? credits.get(ra) : credits.get(ra) + credits.get(rb);
                        helper.assertValueEqual(sum, vanillaCount(a.toLong(), b.toLong()), "the two players' region credits");
                        if (ra != rb) {
                            helper.assertTrue(
                                    Math.max(credits.get(ra), credits.get(rb)) == SQUARE,
                                    "the first player's region should get its whole square");
                        }
                    })
                    .thenSucceed();
        });
    }
}
