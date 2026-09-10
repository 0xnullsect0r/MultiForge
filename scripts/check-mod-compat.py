#!/usr/bin/env python3
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
"""
Report which mod jars a MultiForge server can actually load.

    scripts/check-mod-compat.py mods/*.jar
    scripts/check-mod-compat.py --base 21.1.234 mods/*.jar

MultiForge is forked from a specific NeoForge release and reports that as
its `neoforge` version (see docs/compatibility.md). A mod requiring a
newer NeoForge is refused at load. This tells you which ones, before you
spend ten minutes on a boot that ends in a wall of dependency failures.

Why not just grep the toml: a `neoforge.mods.toml` carries a dependency
block *per mod*, and a single jar often bundles several mods via jarjar.
Grepping the first `modId = "neoforge"` block reports the first mod's
requirement and silently misses the rest — which is how `fastworkbench`
looked unconstrained when the `fastbench` mod inside it needs 21.1.187.
This walks every nested toml and every dependency block, and reports the
highest floor found.
"""

import argparse
import re
import sys
import zipfile

DEP_SPLIT = re.compile(r"\[\[dependencies\.")
IS_NEOFORGE = re.compile(r'modId\s*=\s*"neoforge"')
RANGE = re.compile(r'versionRange\s*=\s*"([^"]*)"')
# Leading bound of a Maven range: "[21.1.187,)" -> 21.1.187
FLOOR = re.compile(r"^[\[(]\s*(\d+)(?:\.(\d+))?(?:\.(\d+))?")


def floor_of(spec):
    """Lowest version a range admits, as a comparable tuple."""
    m = FLOOR.match(spec)
    if not m:
        return (0, 0, 0)
    return tuple(int(p) if p else 0 for p in m.groups())


def requirements(path):
    """Every `neoforge` versionRange declared anywhere in the jar."""
    found = []
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if not name.endswith("neoforge.mods.toml"):
                continue
            text = z.read(name).decode("utf-8", "replace")
            for block in DEP_SPLIT.split(text)[1:]:
                if IS_NEOFORGE.search(block):
                    m = RANGE.search(block)
                    if m:
                        found.append(m.group(1))
    return found


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="21.1.1", help="NeoForge version MultiForge reports (default: 21.1.1)")
    ap.add_argument("jars", nargs="+")
    args = ap.parse_args()

    base = floor_of("[" + args.base)
    ok, too_new, broken = [], [], []

    for path in args.jars:
        name = path.rsplit("/", 1)[-1]
        try:
            reqs = requirements(path)
        except zipfile.BadZipFile:
            broken.append((name, "not a valid jar"))
            continue
        except OSError as e:
            broken.append((name, str(e)))
            continue

        highest = max((floor_of(r) for r in reqs), default=(0, 0, 0))
        shown = ", ".join(sorted(set(reqs))) or "no neoforge range declared"
        if highest <= base:
            ok.append((name, shown))
        else:
            too_new.append((name, highest, shown))

    if ok:
        print(f"Loadable on {args.base} ({len(ok)}):")
        for name, shown in sorted(ok):
            print(f"  {name:<38} {shown}")
    if too_new:
        print(f"\nRefused — needs a newer NeoForge ({len(too_new)}):")
        for name, hi, shown in sorted(too_new, key=lambda r: r[1], reverse=True):
            print(f"  {name:<38} needs {hi[0]}.{hi[1]}.{hi[2]:<8} {shown}")
    if broken:
        print(f"\nUnreadable ({len(broken)}):")
        for name, why in sorted(broken):
            print(f"  {name:<38} {why}")

    print(
        f"\n{len(ok)} loadable, {len(too_new)} refused, {len(broken)} unreadable."
        "\nSibling dependencies between mods are not checked — a mod listed as"
        "\nloadable can still fail if another mod it needs is absent."
    )
    return 1 if too_new or broken else 0


if __name__ == "__main__":
    sys.exit(main())
