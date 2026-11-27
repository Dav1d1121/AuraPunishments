# Storage (schema v3) and the network foundation

AuraPunishments 1.3.0 still runs on SQLite (`plugins/AuraPunishments/punishments.db`, single owning thread, WAL). What changed is the foundation underneath, so that the network release (shared MariaDB/MySQL across Velocity-connected servers) can be built without rewriting the plugin. Everything works standalone today.

## What 1.3.0 adds to the data model

| Addition | Where | Purpose |
|---|---|---|
| **Dialect layer** (`db/Dialect`) | all engine-specific SQL (upsert, insert-ignore, UUID generation, DDL translation, identity triggers) | SQLite is the only implementation; MariaDB/MySQL plug in behind the same interface. Any other engine is refused at startup with a clear message. |
| **Globally unique ids** (`uid`, UUID v4) | punishments, cases, evidence, notes, appeals, reviews, custody | an id that stays unique across servers and databases; existing rows were back-filled by the migration, new rows get one from a database trigger (so no code path can forget it) |
| **Originating server** (`origin`) | the same tables and the audit log | the `network.server-name` of the server that created the record |
| **Operation ids** (`op_id`) | audit log, punishments | one id per service operation (= one transaction); ties an audit entry to the action, and is the idempotency key the network release will use |
| Case-free evidence / notes / appeals | `evidence.punishment_id`, `notes.punishment_id`, `appeals.punishment_id`, `appeals.ticket` | evidence, notes and appeals attach to a punishment; `case_id` is now optional (a CHECK requires one of the two) |
| Standalone jail | `punishments.jail_prisoner_id / jail_name / jail_state / jail_error`, `pending_ops.punishment_id` | the punishment is the jail record |
| `meta.server_id`, `meta.origin_name` | meta | the generated/chosen server name |

The audit hash chain is **not** changed: `origin` and `op_id` are stored beside each entry but are not part of the hash, so chains created by older versions verify unchanged (verified in the test suite on a migrated database).

## The migration (v2 -> v3)

Runs automatically at start, in one transaction, after a file backup to `plugins/AuraPunishments/backups/punishments.db.pre-v3-<timestamp>`:
1. adds the columns above and the unique-id indexes;
2. rebuilds the `evidence` and `appeals` tables (copy rows with their ids, drop, rename) so `case_id` can be empty - **every row, id, relationship, voided flag, correction link, decision and reviewer is preserved** (verified by comparing counts and field values on a representative 1.2.x database in the tests);
3. copies each case's Ticket Tool reference onto its appeal (`cases.appeal_ticket` is kept too);
4. gives every existing record a UUID and the server name; installs the identity triggers.

If anything fails the transaction rolls back and the plugin refuses to start with the database untouched. A database from a *newer* plugin version is refused (never damaged). Re-running is a no-op. v1 databases (1.0.0) migrate straight to v3.

## Server name

`network.server-name` (letters, digits, `.` `_` `-`; for example `smp`, `towny`, `skyblock`, `lobby`). Blank = a random `srv-xxxxxxxx` is generated once and remembered. Choose it **before** the first run on a server that will join a network and keep it: changing it later is allowed (new records use the new name, old records keep theirs, a warning is logged) but splits the server's records across two origins.

## Backup / restore

Copy `punishments.db` while the server is stopped (or use the automatic pre-migration copy). Restore = stop, put the file back, start the matching plugin version. See [ROLLBACK.md](ROLLBACK.md).

## Not in 1.3.0 (planned for the network release)

Shared database, network-wide enforcement and cross-server sync, network jail, Velocity proxy support, MariaDB/MySQL drivers and pool, importing several servers' SQLite files, outage policies. 1.3.0 adds none of that, only the foundation described here.
