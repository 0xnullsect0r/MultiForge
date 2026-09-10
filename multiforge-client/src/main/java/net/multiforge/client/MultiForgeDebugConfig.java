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

import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Per-overlay client configuration, stored in {@code
 * config/multiforge_debug-client.toml} and editable in-game from the
 * Mods screen (NeoForge auto-generates the screen from this spec — see
 * {@link MultiForgeDebugMod}).
 *
 * <p>Through v1.3.18 the mod had exactly one control: the F6 master
 * toggle, which hid every overlay at once and left the server pushing
 * all four streams regardless. The subscription mask has supported
 * per-stream opt-in since the protocol was frozen (§5) and nothing used
 * it. These toggles feed {@link SubscriptionManager}, so switching an
 * overlay off actually stops the server sending that stream rather than
 * just hiding what arrives.
 *
 * <p>All values are read through {@code ModConfigSpec.ConfigValue#get()}
 * on the render thread. That is a cheap volatile read of a cached
 * value, not a file access.
 */
public final class MultiForgeDebugConfig {

    public static final ModConfigSpec SPEC;

    // ---- overlays -------------------------------------------------------

    public static final ModConfigSpec.BooleanValue HUD;
    public static final ModConfigSpec.BooleanValue REGION_LIST;
    public static final ModConfigSpec.BooleanValue VIOLATIONS;
    public static final ModConfigSpec.BooleanValue CHUNK_BORDERS;
    public static final ModConfigSpec.BooleanValue HEATMAP;
    public static final ModConfigSpec.BooleanValue PINS;

    // ---- panel sizing ---------------------------------------------------

    public static final ModConfigSpec.IntValue REGION_LIST_MAX_ROWS;
    public static final ModConfigSpec.IntValue VIOLATION_MAX_ROWS;

    // ---- world rendering ------------------------------------------------

    public static final ModConfigSpec.IntValue BORDER_RADIUS_CHUNKS;
    public static final ModConfigSpec.IntValue Y_BELOW;
    public static final ModConfigSpec.IntValue Y_ABOVE;

    /** Root of every translation key this spec declares. */
    private static final String LANG = "multiforge_debug.configuration.";

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        // Every value and group gets an explicit translation key.
        // Without one, NeoForge's ConfigurationScreen falls back to
        // `<modId>.configuration.<leaf key>` (ConfigurationScreen:539) —
        // which is ambiguous here, because the boolean `overlays.hud`
        // and the group `hud` share the leaf name `hud` and would
        // resolve to the same key.
        b.comment("Which overlays to draw. Turning one off also stops the server sending its data stream.")
                .translation(LANG + "group.overlays")
                .push("overlays");
        HUD = b.comment("Top-left summary block: server build, region count, worst region MSPT.")
                .translation(LANG + "overlays.hud")
                .define("hud", true);
        REGION_LIST = b.comment("Top-left per-region detail rows, under the summary block.")
                .translation(LANG + "overlays.regionList")
                .define("regionList", true);
        VIOLATIONS = b.comment("Right-hand panel of live reroute/ownership warnings.")
                .translation(LANG + "overlays.violations")
                .define("violations", true);
        CHUNK_BORDERS = b.comment("In-world seams where two adjacent chunks belong to different regions.")
                .translation(LANG + "overlays.chunkBorders")
                .define("chunkBorders", true);
        HEATMAP = b.comment("Translucent per-chunk tick-cost tint on the ground.")
                .translation(LANG + "overlays.heatmap")
                .define("heatmap", true);
        PINS = b.comment("Wireframe boxes around operator-created region pins.")
                .translation(LANG + "overlays.pins")
                .define("pins", true);
        b.pop();

        b.comment("HUD panel sizing.").translation(LANG + "group.hud").push("hud");
        REGION_LIST_MAX_ROWS = b.comment("Maximum region rows to draw before truncating.")
                .translation(LANG + "hud.regionListMaxRows")
                .defineInRange("regionListMaxRows", 8, 1, 64);
        VIOLATION_MAX_ROWS = b.comment("Maximum violation rows to draw. The client retains 200 regardless.")
                .translation(LANG + "hud.violationMaxRows")
                .defineInRange("violationMaxRows", 8, 1, 64);
        b.pop();

        b.comment("In-world rendering extents.")
                .translation(LANG + "group.render")
                .push("render");
        BORDER_RADIUS_CHUNKS = b.comment("Chunks in each direction from the player to test for region seams.")
                .translation(LANG + "render.borderRadiusChunks")
                .defineInRange("borderRadiusChunks", 4, 1, 16);
        Y_BELOW = b.comment("Blocks below the player that seams and pin boxes extend.")
                .translation(LANG + "render.yBelow")
                .defineInRange("yBelow", 16, 0, 256);
        Y_ABOVE = b.comment("Blocks above the player that seams and pin boxes extend.")
                .translation(LANG + "render.yAbove")
                .defineInRange("yAbove", 32, 0, 256);
        b.pop();

        SPEC = b.build();
    }

    private MultiForgeDebugConfig() {}

    /**
     * The subscription mask these settings imply (protocol §5).
     *
     * <p>The summary and region-list panels both render {@code
     * REGION_SNAPSHOT}, so either one enables {@code F_REGIONS}.
     * {@code F_OWNERSHIP} exists only from protocol version 2, so
     * {@link SubscriptionManager} strips it when talking to an older
     * server rather than this method guessing at the peer's version.
     */
    public static int desiredMask() {
        int mask = 0;
        if (HUD.get() || REGION_LIST.get()) mask |= DebugPayload.Subscribe.F_REGIONS;
        if (HEATMAP.get()) mask |= DebugPayload.Subscribe.F_HEATMAP;
        if (PINS.get()) mask |= DebugPayload.Subscribe.F_PINS;
        if (VIOLATIONS.get()) mask |= DebugPayload.Subscribe.F_VIOLATIONS;
        if (CHUNK_BORDERS.get()) mask |= DebugPayload.Subscribe.F_OWNERSHIP;
        return mask;
    }
}
