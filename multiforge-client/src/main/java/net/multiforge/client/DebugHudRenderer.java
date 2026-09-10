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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Draws the mod's three on-screen panels:
 *
 * <ul>
 *   <li><b>Summary</b> (top-left) — the server's handshake, live region
 *       count, an approximate worker count, worst region MSPT p95, and
 *       a rolling warn count.
 *   <li><b>Region list</b> (top-left, under the summary) — one row per
 *       live region. The data for this has been produced by {@link
 *       DebugHudState#f3Lines()} and documented in {@code
 *       docs/debugging-violations.md} since M6, but nothing rendered it
 *       until v1.4.0.
 *   <li><b>Violations</b> (right edge) — the live reroute/warn feed.
 *       Advertised in {@code neoforge.mods.toml}, in this mod's own
 *       javadoc, and in {@code docs/debugging-violations.md} since M6;
 *       through v1.3.18 the events were collected into a 200-entry ring
 *       and only their <em>count</em> was ever drawn.
 * </ul>
 *
 * <p>Every panel is individually switchable via {@link
 * MultiForgeDebugConfig} and all of them are gated behind the F6 master
 * toggle.
 *
 * <p>The line builders are static and pure so they can be unit-tested
 * without a Minecraft runtime — see {@code DebugHudRendererTest}.
 */
public final class DebugHudRenderer {

    private static final int LINE_HEIGHT = 10;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final int WARN_COLOR = 0xFFB4B4;
    private static final int MARGIN = 2;

    /** Longest violation detail we will draw before ellipsising. */
    private static final int VIOLATION_DETAIL_MAX = 48;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

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
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;

        int y = MARGIN;
        // v1.3.16: top-left, not bottom-left.
        if (MultiForgeDebugConfig.HUD.get()) {
            for (String line : buildSummaryLines(state)) {
                graphics.drawString(font, line, MARGIN, y, TEXT_COLOR);
                y += LINE_HEIGHT;
            }
        }
        if (MultiForgeDebugConfig.REGION_LIST.get()) {
            for (String line : buildRegionLines(state, MultiForgeDebugConfig.REGION_LIST_MAX_ROWS.get())) {
                graphics.drawString(font, line, MARGIN, y, TEXT_COLOR);
                y += LINE_HEIGHT;
            }
        }

        if (MultiForgeDebugConfig.VIOLATIONS.get()) {
            List<String> rows = buildViolationLines(state, MultiForgeDebugConfig.VIOLATION_MAX_ROWS.get());
            int rowY = MARGIN;
            int screenWidth = graphics.guiWidth();
            for (String row : rows) {
                int x = screenWidth - MARGIN - font.width(row);
                graphics.drawString(font, row, x, rowY, WARN_COLOR);
                rowY += LINE_HEIGHT;
            }
        }
    }

    /**
     * Top-left status block. Empty until a {@code HELLO} arrives, so
     * the mod draws nothing at all on a vanilla or non-MultiForge
     * server.
     */
    static List<String> buildSummaryLines(DebugHudState state) {
        if (state.protocolUnsupported()) {
            // Protocol §8: we refused to subscribe, so no other panel
            // will ever populate. Say why rather than looking broken.
            return List.of(String.format(
                    "MultiForge debug: server protocol %d unsupported by this client", state.serverProtocol()));
        }
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

    /**
     * One row per live region, capped at {@code maxRows}. A truncation
     * row is appended when there are more regions than fit, so the
     * operator can tell the list is partial.
     */
    static List<String> buildRegionLines(DebugHudState state, int maxRows) {
        if (state.hello() == null || state.latestSnapshot() == null) {
            return List.of();
        }
        List<String> all = new ArrayList<>(state.f3Lines().values());
        if (all.size() <= maxRows) {
            return all;
        }
        List<String> out = new ArrayList<>(all.subList(0, maxRows));
        out.add(String.format("… %d more region(s)", all.size() - maxRows));
        return out;
    }

    /**
     * Newest-first violation rows, capped at {@code maxRows}. Format:
     * {@code HH:mm:ss [modId] site — detail}, with the detail
     * ellipsised so one violation cannot push the panel off-screen.
     */
    static List<String> buildViolationLines(DebugHudState state, int maxRows) {
        List<DebugPayload.ViolationEvent> events = state.recentViolations();
        if (events.isEmpty()) {
            return List.of();
        }
        int count = Math.min(maxRows, events.size());
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            DebugPayload.ViolationEvent e = events.get(i);
            out.add(String.format(
                    "%s [%s] %s — %s",
                    TIME_FORMAT.format(Instant.ofEpochMilli(e.epochMillis())),
                    e.modId(),
                    e.site(),
                    truncate(e.detail())));
        }
        return out;
    }

    private static String truncate(String detail) {
        if (detail.length() <= VIOLATION_DETAIL_MAX) return detail;
        return detail.substring(0, VIOLATION_DETAIL_MAX - 1) + "…";
    }
}
