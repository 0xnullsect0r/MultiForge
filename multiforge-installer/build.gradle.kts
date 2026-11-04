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
    application
}

description = "Build tool that assembles the drop-in replacement archive around the fork installer."

dependencies {
    // No runtime dependency: this module only assembles an archive. The
    // thing that actually converts a server is the fork installer, passed
    // in at build-zip time with --installer. Bundling multiforge-runtime
    // here is what produced the v1.4.1-and-earlier archive that shipped a
    // library jar and a launcher for a class that never existed.

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.3")
}

application {
    mainClass.set("net.multiforge.installer.Main")
}

// Make the installer jar itself runnable (java -jar) — the application
// plugin's start scripts also work, but a single-file .jar is what
// operators actually download from a GitHub Release.
tasks.jar {
    manifest {
        attributes(
                "Main-Class" to "net.multiforge.installer.Main",
                "Implementation-Title" to "MultiForge Installer",
                "Implementation-Version" to project.version,
        )
    }
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
