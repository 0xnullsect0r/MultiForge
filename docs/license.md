# MultiForge License Token Format

MultiForge verifies its license fully offline at boot. This document
describes the token format so it can be independently re-implemented by the
purchase site's signing backend.

## Token wire format

```
<base64url(payload)> . <base64url(signature)>
```

Two base64url segments joined by a literal `.`. Padding (`=`) is optional
and stripped on decode. No whitespace anywhere.

## Payload

The payload is a CBOR-encoded map with these keys:

| Key        | Type              | Meaning                                                |
|------------|-------------------|--------------------------------------------------------|
| `v`        | uint              | Schema version. Current: `1`.                          |
| `iss`      | text              | Issuer domain, e.g. `multiforge.example`.              |
| `sub`      | text              | Opaque customer id, e.g. `cust_abcd1234`.              |
| `iat`      | uint              | Issued-at time, Unix seconds.                          |
| `nbf`      | uint              | Not-before time, Unix seconds. Equal to `iat`.         |
| `exp`      | uint \| null      | Expiry, Unix seconds. `null` = perpetual.              |
| `features` | \[text\]          | Set of feature flags. v1 always contains `"core"`.     |
| `kid`      | text              | Public-key id (for key rotation), e.g. `prod-2025`.    |

The current schema (`v=1`) has a single feature: `core` (server boot).
Future tiers add more entries to `features`.

## Signature

`Ed25519(private_key, payload_bytes)`, where `payload_bytes` is the exact
byte sequence that was base64url-encoded into the first segment.

The private key lives on the purchase site. The corresponding public key
(and its `kid`) is baked into the MultiForge server jar at build time.

## Verification (server side)

At boot, before any Vanilla initialization, `LicenseGate.bootstrap()`:

1. Resolves the token from (in order):
   - `MULTIFORGE_LICENSE` environment variable,
   - JVM system property `multiforge.license`,
   - `license.key` file in the server working directory.
2. Splits on `.`, base64url-decodes both halves.
3. CBOR-decodes the payload map.
4. Looks up the public key by `kid`. If unknown → refuse.
5. Verifies the Ed25519 signature. If invalid → refuse.
6. Asserts `nbf <= now`. If in future → refuse.
7. Asserts `exp == null || now < exp`. If expired → refuse.
8. Asserts `"core"` is in `features`. If not → refuse.

Refusal writes a single line to stderr and calls `System.exit(78)`
(`EX_CONFIG` per `sysexits.h`). Success is silent — the server logs a
one-line `[MultiForge] License OK: sub=<sub> exp=<exp>` at INFO.

## Providing the token to a running server

**Docker (preferred):**

```yaml
services:
  minecraft:
    image: ghcr.io/multiforge/multiforge-server:1.0.0
    environment:
      EULA: "TRUE"
      MULTIFORGE_LICENSE: "eyJ2IjoxLC..."
```

**Bare metal:**

```
java -Dmultiforge.license="eyJ2IjoxLC..." -jar multiforge-server.jar nogui
```

**File:**

Write the token (nothing else, no trailing whitespace) to `license.key` in
the server directory. `chmod 600` is recommended.

## Key rotation

`LicenseGate` holds `Map<String, PublicKey>` keyed by `kid`. New MultiForge
builds ship additional public keys without breaking any previously-issued
token. To retire a compromised key, ship a build with that `kid` removed;
tokens signed by that key stop verifying at that version and later.

## Security notes

- Ed25519 (RFC 8032) is a well-analyzed elliptic-curve signature scheme
  with 128-bit security. Signature is 64 bytes, public key is 32 bytes.
- Verification is deterministic and does not require secure random.
- The signature is over the raw payload bytes, **not** over the base64url
  string, so a re-encoded token with different padding still verifies.
- Because verification is fully offline, **tokens can be copied**. That is
  an accepted trade-off for the no-phone-home requirement. The signed
  `sub` field lets support identify a leaked token by its customer.
- The private key MUST NOT be shipped with the server. `.gitignore`
  already excludes `*.private.pem`, `*.priv`, `private-key.*`.

## Reference implementations

- Server-side verifier: `multiforge-license/`.
- CLI signer (used by purchase site or an operator with the private key):
  `multiforge-license-cli/`.
