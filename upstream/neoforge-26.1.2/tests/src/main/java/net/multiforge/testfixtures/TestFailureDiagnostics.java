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

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs chunk, light and thread state when a test that has failed intermittently fails again.
 * Silent when the test passes; it changes nothing the test checks.
 */
public final class TestFailureDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger("multiforge.testdiag");
    private static final int STACK_DEPTH = 14;

    private TestFailureDiagnostics() {}

    /** Reports {@code absolutePos} and its surroundings if this test ends in failure. */
    public static void onFailure(ExtendedGameTestHelper helper, String name, BlockPos absolutePos) {
        helper.addEndListener(passed -> {
            if (!passed) {
                LOGGER.error("MFDIAG {}\n{}", name, describe(helper.getLevel(), absolutePos));
            }
        });
    }

    private static String describe(ServerLevel level, BlockPos pos) {
        ServerChunkCache chunks = level.getChunkSource();
        ChunkPos center = ChunkPos.containing(pos);
        StringBuilder out = new StringBuilder();
        out.append("pos=").append(pos.toShortString())
                .append(" gameTime=").append(level.getGameTime())
                .append(" hasChunk=").append(level.hasChunk(center.x(), center.z()))
                .append(" forced=").append(level.getForceLoadedChunks().contains(center.pack()))
                .append(" positionTicking=").append(chunks.isPositionTicking(center.pack()))
                .append(" entityTicking=").append(level.isPositionEntityTicking(pos))
                .append(" pendingTasks=").append(chunks.getPendingTasksCount())
                .append(" lightWork=").append(chunks.getLightEngine().hasLightWork())
                .append(" brightness=").append(level.getLightEngine().getRawBrightness(pos, 0))
                .append('\n');
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                ChunkPos ring = new ChunkPos(center.x() + dx, center.z() + dz);
                out.append("chunk ").append(ring.x()).append(',').append(ring.z()).append(": ")
                        .append(chunks.getChunkDebugData(ring)).append('\n');
            }
        }
        for (Map.Entry<Thread, StackTraceElement[]> thread : Thread.getAllStackTraces().entrySet()) {
            String threadName = thread.getKey().getName();
            if (threadName.equals("Server thread") || threadName.startsWith("multiforge-tick-") || threadName.startsWith("Worker-Main")) {
                out.append(threadName).append(' ').append(thread.getKey().getState()).append('\n');
                StackTraceElement[] stack = thread.getValue();
                for (int i = 0; i < Math.min(STACK_DEPTH, stack.length); i++) {
                    out.append("    at ").append(stack[i]).append('\n');
                }
            }
        }
        return out.toString();
    }
}
