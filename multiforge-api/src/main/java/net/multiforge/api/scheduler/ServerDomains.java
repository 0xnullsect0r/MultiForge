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
package net.multiforge.api.scheduler;

import java.util.Iterator;
import java.util.ServiceLoader;
import net.multiforge.api.entity.EntityRef;
import net.multiforge.api.spi.SchedulerHost;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.jetbrains.annotations.ApiStatus;

/**
 * Native MultiForge scheduler façade. Prefer these over the Folia-shaped
 * mirrors in {@link net.multiforge.api.folia}; both delegate to the
 * same {@link SchedulerHost}.
 *
 * <pre>{@code
 * ServerDomains.region(world, chunkPos).execute(myMod, () -> world.setBlock(...));
 * ServerDomains.entity(entity).run(myMod, task -> entity.hurt(1), () -> log.info("gone"));
 * ServerDomains.global().runDelayed(myMod, task -> broadcast("dawn"), 20 * 10L);
 * ServerDomains.async().runNow(myMod, task -> pathfind());
 * }</pre>
 */
public final class ServerDomains {

    private static volatile SchedulerHost HOST;

    private ServerDomains() {}

    public static RegionDomain region(WorldRef world, ChunkPos pos) {
        return host().region(world, pos);
    }

    public static EntityDomain entity(EntityRef entity) {
        return host().entity(entity);
    }

    public static GlobalDomain global() {
        return host().global();
    }

    public static AsyncDomain async() {
        return host().async();
    }

    /**
     * Runtime-only. The MultiForge server binds its {@link
     * SchedulerHost} implementation via this method during boot. Mods
     * must not call this — it is guarded so a second call throws
     * unless the caller passes the same instance already installed.
     */
    @ApiStatus.Internal
    public static void install(SchedulerHost host) {
        if (host == null) throw new NullPointerException("host");
        SchedulerHost current = HOST;
        if (current != null && current != host) {
            throw new IllegalStateException(
                    "SchedulerHost already installed (" + current.getClass().getName() + "); refusing to replace with "
                            + host.getClass().getName());
        }
        HOST = host;
    }

    /**
     * Unbind the currently-installed host. Called during MultiForge's
     * server shutdown so a subsequent {@link #install} on server
     * restart succeeds cleanly. Idempotent.
     *
     * <p>Runtime-only; must not be called from mod code (would strand
     * every other mod's dispatch on the fallback path — /67 round-4
     * finding B9). Marked {@code @ApiStatus.Internal} to signal the
     * boundary.
     */
    @ApiStatus.Internal
    public static void uninstall() {
        HOST = null;
    }

    /** Test-only alias for {@link #uninstall()}; kept for source compatibility. */
    public static void resetForTesting() {
        uninstall();
    }

    private static SchedulerHost host() {
        SchedulerHost h = HOST;
        if (h != null) return h;
        synchronized (ServerDomains.class) {
            h = HOST;
            if (h != null) return h;
            Iterator<SchedulerHost> it = ServiceLoader.load(SchedulerHost.class).iterator();
            if (!it.hasNext()) {
                throw new IllegalStateException(
                        "No SchedulerHost is installed. This mod is running against MultiForge's API but no runtime "
                                + "binding is present on the classpath. Are you running vanilla NeoForge instead of MultiForge?");
            }
            SchedulerHost first = it.next();
            if (it.hasNext()) {
                throw new IllegalStateException("Multiple SchedulerHost service bindings on classpath");
            }
            HOST = first;
            return first;
        }
    }
}
