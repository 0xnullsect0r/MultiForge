/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.api.event;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a NeoForge event handler (or an entire {@code
 * @EventBusSubscriber} class) as safe to dispatch on the given domain.
 * MultiForge reads this at listener-registration time.
 *
 * <p>Unannotated handlers default to {@link
 * DispatchDomainKind#LEGACY_SERIAL} — safe, but slower.
 *
 * <pre>{@code
 * @DispatchDomain(DispatchDomainKind.REGION)
 * public static void onBlockBreak(BlockEvent.BreakEvent event) { ... }
 * }</pre>
 */
@Retention(RUNTIME)
@Target({METHOD, TYPE})
public @interface DispatchDomain {
    DispatchDomainKind value();
}
