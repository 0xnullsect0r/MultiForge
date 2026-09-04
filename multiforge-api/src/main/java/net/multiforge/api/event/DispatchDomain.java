/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
