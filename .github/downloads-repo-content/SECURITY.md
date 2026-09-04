# Security disclosure

If you've found a security issue in MultiForge — remote code execution,
license bypass, credential exposure, denial of service on a running
server — **do not file a public issue**. Report privately.

## How to report

Email **security@multiforge.example** with:

- One-sentence summary.
- Minimal reproduction (steps + version + config).
- Impact you've observed (what an attacker gets).
- Your PGP key if you want a signed reply.

Include a proof-of-concept attachment (log, tcpdump, tiny mod) rather
than pasting full source into the email body.

## What happens next

- **Within 2 business days:** you get a human reply acknowledging the
  report and a first triage estimate.
- **Within 14 days:** a fix plan (with a target release version) or a
  "we don't consider this a vulnerability" reply with reasoning.
- **On fix release:** you get credited in the release notes and any
  advisory, unless you asked to stay anonymous.

## Scope

In scope:

- The MultiForge server binary (`multiforge-installer-*.jar`,
  `multiforge-*-server.jar`, Docker image).
- The license verifier (`multiforge-license-*.jar`).
- The debug wire protocol (`multiforge:debug/v1`).
- The client debug mod.
- The release / CI infrastructure that ships the above.

Out of scope:

- The purchase site (report those to security@multiforge.example
  separately with `[site]` in the subject).
- Third-party mods running under MultiForge — those go to the mod
  author.
- Vanilla NeoForge / Minecraft vulnerabilities — those go to
  <https://neoforged.net/security> and <https://www.microsoft.com/msrc>.
- Social engineering, physical attacks.

## Coordinated disclosure

We prefer a 90-day disclosure window from initial report. If you need
to publish sooner (e.g. because it's already public elsewhere), tell
us up front and we'll match your timeline.
