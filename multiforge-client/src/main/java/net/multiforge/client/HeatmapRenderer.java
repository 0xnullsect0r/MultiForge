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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.multiforge.runtime.diagnostics.wire.DebugPacketCodec;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Blends a translucent per-chunk tint over the ground near the player,
 * coloured by {@code HEATMAP_UPDATE} (protocol §7.3).
 *
 * <p>From a protocol-4 server (v1.9.0) the value is the chunk's own tick
 * time: green below 0.1 ms, yellow at 1 ms, red at 5 ms and above. From an
 * older server it is the owning region's average, on the old scale: green
 * below 5 ms, yellow at 20 ms, red at 50 ms. Colours interpolate between the
 * anchors.
 *
 * <p>Each chunk is drawn as 4×4-block cells laid on the terrain surface
 * (the client's motion-blocking heightmap), for chunks the client has
 * loaded within {@link #MAX_RADIUS_CHUNKS} of the player. Until v1.7.1 it
 * was one flat sheet per chunk at the player's feet, which floated over
 * low ground, vanished under high ground and moved up and down with the
 * player.
 */
public final class HeatmapRenderer {

    /** Per-chunk scale (protocol 4+): ms per tick one chunk costs. */
    static final float[] CHUNK_SCALE = {0.1F, 1.0F, 5.0F};
    /** Region-average scale (older servers): ms per tick of the whole region. */
    static final float[] REGION_SCALE = {5.0F, 20.0F, 50.0F};

    private static final int ALPHA = 110;
    /** Lifts each cell just above the surface so it does not z-fight the ground. */
    private static final double SURFACE_OFFSET = 0.05;
    /** Cell size in blocks: a chunk is drawn as (16 / CELL)² cells following the terrain. */
    private static final int CELL = 4;
    /** Chunks drawn in each direction from the player, at most. */
    private static final int MAX_RADIUS_CHUNKS = 12;

    private final DebugHudState state;

    HeatmapRenderer(DebugHudState state) {
        this.state = state;
    }

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!state.overlaysEnabled() || !MultiForgeDebugConfig.HEATMAP.get()) {
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
        DebugPayload.HeatmapUpdate heatmap = state.latestHeatmap();
        if (heatmap == null || heatmap.heats().isEmpty()) {
            return;
        }
        if (!heatmap.worldId().equals(level.dimension().location().toString())) {
            return;
        }

        Vec3 camPos = event.getCamera().getPosition();
        int playerChunkX = player.chunkPosition().x;
        int playerChunkZ = player.chunkPosition().z;
        int radius = Math.min(MAX_RADIUS_CHUNKS, mc.options.getEffectiveRenderDistance());

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        // RenderLevelStageEvent's pose is camera-relative, so vertices given in
        // world space are offset by the full camera position.
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f pose = poseStack.last().pose();

        float[] scale = scaleFor(state.serverProtocol());
        VertexConsumer consumer = mc.renderBuffers().bufferSource().getBuffer(RenderType.debugQuads());
        for (DebugPayload.ChunkHeat heat : heatmap.heats()) {
            if (Math.abs(heat.chunkX() - playerChunkX) > radius || Math.abs(heat.chunkZ() - playerChunkZ) > radius) {
                continue;
            }
            if (!level.hasChunk(heat.chunkX(), heat.chunkZ())) {
                continue;
            }
            int[] rgba = colorFor(heat.heatMspt(), scale);
            int bx = heat.chunkX() * 16;
            int bz = heat.chunkZ() * 16;
            for (int cx = 0; cx < 16; cx += CELL) {
                for (int cz = 0; cz < 16; cz += CELL) {
                    // Surface height at the cell's centre; the cell lies just above it.
                    float y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx + cx + CELL / 2, bz + cz + CELL / 2)
                            + (float) SURFACE_OFFSET;
                    float x0 = bx + cx;
                    float z0 = bz + cz;
                    float x1 = x0 + CELL;
                    float z1 = z0 + CELL;
                    consumer.addVertex(pose, x0, y, z0).setColor(rgba[0], rgba[1], rgba[2], rgba[3]);
                    consumer.addVertex(pose, x0, y, z1).setColor(rgba[0], rgba[1], rgba[2], rgba[3]);
                    consumer.addVertex(pose, x1, y, z1).setColor(rgba[0], rgba[1], rgba[2], rgba[3]);
                    consumer.addVertex(pose, x1, y, z0).setColor(rgba[0], rgba[1], rgba[2], rgba[3]);
                }
            }
        }
        mc.renderBuffers().bufferSource().endBatch(RenderType.debugQuads());
        poseStack.popPose();
    }

    /** The colour scale for what a server speaking {@code protocol} puts in the heat slot. */
    static float[] scaleFor(int protocol) {
        return protocol >= DebugPacketCodec.PER_CHUNK_HEAT_PROTOCOL ? CHUNK_SCALE : REGION_SCALE;
    }

    /** RGBA for {@code mspt} on {@code scale} = {green up to, yellow at, red at}. */
    static int[] colorFor(float mspt, float[] scale) {
        float green = scale[0];
        float yellow = scale[1];
        float red = scale[2];
        float r;
        float g;
        if (mspt <= green) {
            r = 0F;
            g = 1F;
        } else if (mspt <= yellow) {
            float t = (mspt - green) / (yellow - green);
            r = t;
            g = 1F;
        } else {
            float t = Math.min(1F, (mspt - yellow) / (red - yellow));
            r = 1F;
            g = 1F - t;
        }
        return new int[] {(int) (r * 255), (int) (g * 255), 0, ALPHA};
    }
}
