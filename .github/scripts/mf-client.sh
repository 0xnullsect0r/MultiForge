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
# Prints MF_CLIENT_PORTED=true|false for $GITHUB_ENV: whether this line's
# multiforge-client builds (multiforgeClientPorted in the root gradle.properties).
# The 1.x line ships the client, so it may never turn this off.
#
#   MF_LINE=<line> mf-client.sh [gradle.properties]
set -eu
props=${1:-gradle.properties}
[ -f "$props" ] || { echo "::error::no such file: $props" >&2; exit 1; }
ported=$(sed -n 's/^multiforgeClientPorted=//p' "$props" | tail -n 1)
ported=${ported:-true}
case "$ported" in
    true | false) ;;
    *)
        echo "::error::multiforgeClientPorted must be true or false, not '$ported'" >&2
        exit 1
        ;;
esac
if [ "$ported" = false ] && [ "${MF_LINE:-}" = 1.21 ]; then
    echo "::error::the 1.21 line ships multiforge-client; multiforgeClientPorted=false is not allowed there" >&2
    exit 1
fi
echo "MF_CLIENT_PORTED=$ported"
