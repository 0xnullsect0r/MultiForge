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
package net.multiforge.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method (or, applied to a type, all its methods) as executing on a
 * region worker thread.
 *
 * <p>This is a stub landed ahead of the scheduler work that will actually
 * enforce region-thread affinity (Phase 1 / Track A). Today it exists purely
 * as a bytecode-visible marker that {@code multiforge-scanner} reads to drive
 * its tick-reachability heuristic and its blocking-call rule (R03) — see
 * {@code docs/design/scanner-rules.md} &sect;1.4-1.5. Note: that doc assumed
 * package {@code net.multiforge.api.annotation}; this stub lives directly
 * under {@code net.multiforge.api} per the Track C2 task brief. Flag for a
 * doc-amendment pass.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RegionThread {}
