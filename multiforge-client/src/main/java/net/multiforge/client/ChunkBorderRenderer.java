/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
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
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws full-height chunk-boundary pillars around the player, tinted
 * per swatch from the live region ids in the most recent {@code
 * REGION_SNAPSHOT} (protocol §7.2).
 *
 * <p><b>Known limitation:</b> {@code REGION_SNAPSHOT} carries no
 * per-chunk region membership -- {@code RegionId} is an opaque
 * monotonically-increasing counter, not a spatial key (see {@code
 * net.multiforge.runtime.region.RegionId}), and regions themselves are
 * irregular, dynamically grown/merged/split sets of sections rather
 * than a fixed grid (see {@code net.multiforge.runtime.region.Region}).
 * The client therefore cannot reconstruct which chunk truly belongs to
 * which region from the wire alone. Until a future, backward-compatible
 * protocol revision adds a per-chunk mapping (e.g. a new packet kind at
 * one of the {@code 0x06}-{@code 0x0F} reserved ids in protocol §2),
 * this renderer assigns colours to the visible chunk grid by hashing
 * each chunk's own coordinate against the pool of currently-live region
 * ids. That produces a stable, visually distinct grid per session --
 * useful for eyeballing region churn -- but it is a placeholder, not a
 * ground-truth region boundary. Flagged for the protocol owner.
 */
public final class ChunkBorderRenderer {

    /** Chunks drawn in each direction from the player; keeps the line count modest. */
    private static final int RADIUS_CHUNKS = 4;

    private static final float LINE_ALPHA = 0.6F;

    private final DebugHudState state;

    ChunkBorderRenderer(DebugHudState state) {
        this.state = state;
    }

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
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
        VertexConsumer consumer = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        ChunkPos center = player.chunkPosition();

        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                int chunkX = center.x + dx;
                int chunkZ = center.z + dz;
                long regionId = pickRegionId(snapshot.regions(), chunkX, chunkZ);
                float[] rgb = colorFor(regionId);

                double originX = chunkX * 16.0;
                double originZ = chunkZ * 16.0;
                Vec3 offset = new Vec3(originX - camPos.x, minY - camPos.y, originZ - camPos.z);
                AABB local = new AABB(0, 0, 0, 16, maxY - minY, 16);

                poseStack.pushPose();
                poseStack.translate(offset.x, offset.y, offset.z);
                LevelRenderer.renderLineBox(poseStack, consumer, local, rgb[0], rgb[1], rgb[2], LINE_ALPHA);
                poseStack.popPose();
            }
        }
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
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
