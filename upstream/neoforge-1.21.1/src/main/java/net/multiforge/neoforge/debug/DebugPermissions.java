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
package net.multiforge.neoforge.debug;

import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * The {@code multiforge.debug.view} permission node (protocol §6).
 *
 * <p>Protocol §6 names the {@code SUBSCRIBE} handler as the sole
 * enforcement point for this node. Through v1.3.18 nothing enforced it
 * at all: every emitter was installed with {@link
 * net.multiforge.runtime.diagnostics.emitters.PermissionFilter#ALWAYS_ALLOW}
 * and {@code DebugChannelServer.handleClientFrame} performed no check,
 * so any player who dropped the client jar into {@code mods/} received
 * the server's full region, MSPT, heatmap, pin and violation telemetry.
 *
 * <p><b>Default grant: everyone.</b> This deliberately differs from
 * §6's original {@code Commands.LEVEL_GAMEMASTERS} default (and from
 * NeoForge's own {@code USE_SELECTORS_PERMISSION} convention). The
 * debug overlays are a diagnostic convenience rather than privileged
 * information — {@code HELLO} already carries the protocol version and
 * tick rate unconditionally — and MultiForge would rather a curious
 * player be able to see why their base is laggy than lock it behind op.
 * The node exists so that servers which disagree can restrict it: any
 * {@code IPermissionHandler} (LuckPerms and friends) can deny {@code
 * multiforge.debug.view}, and the gate then takes effect on that
 * player's next {@code SUBSCRIBE}.
 *
 * <p>{@code PermissionNode(modID, nodeName)} concatenates to {@code
 * modID + "." + nodeName}, so this registers under exactly the {@code
 * multiforge.debug.view} string the protocol document specifies.
 */
public final class DebugPermissions {
    public static final PermissionNode<Boolean> VIEW = new PermissionNode<>(
            "multiforge", "debug.view", PermissionTypes.BOOLEAN, (player, uuid, contexts) -> true);

    private DebugPermissions() {}

    /** Game-bus listener; wired from {@code DebugChannelServer.installGameBusHooks}. */
    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(VIEW);
    }
}
