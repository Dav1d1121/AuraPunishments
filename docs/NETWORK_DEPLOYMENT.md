# Running AuraPunishments on a network (1.4.0)

For server owners and administrators. One jar, one shared database, every backend enforcing the same punishments. **A single server keeps working exactly as before** (SQLite, nothing to configure); read this page only if you run a proxy with several backends.

```
                      Velocity / BungeeCord
        ┌───────────────┬──────┴───────┬───────────────┐
     lobby          survival         towny         skyblock         jail  (the jail backend: JailReloaded lives only here)
        └───────────────┴──────┬───────┴───────────────┘
                     MariaDB 10.6+ / MySQL 8+   (one dedicated database)
```

* **Backend-only.** The plugin runs on every Paper backend and nowhere else. **No proxy plugin is needed or exists.** Everything the network needs (bans at login, kicks, mutes, warnings, transfers to the jail) works through the backends and the shared database; the live lab (see [TEST_REPORT.md](TEST_REPORT.md)) found nothing that needs code on the proxy.
* **One database, no per-server copies.** Punishments, cases, evidence, notes, appeals, audit log and history are central. A ban made on one backend is in force on every backend at once.
* **Names.** Each backend needs its own `network.server-name` (`lobby`, `survival`, `towny`, `skyblock`, ... - any names you like).

## 1. Requirements

