#!/usr/bin/env bash
#
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
# The facts that differ between MultiForge's release lines, in one place.
# Prints KEY=VALUE lines for $GITHUB_ENV.
#
#   mf-line.sh --tag <tag>    the line a release tag belongs to; refuses any
#                             tag that matches no line
#   mf-line.sh --tree [dir]   the line of a checkout (its minecraftVersion)
#
# A 26.x build must never become GitHub's "latest" release: old updaters and
# imported eggs follow `latest`, and would move a 1.21.1 world to 26.x. So that
# line releases only as `v2.*-alpha.*` prereleases with MF_MAKE_LATEST=false.

set -euo pipefail

line_121() {
    cat <<'EOF'
MF_LINE=1.21
MF_TREE=upstream/neoforge-1.21.1
MF_JAVA=21
MF_TAG_REGEX=^v1\.[0-9]+\.[0-9]+$
MF_RELEASE_BRANCHES=main release/1.21.x
MF_FORK_VERSION_REGEX=^21\.[0-9]+\.[0-9]+-multiforge-
MF_PRERELEASE=false
MF_MAKE_LATEST=true
EOF
}

line_261() {
    cat <<'EOF'
MF_LINE=26.1
MF_TREE=upstream/neoforge-26.1.2
MF_JAVA=25
MF_TAG_REGEX=^v2\.[0-9]+\.[0-9]+-alpha\.[0-9]+$
MF_RELEASE_BRANCHES=port/26.1.2
MF_FORK_VERSION_REGEX=^26\.1\.2\.[0-9]+-multiforge-2\.
MF_PRERELEASE=true
MF_MAKE_LATEST=false
EOF
}

usage() {
    echo "usage: mf-line.sh --tag <tag> | --tree [dir]" >&2
    exit 2
}

[ $# -ge 1 ] || usage
case "$1" in
    --tag)
        [ $# -eq 2 ] || usage
        if [[ "$2" =~ ^v1\.[0-9]+\.[0-9]+$ ]]; then
            line_121
        elif [[ "$2" =~ ^v2\.[0-9]+\.[0-9]+-alpha\.[0-9]+$ ]]; then
            line_261
        else
            echo "::error::tag '$2' matches no release line (v1.X.Y, or v2.X.Y-alpha.N)" >&2
            exit 1
        fi
        ;;
    --tree)
        dir="${2:-.}"
        mc=$(sed -n 's/^minecraftVersion=//p' "$dir/gradle.properties")
        case "$mc" in
            1.21.1) line_121 ;;
            26.1.2) line_261 ;;
            *)
                echo "::error::no release line for minecraftVersion='$mc'" >&2
                exit 1
                ;;
        esac
        ;;
    *) usage ;;
esac
