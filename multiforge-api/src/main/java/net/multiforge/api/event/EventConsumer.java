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
package net.multiforge.api.event;

import java.io.Serializable;
import java.util.function.Consumer;

/**
 * A {@link Consumer} whose method references stay introspectable.
 *
 * <p>{@code IEventBus.addListener} takes a plain {@code Consumer}, and a
 * method reference upcast to it carries no usable metadata on modern JVMs:
 * javac only generates the {@code writeReplace} machinery that names the
 * referenced method for functional interfaces that are {@link Serializable}.
 * Without it MultiForge cannot see the method behind, say, {@code
 * bus.addListener(this::onBlockBreak)}, and the listener is treated as
 * unannotated — {@code @DispatchDomain}/{@code @Ordering} on the method are
 * silently ignored and it may take the conservative serial lane for ever.
 *
 * <p>Casting the reference to {@code EventConsumer} opts into that metadata:
 *
 * <pre>{@code
 * @DispatchDomain(DispatchDomainKind.REGION)
 * void onBreak(BlockEvent.BreakEvent event) { ... }
 *
 * bus.addListener((EventConsumer<BlockEvent.BreakEvent>) this::onBreak);
 * }</pre>
 *
 * <p>The cast is the entire contract: the interface adds no methods, never
 * serializes anything at runtime, and plain lambdas and ordinary consumers
 * keep exactly the behaviour they have today. A method reference behind any
 * other non-serializable functional interface cannot be resolved — that is
 * a JVM limitation, not a choice.
 */
public interface EventConsumer<T> extends Consumer<T>, Serializable {}
