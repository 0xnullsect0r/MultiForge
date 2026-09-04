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
 * Declares the {@link OrderingContract} a handler needs. MultiForge
 * upgrades its dispatch to satisfy the strongest annotation seen on
 * any listener for that event type.
 */
@Retention(RUNTIME)
@Target({METHOD, TYPE})
public @interface Ordering {
    OrderingContract value();
}
