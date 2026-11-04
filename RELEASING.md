# Releasing MultiForge

## Prerequisites

- Push access to `main` and tag push permissions.
- GitHub CLI (`gh`) authenticated against the repo.
- For local checks: JDK 21 (`JAVA_HOME`) and the fork workspace (in
  `upstream/neoforge-1.21.1`: `./gradlew --no-configuration-cache setup`,
  ~5-10 min, ~20 GB disk).

## Bumping the version

- `gradle.properties`: `version=<v>`.
- `upstream/neoforge-1.21.1/gradle.properties`: `multiforge_runtime_version=<v>`
  (the fork embeds the runtime and API of exactly this version).
- `CHANGELOG.md`: the new version's entry.
- Commit as `chore(release): v<v>`.

The release workflow refuses a tag that does not match both properties.

## Standard release

1. **Run the live checks on the release candidate.** The nightly runs only on
   the default branch, so dispatch it on the commit you will tag:
   ```
   gh workflow run nightly.yml --ref develop
   gh run watch "$(gh run list --workflow=nightly.yml --limit 1 --json databaseId -q '.[0].databaseId')"
   ```
   It must be green: vanilla-parity gate, scenarios, the strict-mode swarm
   with regions on workers, and the stress checks.
2. **Merge develop → main via PR.** Wait for green CI.
3. **Tag main:**
   ```
   git checkout main && git pull --ff-only
   git tag -a v<v> -m "v<v> — <one-line summary>"
   git push origin v<v>
   ```
4. **Watch the release workflow:** `gh run watch --workflow=release.yml`.
   - `build-jars` checks that the tag matches both version properties and is
     on `main`, then builds the outer modules, the client mod and the scanner.
   - `build-fork-installer` builds the fork installer, checks mixin targets,
     installs a server from that installer and boots it (NeoForge's production
     self-test), and checks the installer embeds the MultiForge runtime and API.
   - `gh-release` writes `SHA256SUMS` over every asset, creates the release as
     a draft, attaches everything, then publishes it. Until then
     `releases/latest` still points at the previous release, so the updater
     never sees a half-uploaded one.

   Assets of every release:
   - `multiforge-update.sh` — the one-line installer/updater
   - `server.jar` — NeoForged's server starter (LGPL-2.1), mirrored at the
     version and checksum pinned in `release.yml`
   - `multiforge-scanner-<v>.jar` + `multiforge-scanner.jar` — the scanner
     behind `/multiforge certify`
   - `SHA256SUMS` — checked by the updater and the Pelican egg
   - `multiforge-runtime-<v>.jar` — mod-author compile target
   - `multiforge-<v>-installer.jar` + `multiforge-installer.jar` — the fork
     installer
   - `multiforge-<v>-replacement.zip` + `multiforge-replacement.zip` — the
     in-place converter
   - `multiforge-client-<v>.jar` + `multiforge-client.jar` — the client debug mod
   - `pelican-egg.json`

   The updater and the egg download from `…/releases/latest/download/`, so the
   stable names must be in every release. To bump the mirrored `server.jar`,
   change `SERVER_STARTER_VERSION` and `SERVER_STARTER_SHA256` in
   `release.yml` and run `scripts/test-multiforge-update.sh` first.

## If a release job fails

No release is published unless every job succeeds, so there is nothing to
patch up by hand: fix the cause (or wait out an infrastructure failure) and
re-run the failed jobs:

```
gh run rerun --failed "$(gh run list --workflow=release.yml --limit 1 --json databaseId -q '.[0].databaseId')"
```

If the fix needs a code change, delete the tag (below), land the fix through
develop → main, and tag again. Do not upload hand-built assets to a release:
`SHA256SUMS` would not cover them and the updater would refuse the release.

## Verification (after the release, before announcing)

1. **The updater installs it** into an empty directory and updates an older
   install, with a JDK 21:
   ```
   TEST=$(mktemp -d) && cd "$TEST"
   curl -fsSL https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-update.sh | sh
   echo eula=true > eula.txt
   timeout 120 ./run.sh nogui | tee boot.log
   grep -E 'Done \(' boot.log
   grep -c NoSuchMethodError boot.log    # expect 0
   ```
2. **The Pelican egg** (on a test panel): import `pelican-egg.json` from the
   release, create a server, let the install finish, start it, accept the
   EULA prompt, and check it reaches `Done`. Repeat with `MULTIFORGE_VERSION`
   set to the new version.

## Rolling back a bad release

- Delete the GitHub Release and its tag:
  `gh release delete v<v> --cleanup-tag`.
- Fix the underlying bug, re-tag.

Never `git push --force` to `main` or `develop`.
