# ADR-0002: Random short codes with database-enforced uniqueness

**Status:** Accepted

## Context

Every link needs a short, URL-safe, unique code. Common approaches:

| Approach | Unique by construction | Predictable / enumerable | Coordination needed | Notes |
|---|---|---|---|---|
| Auto-increment ID → base62 | Yes | **Yes**: `/aaab` follows `/aaaa`, so anyone can crawl every link | DB sequence | Leaks volume ("we have 4M links") |
| Hash of URL (MD5/SHA → truncate) | No (truncation collides) | Yes for known URLs | None | Same URL ⇒ same code, conflicts with A-1 |
| Snowflake-style ID | Yes | Partly (time-ordered) | Worker IDs | Codes are long (~11 chars base62) |
| Pre-generated key pool | Yes | No | A key service + pool refill | Most moving parts; right at very large scale |
| **Random base62 + unique constraint + retry** | No, but collisions are rare and handled | **No** | None | Simple, stateless, secure |

Short links are often shared privately (documents, invites). Enumerable codes would let anyone
discover them, which is a real privacy issue, not a theoretical one.

## Decision

- 7 random characters from base62 (`[0-9A-Za-z]`) via `SecureRandom.nextInt(62)`, which is unbiased.
  Code space is 62^7 ≈ 3.5 × 10¹².
- Uniqueness is enforced by the **database** (`UNIQUE (code)`), never by check-then-insert
  alone. The pre-check is only a fast path. The constraint is the guarantee.
- Collisions are retried up to `app.shortener.max-code-attempts` (default 5). Exhausting the
  budget throws `CodeSpaceExhaustedException` (HTTP 503) instead of looping forever.
- Each attempt is its own transaction, because a constraint violation poisons an enclosing
  transaction.

## Consequences

- **+** Unpredictable codes. No coordination between instances. Stateless generator.
- **+** Collision probability stays negligible for a long time: at 10 million links, a new code
  collides with probability ~10⁷ / 3.5×10¹² ≈ 3×10⁻⁶ per attempt, and 5 attempts all colliding
  is ~10⁻²⁷.
- **−** Not unique by construction, so it needs retry logic, covered by tests including a
  concurrent race (`concurrentClaimsOfSameAliasProduceExactlyOneWinner`).
- **−** As the table approaches billions of rows, collision rate rises. The response is to
  raise `code-length` to 8 (a config change, since the column fits 30 chars) or to move to a
  key pool. A rising collision-retry log rate is the early warning.
