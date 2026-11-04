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

plugins {
    id("multiforge.base")
    id("net.neoforged.moddev") version "2.0.78"
}

description = "MultiForge fixture mod mftest_writer — see multiforge-testmods/README.md."

neoForge {
    version = providers.gradleProperty("neoForgeVersion").get()
}

tasks.processResources {
    val tokens = mapOf("version" to project.version.toString())
    inputs.properties(tokens)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(tokens)
    }
}

tasks.jar {
    archiveBaseName.set("mftest-writer")
}
