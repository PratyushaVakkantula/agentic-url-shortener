# ADR-0010: Committed events must survive a hard crash

**Status:** Accepted (corrects an assumption behind ADR-0006)

## Context

ADR-0006 claims runs survive crashes, because events are committed to the database before state
changes. The automated recovery tests passed, but they "crash" by stopping the engine inside one
JVM, which never exercises the database's own durability.

The live demo did. A brownfield run was paused at an approval, the server was killed with
`kill -9`, and on restart **the whole run was gone**: every committed event, not just the last
few. Earlier runs, written longer before the kill, survived.

Isolated reproduction (a child JVM commits 50 rows one by one, then gets `SIGKILL`):

| H2 options | Rows after kill -9 (3 trials) |
|---|---|
| default | **0 / 50**, 0 / 50, 0 / 50 (even the `CREATE TABLE` was lost) |
| `WRITE_DELAY=0` | **50 / 50**, 50 / 50, 50 / 50 |

H2's file mode buffers commits for about 500 ms before writing them out. A graceful shutdown
flushes the buffer, which is why every earlier restart looked fine. A hard kill (OOM-killer,
container eviction, `kill -9`) discards it, and with it events the engine had already been told
were committed.

## Decision

- The default datasource URL sets `WRITE_DELAY=0`, so each commit is written before it returns.
- `DurabilityTest` guards it **against the configuration as shipped**: it reads the H2 options
  from `application.yml`, commits from a child JVM, `SIGKILL`s it, and requires every committed
  row to be present. Removing the option makes both of its tests fail (mutation-checked).
- Anyone overriding `DB_URL` with another H2 file URL must keep `;WRITE_DELAY=0` (documented in
  the README).

## Consequences

- **+** "Committed" now means durable against process crashes, which is what an audit trail
  requires. Verified end to end: kill -9 while waiting for approval, restart, approve the same
  approval id on the new process, run completes, nothing redone.
- **−** Each commit costs a write. That is irrelevant at this volume (one event per state change).
- **−** This protects against **process** crashes, not power loss: H2 does not `fsync` per commit.
  H2 is the zero-setup local database; production durability comes from PostgreSQL with its
  default `synchronous_commit = on` (WAL fsync per commit), a configuration change only (NFR-8).
- **Lesson:** a durability claim needs a test that kills a *process*, not just a thread. In-JVM
  "crash" tests cannot see what the storage layer buffers.
