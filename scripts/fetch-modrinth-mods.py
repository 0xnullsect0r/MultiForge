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
"""Download NeoForge 1.21.1 mods from Modrinth, with their required dependencies.

Builds the mod directory for a compatibility run (see
docs/verification/README.md, "Mod matrix"):

  scripts/fetch-modrinth-mods.py <out-dir> create waystones supplementaries ...
  ./gradlew :multiforge-bench:atm10 -PmodpackDir=<dir containing mods/>

Each slug resolves to its newest release for loader neoforge and game
version 1.21.1 (the newest version of any type if there is no release);
required dependencies are followed. A file already present is not
downloaded again. Talks only to api.modrinth.com and its CDN.
"""
import json, os, sys, urllib.parse, urllib.request

if len(sys.argv) < 3:
    sys.exit("usage: fetch-modrinth-mods.py <out-dir> <slug>...")
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
slugs = sys.argv[2:]
UA = {"User-Agent": "multiforge-compat-check/1.0"}
def get(url):
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))
seen = {}
queue = list(slugs)
while queue:
    s = queue.pop(0)
    if s in seen: continue
    q = urllib.parse.urlencode({"loaders": '["neoforge"]', "game_versions": '["1.21.1"]'})
    vs = get(f"https://api.modrinth.com/v2/project/{s}/version?{q}")
    if not vs:
        print("NO VERSION", s); seen[s] = None; continue
    v = next((x for x in vs if x["version_type"] == "release"), vs[0])
    f = next((x for x in v["files"] if x["primary"]), v["files"][0])
    seen[s] = (v["project_id"], f["filename"])
    path = os.path.join(out, f["filename"])
    if not os.path.exists(path):
        urllib.request.urlretrieve(f["url"], path)
    print(f"{s:24s} {v['version_number']:28s} {f['filename']}")
    for d in v["dependencies"]:
        if d["dependency_type"] == "required" and d.get("project_id"):
            p = get(f"https://api.modrinth.com/v2/project/{d['project_id']}")
            queue.append(p["slug"])
