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
import net.minecraft.world.phys.Vec3;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Blends a translucent per-chunk tint over the ground near the player,
 * coloured by {@code HEATMAP_UPDATE}'s per-chunk MSPT delta (protocol
 * §7.3): green below 5ms, yellow below 20ms, red at/above 50ms,
 * interpolated between those anchors.
 *
 * <p>Drawn as a flat quad just below the player's feet rather than
 * following terrain height, since the heated chunks reflect the
 * <em>server's</em> view-radius filtering, not necessarily chunks the
 * client itself has loaded (and thus can query a heightmap for).
 */
public final class HeatmapRenderer {

    private static final float GREEN_MAX_MSPT = 5.0F;
    private static final float YELLOW_MAX_MSPT = 20.0F;
    private static final float RED_MSPT = 50.0F;
    private static final int ALPHA = 110;
    private static final double FEET_OFFSET = 0.05;

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
        float y = (float) (player.getY() - FEET_OFFSET);

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        // v1.4.0: all three axes, not just X/Z. RenderLevelStageEvent's
        // pose is camera-relative, so a vertex given in absolute world
        // space has to be offset by the full camera position. Leaving Y
        // un-translated (v1.3.5–v1.3.18) put every quad at world
        // Y = camY + playerY — a sheet of coloured squares roughly 64
        // blocks over the player's head instead of under their feet.
        // Matches ChunkBorderRenderer and PinRenderer, which always
        // subtracted camPos.y.
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f pose = poseStack.last().pose();

        VertexConsumer consumer = mc.renderBuffers().bufferSource().getBuffer(RenderType.debugQuads());
        for (DebugPayload.ChunkHeat heat : heatmap.heats()) {
            int[] rgba = colorFor(heat.heatMspt());
            float x0 = heat.chunkX() * 16;
            float z0 = heat.chunkZ() * 16;
            float x1 = x0 + 16;
            float z1 = z0 + 16;
            consumer.addVertex(pose, x0, y, z0).setColor(rgba[0], rgba[1], rgba[2], rgba[3]);
            consumer.addVertex(pose, x0, y, z1).setColor(rgba[0], rgba[1], rgba[2], rgba[3]);
            consumer.addVertex(pose, x1, y, z1).setColor(rgba[0], rgba[1], rgba[2], rgba[3]);
            consumer.addVertex(pose, x1, y, z0).setColor(rgba[0], rgba[1], rgba[2], rgba[3]);
        }
        mc.renderBuffers().bufferSource().endBatch(RenderType.debugQuads());
        poseStack.popPose();
    }

    private static int[] colorFor(float mspt) {
        float r;
        float g;
        if (mspt <= GREEN_MAX_MSPT) {
            r = 0F;
            g = 1F;
        } else if (mspt <= YELLOW_MAX_MSPT) {
            float t = (mspt - GREEN_MAX_MSPT) / (YELLOW_MAX_MSPT - GREEN_MAX_MSPT);
            r = t;
            g = 1F;
        } else {
            float t = Math.min(1F, (mspt - YELLOW_MAX_MSPT) / (RED_MSPT - YELLOW_MAX_MSPT));
            r = 1F;
            g = 1F - t;
        }
        return new int[] {(int) (r * 255), (int) (g * 255), 0, ALPHA};
    }
}
