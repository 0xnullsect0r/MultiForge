# One-time setup: public downloads repo + GHCR visibility

MultiForge's source repo is private, so GitHub Release assets attached
here are gated behind auth. To make the installer JAR, replacement ZIP,
and Docker image anonymously downloadable, we publish binaries through
a **public sibling repo** and toggle the GHCR package to public. Both
are one-time operations; after that every release ships automatically.

## 1. Create the public downloads repo

Go to <https://github.com/new> and create:

- **Owner:** `0xnullsect0r`
- **Name:** `multiforge-releases`
- **Visibility:** **Public**
- **Description:** "MultiForge release binaries. Source code lives in
  the private `multiforge` repo."
- Initialize with a README (edit later).

The repo hosts only Release assets. Its default branch can stay empty
apart from the README.

## 2. Create a fine-grained PAT with write access to it

<https://github.com/settings/personal-access-tokens/new>

- **Resource owner:** `0xnullsect0r`
- **Repository access:** Only select repositories → `multiforge-releases`
- **Repository permissions:**
  - Contents: **Read and write** (required to create Releases and
    upload assets)
  - Metadata: Read-only (auto-included)
- **Expiration:** 1 year (mark your calendar to rotate)

Copy the token (you only see it once).

## 3. Add the PAT as a secret on the private source repo

<https://github.com/0xnullsect0r/multiforge/settings/secrets/actions/new>

- **Name:** `RELEASES_REPO_TOKEN`
- **Value:** paste the PAT from step 2

The release workflow reads it via `secrets.RELEASES_REPO_TOKEN`. If you
ever change the downloads repo name, also add a repo **variable** (not
secret) named `RELEASES_REPO` at
<https://github.com/0xnullsect0r/multiforge/settings/variables/actions/new>
with value `owner/repo-name`. Absent that variable, the workflow
defaults to `0xnullsect0r/multiforge-releases`.

## 4. Toggle the GHCR package to public

**Only needed after the first successful release** — GHCR only creates
the package on first push, so this step waits until v1.0.0 actually
publishes.

<https://github.com/users/0xnullsect0r/packages/container/multiforge-server/settings>

Scroll to **Danger Zone** → **Change package visibility** → **Public**.

After the toggle, `docker pull ghcr.io/0xnullsect0r/multiforge-server:1.0.0`
works anonymously forever. Every future push to the same package
inherits the public visibility.

## Public download URLs

Once all three steps are done:

| Artifact | URL |
|----------|-----|
| Installer JAR (latest) | <https://github.com/0xnullsect0r/multiforge-releases/releases/latest/download/multiforge-installer.jar> |
| Installer JAR (pinned) | <https://github.com/0xnullsect0r/multiforge-releases/releases/download/v1.0.0/multiforge-installer-1.0.0.jar> |
| Replacement ZIP (latest) | <https://github.com/0xnullsect0r/multiforge-releases/releases/latest/download/multiforge-replacement.zip> |
| Replacement ZIP (pinned) | <https://github.com/0xnullsect0r/multiforge-releases/releases/download/v1.0.0/multiforge-1.0.0-replacement.zip> |
| Docker image | `ghcr.io/0xnullsect0r/multiforge-server:1.0.0` (also `:1.0`, `:latest`) |

## What the release workflow does

Every tag push (`v*`) on the private repo:

1. Builds the runtime, license, installer, and license-CLI artifacts.
2. Runs the installer's `build-zip` mode to emit
   `multiforge-<v>-replacement.zip`.
3. Builds the multi-arch Docker image and pushes it to GHCR under this
   repo's owner (private repo's `packages: write` permission carries
   its own token — no PAT needed for GHCR).
4. Cosign-signs the image (keyless via GitHub OIDC).
5. Creates a Release **on `multiforge-releases`** (the public repo)
   using `RELEASES_REPO_TOKEN` and uploads all binaries as assets.

## What the sync workflow does

`sync-downloads-repo.yml` runs on every push to `main` that touches
`docs/**` or `.github/downloads-repo-content/**`. It:

1. Clones the downloads repo using `RELEASES_REPO_TOKEN`.
2. Overwrites the tracked mirrors: `README.md`, `SECURITY.md`,
   `.github/ISSUE_TEMPLATE/`, and every operator-facing file under
   `docs/`. Internal-only files (`blueprint.md`,
   `website-install-copy.md`, `downloads-repo-setup.md`) stay private.
3. Commits + pushes if anything changed.

This is how the public repo gets its README, docs, and issue
templates without any manual copy-paste. Edit the source of truth in
this repo; the sync runs on the next merge to main.

## Rotating the PAT

- Generate a new fine-grained PAT with the same scope.
- Replace the value at
  <https://github.com/0xnullsect0r/multiforge/settings/secrets/actions/RELEASES_REPO_TOKEN>.
- Delete the old PAT at
  <https://github.com/settings/personal-access-tokens>.

The next release picks up the new token; nothing else changes.

## If a release run fails on "Resource not accessible by integration"

That's usually the PAT being missing, expired, or scoped wrong. Check:

1. `RELEASES_REPO_TOKEN` secret exists on the private repo.
2. The PAT hasn't expired.
3. The PAT's repository access includes `multiforge-releases`.
4. The PAT's Contents permission is **Read and write** (not just Read).

## If a release run fails on "repository not found"

The `RELEASES_REPO` variable / default points at a repo that doesn't
exist yet. Create `0xnullsect0r/multiforge-releases` (step 1 above) or
set the `RELEASES_REPO` variable to the repo you did create.