| | |
|---|---|
| Database | **MariaDB 10.6+** (the primary test target; 10.11 tested) or **MySQL 8+**. A **dedicated** database and user (do not share LuckPerms' database). InnoDB, `utf8mb4`. |
| Database privileges | `SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, TRIGGER, REFERENCES` on that database. (`CREATE` on `*.*` only if you use `database.create-database: true`.) |
| Clocks | Every server (backends **and** the database host) must be time-synchronised (NTP). Expiry times are absolute timestamps. A backend whose clock differs from the database server's by more than `network.max-clock-skew-seconds` (default 10) refuses to start and says so. |
| Java / Paper | The same jar runs on Paper **1.21.11** (Java 21+) and **26.3** (Java 25), and may run on different versions on different backends. |
| Proxy | Velocity (recommended) or BungeeCord, with the backends reachable **only** through it (modern/secure forwarding). |
| Network jail (optional) | JailReloaded on **one** backend. |

MySQL with binary logging on can refuse the plugin's triggers with *"You do not have the SUPER privilege and binary logging is enabled"*: either grant `SET_USER_ID`/`SUPER` once for the installation, or set `log_bin_trust_function_creators = 1`. MariaDB with default settings needs neither.

## 2. Create the database

```sql
CREATE DATABASE aurapunishments CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'aurapunishments'@'10.0.0.%' IDENTIFIED BY 'a-long-random-password';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, TRIGGER, REFERENCES ON aurapunishments.* TO 'aurapunishments'@'10.0.0.%';
```

Restrict the user to your backends' addresses; use `database.ssl: true` when the database is not on a private network.

## 3. Configure every backend

`plugins/AuraPunishments/config.yml` (the same on every backend except `network.server-name`):

```yaml
network:
  server-name: "survival"        # UNIQUE per backend: lobby, survival, towny, skyblock, ...
  proxy-name: ""                 # only if your proxy calls this backend something else
  sync-interval-ms: 1000         # how quickly other backends' changes reach this one (logins never wait for it)

database:
  type: mariadb                  # or mysql
  host: 10.0.0.5
  port: 3306
  name: aurapunishments
  username: aurapunishments
  password: "${env:AP_DB_PASSWORD}"   # keep the password out of the file if you like
  allow-migration: false         # see section 8
```

Start the **first** backend with `allow-migration: true`: it creates the schema (a brand-new empty database never needs the flag; it is only for upgrading an existing shared database). Then start the others. Every backend writes its heartbeat to the registry; `/ap network` shows them all:

```
Network: server 'survival' (instance 1b2c...), change feed at event 4711, 912 applied
Network jail: backend 'jail', 0 jailed, 0 being sent back, return to original
  - jail v1.4.0 (schema 4) running [jail]
  - lobby v1.4.0 (schema 4) running
  - survival v1.4.0 (schema 4) running <- this server
Discord messages are sent by: lobby
Network-wide housekeeping runs on: survival
```

**Safety nets that stop a bad rollout**

* A second server that starts with a `server-name` already in use by a live server is refused (`allow-duplicate-name: true` is for emergencies only).
* A plugin that finds a database from a **newer** schema refuses to start; an **older** schema is only upgraded by the server you told to (`allow-migration`). An upgrade that was interrupted blocks every server until you look at it, instead of letting them run on half a schema.
* The schema is checked on every start; servers with different plugin versions can run together as long as they share the schema version.

## 4. The proxy

Velocity (`velocity.toml`):

```toml
online-mode = true                               # (false only for an offline-mode network)
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
[advanced]
bungee-plugin-message-channel = true            # the default; the network jail uses it to move players
```

Every backend (`config/paper-global.yml`): `proxies.velocity.enabled: true`, `online-mode: true`, `secret: <the same secret>`; `server.properties` `online-mode=false` and firewall the backends so only the proxy can reach them.

**Player addresses.** With forwarding the backend sees the *player's* address, never the proxy's. Without it every player would appear to come from the proxy, so the plugin protects you twice:

* IP bans refuse private, loopback, link-local and CGNAT addresses (`punishments.ip-bans.refuse-private-addresses: true`, the shipped default), addresses listed in `exclude-addresses`, and addresses shared by more than `max-accounts-per-address` accounts. An IP ban recorded earlier on such an address is **not** enforced against everybody who shares it - only against the banned account itself.
* If you see *"is not a public player address"* when you IP ban someone, your forwarding is not set up: fix that first.

**Mixed Minecraft versions.** Velocity does not translate between game versions. If your backends run different versions (one jar supports 1.21.11 and 26.3), your players need ViaVersion/ViaBackwards (on the proxy or backends) to move between them, **including the jail backend**, which every jailed player must be able to join.

## 5. The network jail

JailReloaded is only needed on **one** backend. Without this feature every backend that jails needs its own JailReloaded and a jailed player walks away by changing server.

1. Install JailReloaded on the jail backend only, build the jail there (`/jail create ...`), and set `punishments.jail.jail-name` (and `cell-name`) in **that backend's** AuraPunishments config.
2. On **every** backend set the same value (it is the jail backend's `network.server-name`):
   ```yaml
   network:
     jail:
       backend: "jail"
       return:
         mode: ORIGINAL        # ORIGINAL | LOBBY | FIXED
         lobby-server: lobby   # the PROXY's name; also the fallback of ORIGINAL
         fixed-server: ""
       hold:
         enabled: true
         allowed-commands: []
       transfer-retry-seconds: 5
   ```
3. Restart the backends.

How it behaves:

* `/ap jail` (or the jail button / a case's jail punishment) on **any** backend records the jail, remembers where the player was, and tells the jail backend. The player is sent there through the proxy's plugin-message channel and JailReloaded jails them. If they are offline, the first login on any backend sends them there.
* A jail is only **accepted** while the jail backend is running (its heartbeat is fresh); otherwise staff get *"Nobody was jailed"* - never a half-recorded jail.
* **No escape.** From the moment a jail is recorded until JailReloaded holds the player, they are *held* on whichever backend they are on: they cannot move, build, fight, chat or run commands (`hold.allowed-commands` lists exceptions). This covers a slow transfer, a login on the wrong backend and a jail backend that is down or frozen: the transfer is repeated every `transfer-retry-seconds` until it works. A jail that never reached JailReloaded runs out by the clock.
* **Release** (`/ap unjail`, a revocation, an appeal, the sentence ending) works from any backend. The jail backend frees the prisoner, lifts the hold everywhere and sends the player back: `ORIGINAL` = the backend they were on when jailed (the lobby when unknown or unreachable), `LOBBY`, or `FIXED`. A failed target falls back to the lobby; a player who is offline simply logs in wherever the proxy sends them.
* Time changes (an appeal that shortens or lengthens a jail) are applied to JailReloaded by the jail backend, once.
* **Pretrial detention and court jail sentences** (the judicial system, off by default) are carried out by the jail backend only; staff on other backends get a clear refusal. The case workflow's jail punishment works everywhere.
* If JailReloaded already holds the player by hand, the network jail does not pretend: the punishment is closed as *"Jail was not started"* and staff are told.

## 6. When the database is unreachable

| What | Behaviour |
|---|---|
| Bans, mutes the server already knew | **Keep being enforced** (an in-memory copy, also saved to `plugins/AuraPunishments/active-punishments.yml` every few seconds and on shutdown, so it also survives a restart during the outage). |
| New connections | `network.outage.policy: ENFORCE_KNOWN` (default) lets players in, enforcing what is known; `DENY_ALL` refuses new connections (operators excepted) until the database is back. |
| Staff actions | **Refused** with *"The database is unavailable right now. Nothing was changed."* - never reported as done. |
| Jail holds | Held players stay held (the hold is in memory); transfers and releases wait. |
| Start-up | `network.outage.start-without-database: true` lets a backend start from the saved copy and retry every `retry-seconds`. A *schema/version/config* problem is **never** treated as an outage - the plugin disables itself with the reason. |
| Recovery | Automatic: the backend rejoins the registry, reloads from the database and resumes (no restart). Punishments made on other backends meanwhile appear; nothing is merged or guessed. |

## 7. Discord across the network

Every event is posted **once**: the outbox is shared and exactly one backend at a time (a lease; `/ap network` shows which) sends, pacing Discord's rate limit; if it dies another takes over when the lease runs out. A row is claimed before it is sent, the same event reported by two backends is one row, and failures retry with back-off. Keep the `discord:` section identical on every backend (the stored rows name destinations, the sender looks the URL up in its own config). A crash in the milliseconds between Discord accepting a message and the database recording it can repeat that one message; nothing is ever lost silently.

## 8. Upgrading, migrating, rolling back

**Moving existing servers onto the shared database.** Each old server has its own SQLite file. Import them (all or nothing, repeatable, the original untouched):

1. Stop the old server; copy its `plugins/AuraPunishments/punishments.db` (and `-wal`/`-shm` if present) into `plugins/AuraPunishments/migration/` of **any one** backend that is on the shared database. Name each file after its server (`survival.db`, `towny.db`).
2. Make sure nothing is unfinished on the old server: no pretrial detention, no interrupted jail operation. The check tells you.
3. `/ap migrate check survival.db survival` - a dry run: reads everything, writes nothing, lists what it would import and any problem. A report is also saved next to the file.
4. `/ap migrate run survival.db survival confirm` - the real import in **one transaction**: it all happens or none of it does.
5. Repeat for the next file. Running the same file again, or a copy of it, adds nothing.

What the import guarantees: ids are remapped and every reference is kept (a punishment still points at its case, evidence at its case or punishment, reviews at their punishment); **nothing that ended comes back to life** (an ended or revoked punishment stays ended; a ban still marked active whose time ran out is imported as expired; when two old servers hold an active ban or mute for the same player the stricter one stays active and the other is recorded as superseded); profiles are merged by player; the audit entries are appended to the shared hash chain with a note of where they came from; every row is stamped with the source server. A jail still being served blocks the import unless you add `convert-jails` (it is then queued for the jail backend; release the old JailReloaded record by hand). The import runs on its own database connection, so logins on that backend are not held up; staff actions that write the audit log wait briefly while the audit entries are appended at the end.

**Backups and rollback.** Before the first import take a database dump (`mysqldump --single-transaction aurapunishments > before-import.sql`) and keep the old SQLite files - they are never modified. To roll back an import: stop every backend, restore the dump (or `DROP`/recreate the database and import again), start. Nothing else needs undoing. To go back to a standalone server, stop, set `database.type: sqlite`, start with your last SQLite file.

**Upgrading the plugin version later.** Stop **all** backends, back up, set `database.allow-migration: true` on one, start it, set it back to `false`, start the rest.

## 9. Security notes

* The database password never appears in logs or `/ap` output; use `${env:AP_DB_PASSWORD}` if you want it out of the file. Connection settings that fail are reported without the password.
* The import only reads files in `plugins/AuraPunishments/migration/` (plain file names, no paths) and only for administrators (`aurapunishments.admin`).
* Webhook URLs are never stored in the database (only a destination token).
* The audit log is one hash chain for the whole network (entries appended by several servers at the same moment take turns); `/ap audit` verifies it.
* Players cannot reach another backend's console, database or files; the plugin adds no listening port.

## 10. Commands and permissions

| Command | Permission | What |
|---|---|---|
| `/ap network` | `aurapunishments.admin` | the registry, who is the Discord sender / housekeeping leader, the change feed, the network jail |
| `/ap migrate [check\|run <file> [label] [convert-jails] confirm]` | `aurapunishments.admin` | import an old server's SQLite database |
| `/ap status` | `aurapunishments.admin` | now also shows the storage (standalone or shared) and, during an outage, the DEGRADED state |

No new permission nodes. Hierarchy (who may punish whom) works as before on every backend; give every backend the same LuckPerms data (shared LuckPerms storage) so staff levels are the same everywhere.

## 11. Troubleshooting

| You see | Meaning / fix |
|---|---|
| `Another server is already running as 'survival'` | two backends share a `server-name`; rename one (a crashed server's entry expires after 45 s) |
| `This server's clock differs from the database server's by N s` | fix NTP |
| `Database schema vN is newer than this plugin` | an updated backend has already migrated; update this one |
| `has not been migrated ... allow-migration` | start one backend with `database.allow-migration: true` once (all stopped, backup taken) |
| `The jail backend 'jail' is not running` | the jail backend's heartbeat is older than 45 s; start/repair it. Jails already ordered stay held and are carried out when it returns. |
| `... is not a public player address` | forwarding is not configured; see section 4 |
| `The database is unavailable right now. Nothing was changed.` | see section 6; the plugin recovers by itself |
| A player is stuck "held" | the jail backend cannot take them (down, or no common Minecraft version); `/ap unjail <player> <reason>` releases from any backend |

## 12. What was verified

See [TEST_REPORT.md](TEST_REPORT.md): unit/integration suites on SQLite and a real MariaDB, and a live network of Velocity + five Paper backends on Minecraft 26.3 and 1.21.11 (bans, mutes, warnings, kicks, jail and transfers, a frozen and a killed jail backend, concurrent staff actions, a database outage and recovery, restarts, an import of a real 1.0.0 database). **Not covered:** MySQL 8 as the server (MariaDB is the verified engine; the SQL is portable and the unit suite is engine-parameterised), CrossChat and other cross-server chat plugins (not available to test; mutes are enforced at the chat event of the backend the player speaks on, so a relayed message is never created), LuckPerms with shared storage across backends (levels come from LuckPerms permission data on each backend).
