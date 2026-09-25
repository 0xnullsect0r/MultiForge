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
package net.multiforge.runtime.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshot of the operator-visible knobs from
 * {@code multiforge-server.toml}. Every field is immutable; a change
 * from an in-game command produces a new snapshot published to
 * {@link MultiForgeConfigStore} subscribers.
 *
 * <p>Layout:
 * <pre>
 *   [mtserver]
 *   cores = 8               # cores × threadsPerCore = tick worker count
 *   threadsPerCore = 2
 *   mode = "hybrid"         # off | hybrid | strict
 *
 *   [region]
 *   size = 4                # log2 of chunks per section side (0..8); 4 = 16 chunks
 *
 *   [violations]
 *   policy = "warn"         # warn | reroute-only | fail
 *   warnPerMin = 5          # warnings per minute per violation site
 *
 *   [persistence]
 *   autosaveTicks = 6000    # per-region journal autosave interval, in ticks
 * </pre>
 */
public record MultiForgeConfig(
        int cores,
        int threadsPerCore,
        Mode mode,
        int regionSize,
        ViolationPolicy violationPolicy,
        int warnPerMin,
        long autosaveTicks) {

    public enum Mode {
        OFF,
        HYBRID,
        STRICT
    }

    /**
     * What happens when code on a region worker mutates a chunk its region
     * does not own (see {@code OwnershipEnforcer}).
     */
    public enum ViolationPolicy {
        /** Reroute the mutation to the owning region and log a rate-limited warning. */
        WARN,
        /** Reroute silently (the probe counter still records it). */
        REROUTE_ONLY,
        /** Throw — the same as {@code mode = "strict"} for ownership. */
        FAIL
    }

    private static final Logger LOG = LoggerFactory.getLogger("multiforge.config");

    /**
     * Set once the first time {@link #tickWorkerCount()} honours the
     * {@code -Dmultiforge.workers=N} override, so the "override active"
     * notice is logged exactly once per JVM rather than once per tick.
     */
    private static final AtomicBoolean WORKERS_OVERRIDE_LOGGED = new AtomicBoolean(false);

    /**
     * Worker count for the tick-region scheduler. {@code cores *
     * threadsPerCore} from {@code multiforge.toml}, unless
     * {@code -Dmultiforge.workers=N} is set on the JVM command line, in
     * which case that value short-circuits the TOML-derived computation
     * entirely (see Phase 7 runbook §7 — lets 7.3's N-worker verification
     * run flex worker count without editing {@code multiforge.toml}).
     *
     * <p>An unparsable or non-positive override (blank, non-numeric,
     * zero, negative) is not a meaningful worker count, so it silently
     * falls through to the normal {@code cores * threadsPerCore}
     * computation rather than throwing or clamping to 1.
     */
    public int tickWorkerCount() {
        String override = System.getProperty("multiforge.workers");
        if (override != null && !override.isBlank()) {
            try {
                int n = Integer.parseInt(override.trim());
                if (n > 0) {
                    if (WORKERS_OVERRIDE_LOGGED.compareAndSet(false, true)) {
                        LOG.info(
                                "multiforge.workers override active: using {} tick worker(s) "
                                        + "instead of the computed cores({}) * threadsPerCore({})",
                                n,
                                cores,
                                threadsPerCore);
                    }
                    return n;
                }
                // 0 or negative — not a meaningful worker count, fall through.
            } catch (NumberFormatException ignored) {
                // Invalid value — fall through to the computed default.
            }
        }
        return Math.max(1, cores * threadsPerCore);
    }

    /**
     * The mode the server actually runs in: {@code -Dmultiforge.mode=off|hybrid|strict}
     * when set (so an operator can switch MultiForge off, or into strict
     * regression mode, without editing the file), otherwise {@link #mode()}.
     * An unrecognised property value is ignored.
     *
     * <ul>
     * <li>{@link Mode#OFF} — the regionized runtime is not installed; the
     *     server runs Vanilla's single-threaded tick.</li>
     * <li>{@link Mode#HYBRID} — regions tick in parallel; an ownership
     *     violation is rerouted to its owner with a rate-limited warning.</li>
     * <li>{@link Mode#STRICT} — as hybrid, but ownership violations and
     *     region-tick overruns throw (regression runs).</li>
     * </ul>
     */
    public Mode effectiveMode() {
        String override = System.getProperty("multiforge.mode");
        if (override != null && !override.isBlank()) {
            try {
                return Mode.valueOf(override.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                LOG.warn("ignoring unrecognised -Dmultiforge.mode={} (expected off, hybrid or strict)", override);
            }
        }
        return mode;
    }

    public static MultiForgeConfig defaults() {
        return new MultiForgeConfig(
                Runtime.getRuntime().availableProcessors(),
                1,
                Mode.HYBRID,
                4, // 2^4 = 16 chunks per section side (Folia default)
                ViolationPolicy.WARN,
                5,
                6000L); // Vanilla's autosave cadence: 5 minutes at 20 TPS
    }

    /** Builder-style with-methods so /multiforge commands can produce a new snapshot. */
    public MultiForgeConfig withCores(int v) {
        return new MultiForgeConfig(v, threadsPerCore, mode, regionSize, violationPolicy, warnPerMin, autosaveTicks);
    }

    public MultiForgeConfig withThreadsPerCore(int v) {
        return new MultiForgeConfig(cores, v, mode, regionSize, violationPolicy, warnPerMin, autosaveTicks);
    }

    public MultiForgeConfig withMode(Mode v) {
        return new MultiForgeConfig(cores, threadsPerCore, v, regionSize, violationPolicy, warnPerMin, autosaveTicks);
    }

    public MultiForgeConfig withRegionSize(int v) {
        return new MultiForgeConfig(cores, threadsPerCore, mode, v, violationPolicy, warnPerMin, autosaveTicks);
    }

    public MultiForgeConfig withViolationPolicy(ViolationPolicy v) {
        return new MultiForgeConfig(cores, threadsPerCore, mode, regionSize, v, warnPerMin, autosaveTicks);
    }

    public MultiForgeConfig withWarnPerMin(int v) {
        return new MultiForgeConfig(cores, threadsPerCore, mode, regionSize, violationPolicy, v, autosaveTicks);
    }

    public MultiForgeConfig withAutosaveTicks(long v) {
        return new MultiForgeConfig(cores, threadsPerCore, mode, regionSize, violationPolicy, warnPerMin, v);
    }
}
