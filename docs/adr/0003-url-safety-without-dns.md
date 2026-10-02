# ADR-0003: URL safety validation is syntactic; no DNS resolution

**Status:** Accepted

## Context

A shortener lends its own domain's reputation to the target. Without checks it can be abused to:

- run script (`javascript:`, `data:` URLs),
- disguise a destination (`https://bank.com@evil.example`),
- point users at internal services (`http://169.254.169.254/` cloud metadata, `http://10.0.0.5/admin`),
  which is especially dangerous if any component ever fetches the target (previews, unfurling).

IP addresses can be spelled many ways that browsers accept: `127.1`, `2130706433`, `0x7f000001`,
`0177.0.0.1`, `[::ffff:127.0.0.1]`. A naive "starts with 127." check misses all of them.

We could also resolve hostnames and block those that resolve to private addresses. But DNS
answers can change between our check and the user's click (DNS rebinding). Resolution would also
add latency and a network dependency to link creation.

## Decision

`UrlSafetyValidator` performs **syntactic** checks only, in this order:

1. Length ≤ 2048. No whitespace or control characters (prevents header/log injection).
2. Parses as an absolute URI. Scheme in the allow-list `{http, https}`.
3. No user-info component.
4. Host checks:
   - IPv4 literals are parsed with `inet_aton` semantics (1–4 parts; decimal, octal, hex) and
     checked against IANA special-purpose ranges.
   - IPv6 literals are checked for loopback, unspecified, link-local, unique-local, multicast and
     documentation ranges, plus the IPv4-mapped, IPv4-compatible and NAT64 forms of blocked IPv4.
   - Hostnames: `localhost`, internal suffixes (`.localhost`, `.local`, `.internal`, `.lan`,
     `.home.arpa`) and single-label names are rejected.

No DNS lookups. We never fetch target URLs (requirement A-6).

## Consequences

- **+** Deterministic, fast, offline, fully unit-testable (55 cases, including the alternate IP spellings).
- **+** Each rejection has a specific error code (`UNSUPPORTED_SCHEME`, `BLOCKED_HOST`, …) for clients.
- **−** A public hostname that *resolves* to a private IP is accepted. Because the service never
  fetches targets, the impact is limited to the clicking user's own network, the same as any
  link on the web. If a fetching feature is added (link previews), that feature must re-validate
  the resolved IP at connection time, which is the only place it can be done correctly.
- **−** Raw Unicode (IDN) hosts are rejected because `java.net.URI` does not expose them. Clients
  must send the punycode form (`xn--…`). Acceptable for an API; a UI would convert first.
- **−** No reputation or blocklist check (e.g. Google Safe Browsing). That is the natural next
  layer and would plug in after these syntactic checks.
