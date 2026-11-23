#!/bin/sh
# MultiForge — Copyright (c) 2026 MultiForge authors.
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, version 3.
# This program is distributed in the hope that it will be useful, but
# WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
# General Public License for more details.
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.
#
# Refuses a tree that may not be released or run through the live checks, from
# multiforge_patches_enabled in its gradle.properties (absent means true):
#
#   true        allowed on every line
#   prerelease  allowed only on a line that releases as a GitHub prerelease
#   anything else (false, partial, a typo) is refused
#
#   MF_TREE=<tree> MF_PRERELEASE=true|false mf-patches-gate.sh
set -eu
props="${MF_TREE:?MF_TREE is not set}/gradle.properties"
[ -f "$props" ] || { echo "::error::no such file: $props" >&2; exit 1; }
state=$(sed -n 's/^multiforge_patches_enabled=//p' "$props" | tail -n 1)
state=${state:-true}
case "$state" in
    true) ;;
    prerelease)
        if [ "${MF_PRERELEASE:-false}" != true ]; then
            echo "::error::$MF_TREE has multiforge_patches_enabled=prerelease, but this line releases as stable" >&2
            exit 1
        fi
        ;;
    *)
        echo "::error::$MF_TREE has multiforge_patches_enabled=$state: not releasable" >&2
        exit 1
        ;;
esac
echo "patch state of $MF_TREE: $state"
