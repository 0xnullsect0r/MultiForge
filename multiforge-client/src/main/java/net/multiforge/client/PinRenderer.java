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
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Renders each operator-created region pin as a labelled bounding
 * box in-world. Same configurable Y-band as {@link ChunkBorderRenderer}
 * (dodges z-fighting at high altitudes; pre-v1.3.16 code drew only a
 * floating billboard label with no box at all, and the docs
 * incorrectly claimed a box was drawn).
 */
public final class PinRenderer {

    private static final float LINE_ALPHA = 0.85F;
    private static final float BOX_RED = 1.0F;
    private static final float BOX_GREEN = 0.85F;
    private static final float BOX_BLUE = 0.2F;

    private static final float LABEL_HEIGHT_ABOVE_PLAYER = 3.0F;
    private static final float SCALE = 0.025F;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int BACKGROUND_COLOR = 0x40000000;

    private final DebugHudState state;

    PinRenderer(DebugHudState state) {
        this.state = state;
    }

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!state.overlaysEnabled() || !MultiForgeDebugConfig.PINS.get()) {
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
        List<DebugPayload.PinBox> pins = state.pins();
        if (pins.isEmpty()) {
            return;
        }
        String currentWorld = level.dimension().location().toString();

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        Font font = mc.font;
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        double playerY = player.getY();
        double yLow = Mth.clamp(
                playerY - MultiForgeDebugConfig.Y_BELOW.get(), level.getMinBuildHeight(), level.getMaxBuildHeight());
        double yHigh = Mth.clamp(
                playerY + MultiForgeDebugConfig.Y_ABOVE.get(), level.getMinBuildHeight(), level.getMaxBuildHeight());

        for (DebugPayload.PinBox pin : pins) {
            if (!pin.worldId().equals(currentWorld)) {
                continue;
            }
            // Box: chunks (from) through (to) inclusive; (to+1) since chunk edges are exclusive.
            double x0 = pin.fromChunkX() * 16.0 - camPos.x;
            double z0 = pin.fromChunkZ() * 16.0 - camPos.z;
            double x1 = (pin.toChunkX() + 1) * 16.0 - camPos.x;
            double z1 = (pin.toChunkZ() + 1) * 16.0 - camPos.z;
            double y0 = yLow - camPos.y;
            double y1 = yHigh - camPos.y;

            var consumer = bufferSource.getBuffer(RenderType.lines());
            poseStack.pushPose();
            LevelRenderer.renderLineBox(
                    poseStack, consumer, new AABB(x0, y0, z0, x1, y1, z1), BOX_RED, BOX_GREEN, BOX_BLUE, LINE_ALPHA);
            poseStack.popPose();

            // Billboard label above the box's NE-top corner so it's easy
            // to correlate the label with the pin's rectangle.
            double labelX = (pin.toChunkX() + 1) * 16.0;
            double labelZ = pin.fromChunkZ() * 16.0;
            double labelY = playerY + LABEL_HEIGHT_ABOVE_PLAYER;

            String label = pin.id();
            float halfWidth = font.width(label) / 2.0F;

            poseStack.pushPose();
            poseStack.translate(labelX - camPos.x, labelY - camPos.y, labelZ - camPos.z);
            poseStack.mulPose(event.getCamera().rotation());
            poseStack.scale(SCALE, -SCALE, SCALE);
            Matrix4f matrix = poseStack.last().pose();
            font.drawInBatch(
                    label,
                    -halfWidth,
                    0F,
                    TEXT_COLOR,
                    false,
                    matrix,
                    bufferSource,
                    Font.DisplayMode.NORMAL,
                    BACKGROUND_COLOR,
                    LightTexture.FULL_BRIGHT);
            poseStack.popPose();
        }
        bufferSource.endBatch(RenderType.lines());
        bufferSource.endBatch();
    }
}
