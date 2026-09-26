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
 * Marks a method (or, applied to a type, all its methods) as running on a
 * region worker thread — typically a tick handler reached from a block
 * entity, entity or scheduled-task callback.
 *
 * <p>The marker is read by {@code multiforge-scanner}: an annotated method is
 * a root of its tick-reachability analysis, and a blocking call inside one
 * ({@code Future.get}, {@code join}, {@code Thread.sleep}) is reported by rule
 * R03 as an error (see {@code docs/design/scanner-rules.md} §1.5). It has no
 * effect at run time; MultiForge itself decides which thread runs a callback.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RegionThread {}
