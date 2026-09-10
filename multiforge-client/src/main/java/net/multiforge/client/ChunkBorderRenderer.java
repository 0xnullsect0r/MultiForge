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
package net.multiforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.awt.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws vertical strips along the seams where two adjacent chunks
 * belong to different regions. Walks a grid around the player and, for
 * each chunk, draws a line strip on its north and west edge when the
 * neighbour across that edge has a different owning region. Y is
 * clamped to a band around the player so the strips stay crisp and
 * dodge depth-buffer precision loss at high altitude.
 *
 * <p>As of v1.4.0 the ownership behind those seams is <b>real</b>. From
 * v1.3.5 through v1.3.18 this class hash-picked a region id from the
 * chunk coordinates modulo the live region count: the seams it drew
 * were an artifact of how many regions happened to exist, they
 * re-shuffled whenever a region merged or split, and they had no
 * relationship to which region actually owned anything. That was
 * documented as a known limitation here while {@code
 * docs/debugging-violations.md} simultaneously told operators to use
 * the overlay to diagnose real ownership bugs.
 *
 * <p>Now the server ships the true section→region mapping over {@code
 * CHUNK_OWNERSHIP} (protocol §7.7) and this renderer reads it from
 * {@link DebugHudState#regionIdAtChunk(int, int)}. Ownership is tracked
 * per section, so seams appear on section boundaries — that is the
 * genuine ownership boundary, not a coarsening.
 *
 * <p>If no ownership frame has arrived for the current dimension —
 * because the overlay is switched off, or the server predates protocol
 * version 2 — this draws <b>nothing</b>. There is deliberately no
 * fallback to the old hash: inventing a plausible-looking boundary is
 * the defect being fixed.
 */
public final class ChunkBorderRenderer {

    private static final float LINE_ALPHA = 0.75F;

    private final DebugHudState state;

    ChunkBorderRenderer(DebugHudState state) {
        this.state = state;
    }

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!state.overlaysEnabled() || !MultiForgeDebugConfig.CHUNK_BORDERS.get()) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return;
        }
        if (!state.hasOwnershipFor(level.dimension().location().toString())) {
            return;
        }

        int radius = MultiForgeDebugConfig.BORDER_RADIUS_CHUNKS.get();
        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        ChunkPos center = player.chunkPosition();

        double playerY = player.getY();
        double yLow = Mth.clamp(
                playerY - MultiForgeDebugConfig.Y_BELOW.get(), level.getMinBuildHeight(), level.getMaxBuildHeight());
        double yHigh = Mth.clamp(
                playerY + MultiForgeDebugConfig.Y_ABOVE.get(), level.getMinBuildHeight(), level.getMaxBuildHeight());

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = center.x + dx;
                int chunkZ = center.z + dz;
                Long here = state.regionIdAtChunk(chunkX, chunkZ);
                if (here == null) {
                    // Section not loaded / not owned — no seam to draw.
                    continue;
                }

                // North seam: this chunk vs. (chunkX, chunkZ - 1).
                if (dz > -radius) {
                    Long north = state.regionIdAtChunk(chunkX, chunkZ - 1);
                    if (differs(here, north)) {
                        drawSeamZ(poseStack, consumer, camPos, chunkX, chunkZ, yLow, yHigh, here);
                    }
                }

                // West seam: this chunk vs. (chunkX - 1, chunkZ).
                if (dx > -radius) {
                    Long west = state.regionIdAtChunk(chunkX - 1, chunkZ);
                    if (differs(here, west)) {
                        drawSeamX(poseStack, consumer, camPos, chunkX, chunkZ, yLow, yHigh, here);
                    }
                }
            }
        }

        bufferSource.endBatch(RenderType.lines());
    }

    /**
     * A seam exists where the neighbour is owned by a <em>different</em>
     * region. An unknown neighbour (unloaded, or outside what the server
     * sent) is not a seam — drawing one there would put a wall around
     * the edge of the streamed area rather than around a region.
     */
    private static boolean differs(Long here, Long neighbour) {
        return neighbour != null && !neighbour.equals(here);
    }

    /** Vertical wall at the north edge of (chunkX, chunkZ) — from (x, z) to (x+16, z). */
    private static void drawSeamZ(
            PoseStack poseStack,
            VertexConsumer consumer,
            Vec3 camPos,
            int chunkX,
            int chunkZ,
            double yLow,
            double yHigh,
            long regionId) {
        double x0 = chunkX * 16.0 - camPos.x;
        double x1 = x0 + 16.0;
        double z = chunkZ * 16.0 - camPos.z;
        double y0 = yLow - camPos.y;
        double y1 = yHigh - camPos.y;
        float[] rgb = colorFor(regionId);
        drawLine(poseStack, consumer, x0, y0, z, x1, y0, z, rgb);
        drawLine(poseStack, consumer, x0, y1, z, x1, y1, z, rgb);
        drawLine(poseStack, consumer, x0, y0, z, x0, y1, z, rgb);
        drawLine(poseStack, consumer, x1, y0, z, x1, y1, z, rgb);
    }

    /** Vertical wall at the west edge of (chunkX, chunkZ) — from (x, z) to (x, z+16). */
    private static void drawSeamX(
            PoseStack poseStack,
            VertexConsumer consumer,
            Vec3 camPos,
            int chunkX,
            int chunkZ,
            double yLow,
            double yHigh,
            long regionId) {
        double x = chunkX * 16.0 - camPos.x;
        double z0 = chunkZ * 16.0 - camPos.z;
        double z1 = z0 + 16.0;
        double y0 = yLow - camPos.y;
        double y1 = yHigh - camPos.y;
        float[] rgb = colorFor(regionId);
        drawLine(poseStack, consumer, x, y0, z0, x, y0, z1, rgb);
        drawLine(poseStack, consumer, x, y1, z0, x, y1, z1, rgb);
        drawLine(poseStack, consumer, x, y0, z0, x, y1, z0, rgb);
        drawLine(poseStack, consumer, x, y0, z1, x, y1, z1, rgb);
    }

    private static void drawLine(
            PoseStack poseStack,
            VertexConsumer consumer,
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1,
            float[] rgb) {
        var pose = poseStack.last();
        var matrix = pose.pose();
        float nx = (float) (x1 - x0);
        float ny = (float) (y1 - y0);
        float nz = (float) (z1 - z0);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        consumer.addVertex(matrix, (float) x0, (float) y0, (float) z0)
                .setColor(rgb[0], rgb[1], rgb[2], LINE_ALPHA)
                .setNormal(pose, nx, ny, nz);
        consumer.addVertex(matrix, (float) x1, (float) y1, (float) z1)
                .setColor(rgb[0], rgb[1], rgb[2], LINE_ALPHA)
                .setNormal(pose, nx, ny, nz);
    }

    /** Stable per-region hue — a region keeps its colour for as long as it exists. */
    static float[] colorFor(long regionId) {
        int hash = Long.hashCode(regionId * 0x9E3779B97F4A7C15L);
        float hue = (hash & 0xFFFFFF) / (float) 0xFFFFFF;
        int rgb = Color.HSBtoRGB(hue, 0.65F, 1.0F);
        return new float[] {((rgb >> 16) & 0xFF) / 255F, ((rgb >> 8) & 0xFF) / 255F, (rgb & 0xFF) / 255F};
    }
}
