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
"""Edit MultiForge's grouped net/minecraft patches through a scratch git repo.

The patches in multiforge-patches/NN-group/ are applied in group order on top
of NeoForge's patched Vanilla source, and several groups patch the same file.
Editing them by hand means keeping every later group's context lines valid.
This tool turns the patch set into a git history instead: one commit per
group on top of a "pristine" commit, so an edit can be committed as a fixup
of the group it belongs to and the patch files re-exported.

Workflow (after `./gradlew setup` and `:neoforge:applyMultiforgePatches` in
upstream/neoforge-1.21.1):

  scripts/mf-patches.py init            # build the work repo from the applied tree
  $EDITOR build/mf-patches/work/net/minecraft/...   # edit the fully-patched files
  scripts/mf-patches.py fixup 02-region-tick        # fold the edit into that group
  scripts/mf-patches.py export                      # rewrite multiforge-patches/*

Then build as usual: :neoforge:applyMultiforgePatches (run by compileJava) resets
every target to its pristine copy and applies the exported patches. Never copy
work-repo files into the fork tree by hand — the applier would take them for
freshly generated pristine sources.

`add FILE` starts tracking a Vanilla file no patch touched yet (run it before
editing that file). Only net/minecraft/** is handled here; patches against the
committed net/neoforged tree are applied to that tree directly.
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PATCHES = ROOT / "multiforge-patches"
FORK = ROOT / "upstream" / "neoforge-1.21.1"
TREE = FORK / "projects" / "neoforge" / "src" / "main" / "java"
BASE = FORK / "projects" / "base" / "src" / "main" / "java"
WORK = ROOT / "build" / "mf-patches" / "work"


def git(*args, check=True, capture=False, cwd=WORK):
    return subprocess.run(
        ["git", *args], cwd=cwd, check=check, text=True,
        stdout=subprocess.PIPE if capture else None,
        env={**os.environ, "GIT_AUTHOR_NAME": "mf", "GIT_AUTHOR_EMAIL": "mf@local",
             "GIT_COMMITTER_NAME": "mf", "GIT_COMMITTER_EMAIL": "mf@local"})


def groups():
    return sorted(p for p in PATCHES.iterdir() if p.is_dir())


def target_of(patch: Path) -> str:
    for line in patch.read_text().splitlines():
        if line.startswith("--- "):
            t = line[4:].split("\t")[0].strip()
            return t[2:] if t.startswith("a/") else t
    raise SystemExit(f"no '--- ' header in {patch}")


def minecraft_patches(group: Path):
    out = []
    for p in sorted(group.rglob("*.patch")):
        t = target_of(p)
        if t.startswith("net/minecraft/"):
            out.append((p, t))
    return out


def cmd_init(_):
    if WORK.exists():
        shutil.rmtree(WORK)
    WORK.mkdir(parents=True)
    git("init", "-q")
    all_patches = [(g, p, t) for g in groups() for p, t in minecraft_patches(g)]
    files = sorted({t for _, _, t in all_patches})
    for t in files:
        src = TREE / t
        if not src.exists():
            raise SystemExit(f"{t} missing from {TREE} — run setup + applyMultiforgePatches first")
        (WORK / t).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, WORK / t)
    # Reverse-apply every group, newest first, to recover the pristine files.
    for g, p, t in reversed(all_patches):
        r = subprocess.run(["git", "apply", "-R", "--recount", str(p)], cwd=WORK, capture_output=True, text=True)
        if r.returncode != 0:
            raise SystemExit(f"cannot reverse {p.relative_to(ROOT)} — is the fork tree fully patched?\n{r.stderr}")
    git("add", "-A")
    git("commit", "-q", "-m", "pristine")
    for g in groups():
        for p, t in minecraft_patches(g):
            git("apply", "--recount", str(p))
        git("add", "-A")
        git("commit", "-q", "--allow-empty", "-m", g.name)
    print(f"work repo ready at {WORK} ({len(files)} files)")


def commit_of(group: str) -> str:
    log = git("log", "--format=%H %s", capture=True).stdout.splitlines()
    for line in log:
        sha, subject = line.split(" ", 1)
        if subject == group:
            return sha
    raise SystemExit(f"no commit for group {group}")


def cmd_add(args):
    for t in args.files:
        dst = WORK / t
        if dst.exists():
            continue
        src = TREE / t if (TREE / t).exists() else BASE / t
        if not src.exists():
            raise SystemExit(f"{t} not found in {TREE} or {BASE}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
        # New pristine file: add it to the pristine commit via fixup.
        git("add", t)
        git("commit", "-q", "--fixup", commit_of("pristine"))
        stashed = git("diff", "--quiet", check=False).returncode != 0
        if stashed:
            git("stash", "push", "-q")
        env = {**os.environ, "GIT_SEQUENCE_EDITOR": "true"}
        subprocess.run(["git", "rebase", "-q", "-i", "--autosquash", "--root"], cwd=WORK, check=True, env=env)
        if stashed:
            git("stash", "pop", "-q")


def cmd_fixup(args):
    target = commit_of(args.group)
    # Stage only the named files (default: all) so edits meant for different
    # groups can be folded one group at a time.
    git("add", "--", *(args.files or ["."]))
    if git("diff", "--cached", "--quiet", check=False).returncode == 0:
        print("nothing to fold in")
        return
    git("commit", "-q", "--fixup", target)
    stashed = git("diff", "--quiet", check=False).returncode != 0
    if stashed:
        git("stash", "push", "-q")
    env = {**os.environ, "GIT_SEQUENCE_EDITOR": "true",
           "GIT_AUTHOR_NAME": "mf", "GIT_AUTHOR_EMAIL": "mf@local",
           "GIT_COMMITTER_NAME": "mf", "GIT_COMMITTER_EMAIL": "mf@local"}
    r = subprocess.run(["git", "rebase", "-q", "-i", "--autosquash", "--empty=keep", "--root"], cwd=WORK, env=env)
    # A fixup that reverts a group's last change leaves that group's commit empty,
    # which git refuses to squash into; keep it (a group with no patches is fine).
    while r.returncode != 0 and (WORK / ".git" / "rebase-merge").exists() and \
            git("diff", "--cached", "--quiet", check=False).returncode == 0 and \
            not git("diff", "--name-only", "--diff-filter=U", capture=True).stdout.strip():
        subject = git("log", "-1", "--format=%s", capture=True).stdout.strip()
        git("commit", "-q", "--amend", "--allow-empty", "-m", subject.removeprefix("fixup! "))
        r = subprocess.run(["git", "rebase", "--continue"], cwd=WORK, env={**env, "GIT_EDITOR": "true"})
    if r.returncode != 0:
        raise SystemExit("rebase stopped on a conflict — resolve in the work repo, then `git rebase --continue`"
                         + (" and `git stash pop`" if stashed else ""))
    if stashed:
        git("stash", "pop", "-q")


def cmd_export(_):
    changed = 0
    for g in groups():
        sha = commit_of(g.name)
        names = git("diff", "--name-only", f"{sha}~1", sha, capture=True).stdout.split()
        existing = {t: p for p, t in minecraft_patches(g)}
        for t in names:
            diff = git("diff", "--no-color", "-U3", f"{sha}~1", sha, "--", t, capture=True).stdout
            lines = [l for l in diff.splitlines()
                     if not l.startswith(("diff --git", "index ", "new file mode", "deleted file mode"))]
            text = "\n".join(lines) + "\n"
            out = existing.pop(t, g / (t + ".patch"))
            old = out.read_text() if out.exists() else None
            if old is not None and _normalise(old) == _normalise(text):
                continue
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(text)
            changed += 1
            print(f"wrote {out.relative_to(ROOT)}")
        for t, p in existing.items():
            p.unlink()
            changed += 1
            print(f"removed {p.relative_to(ROOT)}")
    print(f"{changed} patch file(s) changed")


def _normalise(text):
    # Only the added/removed lines are the patch's meaning; hunk headers,
    # context whitespace and context extent vary between generators.
    return [l.rstrip() for l in text.splitlines()
            if l.startswith(("+", "-")) and not l.startswith(("+++", "---"))]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("init")
    a = sub.add_parser("add")
    a.add_argument("files", nargs="+")
    f = sub.add_parser("fixup")
    f.add_argument("group")
    f.add_argument("files", nargs="*", help="work-repo paths to fold in (default: every change)")
    sub.add_parser("export")
    args = ap.parse_args()
    {"init": cmd_init, "add": cmd_add, "fixup": cmd_fixup, "export": cmd_export}[args.cmd](args)


if __name__ == "__main__":
    main()
