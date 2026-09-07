# Releasing MultiForge

Standard release flow + fallback if the fork-installer CI job fails.

## Prerequisites

- Push access to `main` and tag push permissions.
- Local machine with the vendored NeoForge workspace ready — `./gradlew :setup` must have run at least once (~5-10 min, ~20 GB disk).
- GitHub CLI (`gh`) authed against the repo.
- JDK 21 (`JAVA_HOME`).

## Standard release

1. **Merge develop → main via PR.** Wait for green CI.
2. **Cut the tag from main:**
   ```
   git checkout main && git pull --ff-only
   git tag -a v1.3.2 -m "v1.3.2 — <one-line summary>"
   git push origin v1.3.2
   ```
3. **Watch the release workflow.**
   ```
   gh run watch --workflow=release.yml
   ```
   Two jobs fire in parallel-ish: `build-jars` (fast, required) and `build-fork-installer` (~15 min, advisory).
4. **If both succeed:** the GitHub Release at `https://github.com/0xnullsect0r/MultiForge/releases/tag/v1.3.2` has all artifacts:
   - `multiforge-runtime-1.3.2.jar` (mod-author compile target)
   - `multiforge-installer-1.3.2.jar` (pure-Java stub installer; kept for compat but the fork installer is what actually works)
   - `multiforge-1.3.2-installer.jar` (**the fork installer — this is the one users install with**)
   - `multiforge-installer.jar` (stable-alias copy of the fork installer)
   - `multiforge-1.3.2-replacement.zip` (drop-in overlay)
   - `multiforge-client-1.3.2.jar` (optional client debug mod; NeoForge 1.21.1)
   - `multiforge-client.jar` (stable-alias copy of the client mod)
   - `pelican-egg.json` (Pelican Panel / Pterodactyl egg)

   No further action needed.

## Fallback: build-fork-installer CI job failed

The fork-installer job has historically been preempted on GHA `ubuntu-latest` during `neoFormDecompile` (~2 min of subprocess-heavy javap + vineflower work inside the same JVM heap as Gradle — infrastructure issue, not code). If the job failed, `multiforge-1.3.2-installer.jar` is MISSING from the release, and the Pelican egg's download URL is broken.

**Local fallback:**

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH=$JAVA_HOME/bin:$PATH

# Ensure you're on the tag
git checkout v1.3.2

# Publish MultiForge runtime + api to mavenLocal (fork consumes these)
./gradlew :multiforge-api:publishToMavenLocal :multiforge-runtime:publishToMavenLocal

# Build the fork installer (two separate invocations per the pre-existing
# Gradle task-graph quirk — combined invocation wipes patches back to
# pristine before compileJava runs)
cd upstream/neoforge-1.21.1
./gradlew :setup :neoforge:applyMultiforgePatches
./gradlew :neoforge:signInstallerJar

# The fork installer's actual filename derives from gradleutils; find it
# and rename to the release-friendly form
FORK_JAR=$(find projects/neoforge/build/distributions -maxdepth 1 \
    -name 'neoforge-*-installer.jar' ! -name '*-unsigned.jar' | head -1)
[ -z "$FORK_JAR" ] && FORK_JAR=$(find projects/neoforge/build/distributions -maxdepth 1 \
    -name 'neoforge-*-installer-unsigned.jar' | head -1)
cp "$FORK_JAR" /tmp/multiforge-1.3.2-installer.jar

# Attach to the existing release (--clobber replaces if a stale one is there)
cd ../..
gh release upload v1.3.2 /tmp/multiforge-1.3.2-installer.jar --clobber
```

## Verification (post-release, before announcing)

1. **Fork installer downloads:**
   ```
   curl -LI https://github.com/0xnullsect0r/MultiForge/releases/download/v1.3.2/multiforge-1.3.2-installer.jar
   # HTTP/2 200 (or 302 → 200)
   ```

2. **Fresh install boots:**
   ```
   TEST=$(mktemp -d)
   cd $TEST
   curl -LO https://github.com/0xnullsect0r/MultiForge/releases/download/v1.3.2/multiforge-1.3.2-installer.jar
   java -jar multiforge-1.3.2-installer.jar --installServer .
   echo eula=true > eula.txt
   chmod +x run.sh
   timeout 60 ./run.sh nogui | tee /tmp/boot.log
   grep 'Done' /tmp/boot.log       # server booted
   grep 'NoSuchMethodError' /tmp/boot.log || echo 'no NoSuchMethodError — good'
   grep 'no-regionizer-skip' /tmp/boot.log | wc -l   # expect 0-2 lines (R.1 eager materialise covers boot dims; R.2 rate-limits any edge cases)
   ```

3. **Egg import** (if you have a Pelican Panel test instance):
   - Import `pelican-egg.json` from the release into your panel.
   - Create a server. Wait for install to complete.
   - Boot; verify no install-script errors.

## Bumping the version

- Update `gradle.properties` `version=`.
- Update `upstream/neoforge-1.21.1/gradle.properties` `multiforge_runtime_version=` (must match for the fork's jarJar embed to resolve from mavenLocal).
- Update `CHANGELOG.md` with the new version entry.
- Commit as `chore(release): bump version to <v>`.

## Rolling back a bad release

- Delete the tag: `git tag -d v1.3.2 && git push origin :refs/tags/v1.3.2`.
- Delete the GitHub Release: `gh release delete v1.3.2 --cleanup-tag`.
- Fix the underlying bug, re-tag.

Never `git push --force` to `main` or `develop`.
