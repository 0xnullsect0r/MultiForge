/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.commands;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.config.MultiForgeConfigStore;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.region.pin.RegionPin;
import net.multiforge.runtime.region.pin.RegionPinManager;

/**
 * Pure-Java argument parser + dispatcher for the /multiforge tree.
 * The M5 patch wires it to NeoForge's Brigadier command registration;
 * the dispatcher itself is dependency-free so its parsing is unit
 * testable.
 *
 * <p>Supported grammar:
 *
 * <pre>
 *   /multiforge config cores &lt;n&gt;
 *   /multiforge config threads &lt;n&gt;
 *   /multiforge region size &lt;chunks&gt;
 *   /multiforge region mode player-only|full-world
 *   /multiforge region pin &lt;id&gt; &lt;world&gt; &lt;fromCX&gt; &lt;fromCZ&gt; &lt;toCX&gt; &lt;toCZ&gt;
 *   /multiforge region unpin &lt;id&gt;
 *   /multiforge region list
 *   /multiforge probes           — dump all ProbeRegistry counters (diagnostics)
 *   /multiforge probes &lt;prefix&gt;  — dump counters whose key starts with prefix
 * </pre>
 */
public final class MultiForgeCommandDispatcher {

    private final MultiForgeConfigStore configStore;
    private final RegionPinManager pins;

    public MultiForgeCommandDispatcher(MultiForgeConfigStore configStore, RegionPinManager pins) {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.pins = Objects.requireNonNull(pins, "pins");
    }

