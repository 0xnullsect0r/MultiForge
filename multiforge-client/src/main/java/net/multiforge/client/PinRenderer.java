/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Renders a camera-facing billboard label above each operator-created
 * region pin from {@code PIN_LIST} (protocol §7.4), so pins are visible
 * in the world without opening a separate UI.
 *
 * <p>Positioned at the horizontal centre of the pin's chunk range,
 * following the player's height, using the same billboard technique
 * vanilla uses for entity name tags ({@code EntityRenderer#renderNameTag}):
 * translate to world position relative to the camera, rotate to face
 * the camera, then scale down before drawing text.
 */
public final class PinRenderer {

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

        for (DebugPayload.PinBox pin : pins) {
            if (!pin.worldId().equals(currentWorld)) {
                continue;
            }
            double centerChunkX = (pin.fromChunkX() + pin.toChunkX()) / 2.0;
            double centerChunkZ = (pin.fromChunkZ() + pin.toChunkZ()) / 2.0;
            double centerX = centerChunkX * 16.0 + 8.0;
            double centerZ = centerChunkZ * 16.0 + 8.0;
            double labelY = player.getY() + LABEL_HEIGHT_ABOVE_PLAYER;

            String label = pin.id();
            float halfWidth = font.width(label) / 2.0F;

            poseStack.pushPose();
            poseStack.translate(centerX - camPos.x, labelY - camPos.y, centerZ - camPos.z);
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
        bufferSource.endBatch();
    }
}
