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
# Check a GameTest server log: at least <min> required tests passed, and
# MultiForge's own fixtures were among the tests that ran.
#
#   check-gametest-log.sh <log> <min>
set -eu
log=$1
min=$2
passed=$(sed -n 's/.*All \([0-9][0-9]*\) required tests passed.*/\1/p' "$log" | tail -n 1)
if [ -z "$passed" ]; then
    echo "::error::the GameTest log has no 'All N required tests passed' line"
    exit 1
fi
if [ "$passed" -lt "$min" ]; then
    echo "::error::only $passed required GameTests ran; expected at least $min (tests stopped registering?)"
    exit 1
fi
for t in testRegionTicksOnAWorker commandUnderARegionTokenOnTheServerThreadRunsInline scheduledBlockTickFires; do
    grep -q "$t" "$log" || { echo "::error::MultiForge GameTest $t did not run"; exit 1; }
done
echo "$passed required GameTests passed (minimum $min)"