    /**
     * Handle a parsed command. {@code output} receives one or more
     * lines of feedback; the caller sends those to the operator's
     * chat / console.
     *
     * @return true if the command succeeded, false on a usage error
     *         (usage lines are written to output).
     */
    public boolean dispatch(String[] args, Consumer<String> output) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(output, "output");
        if (args.length == 0) {
            output.accept("Usage: /multiforge <config|region|probes> ...");
            return false;
        }
        return switch (args[0]) {
            case "config" -> handleConfig(args, output);
            case "region" -> handleRegion(args, output);
            case "probes" -> handleProbes(args, output);
            default -> {
                output.accept("Unknown subcommand: " + args[0]);
                yield false;
            }
        };
    }

    /**
     * Dump {@link ProbeRegistry} counters — the diagnostic surface the
     * ownership guards, watchdog, and future M8 subsystems bump into
     * on race detection. Operators use this to answer "are we hitting
     * region-tick.overrun?" or "is Level.setBlock:off-thread growing?"
     * without needing to grep the server log.
     *
     * <p>{@code /multiforge probes} dumps every counter; {@code /multiforge
     * probes &lt;prefix&gt;} filters to keys starting with the given prefix
     * (e.g. {@code /multiforge probes region-tick} for just the watchdog
     * counters). Snapshot is sorted for stable operator-readable output.
     */
    private boolean handleProbes(String[] args, Consumer<String> output) {
        String prefix = args.length > 1 ? args[1] : "";
        Map<String, Long> snap = ProbeRegistry.snapshot();
        int matched = 0;
        for (Map.Entry<String, Long> e : snap.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            output.accept(e.getKey() + " = " + e.getValue());
            matched++;
        }
        if (matched == 0) {
            output.accept(prefix.isEmpty() ? "(no probes recorded)" : "(no probes matching prefix '" + prefix + "')");
        }
        return true;
    }

    private boolean handleConfig(String[] args, Consumer<String> output) {
        if (args.length < 3) {
            output.accept("Usage: /multiforge config <cores|threads> <n>");
            return false;
        }
        int n;
        try {
            n = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            output.accept("Not a number: " + args[2]);
            return false;
        }
        if (n < 1 || n > 4096) {
            output.accept("Value out of range: " + n);
            return false;
        }
        try {
            MultiForgeConfig next =
                    switch (args[1]) {
                        case "cores" -> configStore.update(c -> c.withCores(n));
                        case "threads" -> configStore.update(c -> c.withThreadsPerCore(n));
                        default -> {
                            output.accept("Unknown config key: " + args[1]);
                            yield null;
                        }
                    };
            if (next == null) return false;
            output.accept("Set " + args[1] + " = " + n + " (worker pool now " + next.tickWorkerCount() + " threads)");
            return true;
        } catch (IOException e) {
            output.accept("Failed to persist config: " + e.getMessage());
            return false;
        }
    }

    private boolean handleRegion(String[] args, Consumer<String> output) {
        if (args.length < 2) {
            output.accept("Usage: /multiforge region <size|mode|pin|unpin|list> ...");
            return false;
        }
        return switch (args[1]) {
            case "size" -> handleRegionSize(args, output);
            case "mode" -> handleRegionMode(args, output);
            case "pin" -> handlePin(args, output);
            case "unpin" -> handleUnpin(args, output);
            case "list" -> handleList(output);
            default -> {
                output.accept("Unknown region subcommand: " + args[1]);
                yield false;
            }
        };
    }

    private boolean handleRegionSize(String[] args, Consumer<String> output) {
        if (args.length < 3) {
            output.accept("Usage: /multiforge region size <chunks> (must be a power of 2 from 1..256)");
            return false;
        }
        int chunks;
        try {
            chunks = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            output.accept("Not a number: " + args[2]);
            return false;
        }
        if (chunks < 1 || chunks > 256 || Integer.bitCount(chunks) != 1) {
            output.accept("Size must be a power of 2 between 1 and 256");
            return false;
        }
        int shift = Integer.numberOfTrailingZeros(chunks);
        try {
            configStore.update(c -> c.withRegionSize(shift));
            output.accept("Region size set to " + chunks + " chunks per side (shift=" + shift + ")");
            return true;
        } catch (IOException e) {
            output.accept("Failed to persist: " + e.getMessage());
            return false;
        }
    }

    private boolean handleRegionMode(String[] args, Consumer<String> output) {
        if (args.length < 3) {
            output.accept("Usage: /multiforge region mode player-only|full-world");
            return false;
        }
        MultiForgeConfig.RegionMode mode;
        try {
            mode = MultiForgeConfig.RegionMode.valueOf(args[2].replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            output.accept("Unknown mode: " + args[2] + " (accepted: player-only, full-world)");
            return false;
        }
        try {
            configStore.update(c -> c.withRegionMode(mode));
            output.accept("Region mode set to " + args[2]);
            return true;
        } catch (IOException e) {
            output.accept("Failed to persist: " + e.getMessage());
            return false;
        }
    }

    private boolean handlePin(String[] args, Consumer<String> output) {
        if (args.length < 8) {
            output.accept("Usage: /multiforge region pin <id> <world> <fromCX> <fromCZ> <toCX> <toCZ>");
            return false;
        }
        String id = args[2];
        WorldRef world = WorldRef.of(args[3]);
        int fx, fz, tx, tz;
        try {
            fx = Integer.parseInt(args[4]);
            fz = Integer.parseInt(args[5]);
            tx = Integer.parseInt(args[6]);
            tz = Integer.parseInt(args[7]);
        } catch (NumberFormatException e) {
            output.accept("Chunk coordinates must be integers");
            return false;
        }
        try {
            RegionPin pin = pins.add(new RegionPin(id, world, fx, fz, tx, tz));
            pins.save();
            output.accept("Pinned " + pin.chunkCount() + " chunks as '" + pin.id() + "' in " + world.dimensionId());
            return true;
        } catch (IllegalStateException e) {
            output.accept(e.getMessage());
            return false;
        } catch (IOException e) {
            output.accept("Failed to persist pin: " + e.getMessage());
            return false;
        }
    }

    private boolean handleUnpin(String[] args, Consumer<String> output) {
        if (args.length < 3) {
            output.accept("Usage: /multiforge region unpin <id>");
            return false;
        }
        RegionPin removed = pins.remove(args[2]);
        if (removed == null) {
            output.accept("No such pin: " + args[2]);
            return false;
        }
        try {
            pins.save();
        } catch (IOException e) {
            output.accept("Removed from memory but failed to persist: " + e.getMessage());
            return false;
        }
        output.accept("Removed pin '" + removed.id() + "'");
        return true;
    }

    private boolean handleList(Consumer<String> output) {
        List<RegionPin> all = List.copyOf(pins.all());
        if (all.isEmpty()) {
            output.accept("No pinned regions.");
            return true;
        }
        output.accept("Pinned regions (" + all.size() + "):");
        for (RegionPin p : all) {
            output.accept("  " + p.id() + " " + p.world().dimensionId() + " ["
                    + p.fromChunkX() + "," + p.fromChunkZ() + "]..["
                    + p.toChunkX() + "," + p.toChunkZ() + "] (" + p.chunkCount() + " chunks)");
        }
        return true;
    }
}
