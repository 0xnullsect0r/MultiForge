# Security policy

## Reporting a vulnerability

Please do **not** open a public issue for security problems.

The preferred channel is a **private security advisory** via GitHub:
https://github.com/0xnullsect0r/MultiForge/security/advisories/new

We aim to acknowledge receipt within 3 business days and issue a coordinated advisory as appropriate. Because this is a small OSS project without a dedicated security team, timelines depend on maintainer availability — expect thoughtful engagement, not enterprise-grade SLA.

## Supported versions

Security fixes land on the latest tagged release and its immediate predecessor. Older tags are archival only.

| Version | Supported |
|---|---|
| 1.3.x | ✅ |
| 1.2.x | ✅ |
| < 1.2 | ❌ |

## Scope

Any of these are in-scope, and we appreciate reports on them:

- Server-side remote code execution, path traversal, or arbitrary file read/write triggered by an unauthenticated network peer.
- Region-ownership escape leading to world corruption, item duplication, or cross-player privilege escalation.
- Deadlocks, unbounded memory allocation, or CPU exhaustion triggered by a low-privilege in-game user.
- Any way to bypass the `OwnershipEnforcer` STRICT-mode gate that would otherwise catch a race.
- Any code path where a malicious mod jar can escape the mod-safety scanner's rules and cause the above.
- Buffer bugs or authentication bypasses in the RCON handling MultiForge patches into `net.minecraft.server.rcon.*`.

## Out of scope

- Issues in NeoForge, Vanilla Minecraft, or third-party mods that are not caused or amplified by MultiForge. Report those upstream.
- Griefing exploits in gameplay (item duping via redstone contraptions, etc.) that exist in Vanilla independent of MultiForge.
- Denial-of-service by connecting many clients — mitigate at the network layer.

## Disclosure preferences

- We prefer coordinated disclosure. Please give the maintainer time to ship a fix before publishing details.
- Credit goes to the reporter in the CHANGELOG entry for the fix; if you'd prefer anonymity, say so in the advisory.
- Bug bounties are not offered — this is a volunteer OSS project.
