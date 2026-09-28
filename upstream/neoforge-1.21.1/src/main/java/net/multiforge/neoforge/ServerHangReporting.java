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
package net.multiforge.neoforge;

import net.minecraft.server.MinecraftServer;
import net.multiforge.runtime.diagnostics.HangReporter;
import org.jetbrains.annotations.ApiStatus;

/**
 * Starts the {@link HangReporter} for a server once its tick loop begins
 * ({@code ServerLifecycleHooks.handleServerStarted}, on the server thread). It
 * reads {@code MinecraftServer.mfTickStartNanos}, the heartbeat the patched
 * {@code ServerWatchdog} also measures from, and stops with the server. It
 * only logs, so it runs in every mode, {@code off} included; {@code
 * -Dmultiforge.hangReport=false} turns it off.
 */
@ApiStatus.Internal
public final class ServerHangReporting {
    private ServerHangReporting() {}

    public static void start(MinecraftServer server) {
        if (!HangReporter.enabled()) return;
        HangReporter.forServer(Thread.currentThread(), server::getNextTickTime, () -> server.mfTickStartNanos)
                .startDaemon("multiforge-hang-reporter", server::isRunning);
    }
}
