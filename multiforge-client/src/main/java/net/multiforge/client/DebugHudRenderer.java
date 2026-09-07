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

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Appends MultiForge diagnostics to the vanilla F3 debug screen: the
 * server's handshake ({@code HELLO}), live region count, an
 * approximate worker count, the worst region MSPT p95 across the
 * fleet, and a running warn count.
 *
 * <p>Only draws while {@code DebugScreenOverlay.showDebugScreen()} is
 * on (the player has F3 open) and only once {@link DebugHudState#hello()}
 * has been populated -- before the first {@code HELLO} frame there is
 * nothing MultiForge-specific to show.
 */
public final class DebugHudRenderer {

    private static final int LINE_HEIGHT = 10;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final int MARGIN = 2;

    private final DebugHudState state;

    DebugHudRenderer(DebugHudState state) {
        this.state = state;
    }

    @SubscribeEvent
    public void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (!state.overlaysEnabled()) {
            return;
        }
        // v1.3.16: dropped the mc.getDebugOverlay().showDebugScreen() gate.
        // HUD is now always visible when overlays are on and a HELLO frame
        // has arrived — matches what docs/client-mod-guide.md §2.1
        // promises. Old behavior only showed the HUD while F3 was held.
        Minecraft mc = Minecraft.getInstance();
        List<String> lines = buildLines(state);
        if (lines.isEmpty()) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;
        // v1.3.16: top-left, not bottom-left.
        int y = MARGIN;
        for (String line : lines) {
            graphics.drawString(font, line, MARGIN, y, TEXT_COLOR);
            y += LINE_HEIGHT;
        }
    }

    private static List<String> buildLines(DebugHudState state) {
        DebugPayload.Hello hello = state.hello();
        if (hello == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(3);
        lines.add(String.format(
                "MultiForge build=%s proto=%d tickHz=%d", hello.buildLabel(), hello.protocolVersion(), hello.tickHz()));

        DebugPayload.RegionSnapshot snapshot = state.latestSnapshot();
        if (snapshot != null) {
            int regionCount = snapshot.regions().size();
            double worstP95 = 0.0;
            for (DebugPayload.RegionStat region : snapshot.regions()) {
                worstP95 = Math.max(worstP95, region.msptP95());
            }
            // The wire protocol carries no dedicated worker-thread-count
            // field (docs/design/client-debug-protocol.md §7.2). MultiForge's
            // regionized scheduler runs roughly one worker per live, ticking
            // region, so region count is the best available proxy -- an
            // approximation, not a literal live thread count.
            double tpsEstimate = worstP95 > 0 ? Math.min(hello.tickHz(), 1000.0 / worstP95) : hello.tickHz();
            lines.add(String.format(
                    "MultiForge regions=%d workers~=%d tps~=%.1f", regionCount, regionCount, tpsEstimate));
            // recentViolations() is capped at DebugHudState.VIOLATION_HISTORY
            // (200) -- a rolling window, not a since-connect total.
            lines.add(String.format(
                    "MultiForge worstP95=%.1fms warns=%d(last 200)",
                    worstP95, state.recentViolations().size()));
        }
        return lines;
    }
}
