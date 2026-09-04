# Security policy

## Reporting a vulnerability

Please do **not** open a public issue for security problems.

Report suspected vulnerabilities privately to
`security@multiforge.example` (PGP key TBD). We aim to acknowledge
receipt within 3 business days and issue a coordinated advisory as
appropriate.

## Scope

- License-key verification bypass or forgery.
- Server-side remote code execution, path traversal, or arbitrary file
  read/write triggered by an unauthenticated network peer.
- Region-ownership escape leading to world corruption, item duplication,
  or cross-player privilege escalation.
- Deadlocks, unbounded memory, or CPU exhaustion triggered by a
  low-privilege user.
- Any exposure of the vendor's Ed25519 signing private key.

## Out of scope

- Issues reproducible only against a modpack MultiForge has not
  advertised support for.
- Mod-authored code that has legitimate access to server state; report
  those to the individual mod author.
