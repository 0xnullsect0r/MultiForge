#!/usr/bin/env sh
# NeoForge requires a configured set of both JVM and program arguments.
# Add custom JVM arguments to the user_jvm_args.txt
# Add custom program arguments {such as nogui} to this file in the next line before the "$@" or
#  pass them to this script directly

# MultiForge: Java 21 exactly. Mods built for NeoForge 1.21.1 (and the Mixin
# library most of them use) cannot read class files of a newer Java, so a newer
# JDK fails at mod loading with "Unsupported class file major version".
# JAVA_HOME wins over the java on PATH when it points at a JDK.
JAVA_BIN=java
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA_BIN="$JAVA_HOME/bin/java"; fi
JAVA_MAJOR=$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p' | head -n 1)
if [ "$JAVA_MAJOR" != "21" ]; then
    echo "MultiForge requires Java 21. Found: ${JAVA_MAJOR:-no working java} at $JAVA_BIN" >&2
    echo "Install a JDK 21 (for example Temurin 21) and point JAVA_HOME at it." >&2
    exit 1
fi
"$JAVA_BIN" @user_jvm_args.txt @libraries/@MAVEN_PATH@/unix_args.txt "$@"
