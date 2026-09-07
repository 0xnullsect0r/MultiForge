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
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws vertical strips only along the seams where two adjacent
 * chunks belong to (hash-picked) different regions. Iterates a 9×9
 * grid around the player, but instead of a full-height AABB per chunk
 * (v1.3.5–v1.3.15 behaviour, which produced corner-pillar farms and
 * z-fighting above clouds), it walks the north+east edge of each
 * chunk and draws a single line strip if the neighbour on that side
 * has a different colour. Y range is clamped to a 48-block band
 * around the player to keep the strips crisp and dodge depth-buffer
 * precision loss at high altitude.
 *
 * <p><b>Known limitation:</b> the region-per-chunk assignment is
 * hash-based, not real (protocol §7.2 doesn't carry per-chunk
 * ownership — see the v1.3.5–v1.3.15 Javadoc for the full story).
 * The seam view is still useful for eyeballing region churn, but a
 * seam that appears in-world is a hash artifact, not a truth.
 * Deferred to v1.4: a new packet kind carrying per-chunk region-ids.
 */
public final class ChunkBorderRenderer {

    /** Chunks drawn in each direction from the player; keeps the strip count modest. */
    private static final int RADIUS_CHUNKS = 4;

    /** Y-range around the player where seams are drawn. */
    private static final int Y_BELOW = 16;

    private static final int Y_ABOVE = 32;

    private static final float LINE_ALPHA = 0.75F;

    private final DebugHudState state;

    ChunkBorderRenderer(DebugHudState state) {
        this.state = state;
    }

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!state.overlaysEnabled()) {
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
        DebugPayload.RegionSnapshot snapshot = state.latestSnapshot();
        if (snapshot == null || snapshot.regions().isEmpty()) {
            return;
        }

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        ChunkPos center = player.chunkPosition();

        double playerY = player.getY();
        double yLow = Mth.clamp(playerY - Y_BELOW, level.getMinBuildHeight(), level.getMaxBuildHeight());
        double yHigh = Mth.clamp(playerY + Y_ABOVE, level.getMinBuildHeight(), level.getMaxBuildHeight());

        List<DebugPayload.RegionStat> regions = snapshot.regions();

        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                int chunkX = center.x + dx;
                int chunkZ = center.z + dz;
                long here = pickRegionId(regions, chunkX, chunkZ);

                // North seam: this chunk vs. (chunkX, chunkZ - 1).
                if (dz > -RADIUS_CHUNKS) {
                    long north = pickRegionId(regions, chunkX, chunkZ - 1);
                    if (north != here) {
                        drawSeamZ(poseStack, consumer, camPos, chunkX, chunkZ, yLow, yHigh, here);
                    }
                }

                // West seam: this chunk vs. (chunkX - 1, chunkZ).
                if (dx > -RADIUS_CHUNKS) {
                    long west = pickRegionId(regions, chunkX - 1, chunkZ);
                    if (west != here) {
                        drawSeamX(poseStack, consumer, camPos, chunkX, chunkZ, yLow, yHigh, here);
                    }
                }
            }
        }

        bufferSource.endBatch(RenderType.lines());
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

    private static long pickRegionId(List<DebugPayload.RegionStat> regions, int chunkX, int chunkZ) {
        int hash = Long.hashCode(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL));
        int index = Math.floorMod(hash, regions.size());
        return regions.get(index).regionId();
    }

    private static float[] colorFor(long regionId) {
        int hash = Long.hashCode(regionId * 0x9E3779B97F4A7C15L);
        float hue = (hash & 0xFFFFFF) / (float) 0xFFFFFF;
        int rgb = Color.HSBtoRGB(hue, 0.65F, 1.0F);
        return new float[] {((rgb >> 16) & 0xFF) / 255F, ((rgb >> 8) & 0xFF) / 255F, (rgb & 0xFF) / 255F};
    }
}
