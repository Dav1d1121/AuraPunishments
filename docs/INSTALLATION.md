# AuraPunishments 1.0.0 - Installation, Deployment and Rollback

## Before you start

1. Back up the server (at minimum `plugins/JailReloaded/` and, if present, `plugins/AuraPunishments/`).
2. Confirm: Paper **26.3**, Java **25**, JailReloaded **1.2.x** (tested 1.2.2), LuckPerms installed.
3. Read [PERMISSIONS.md](PERMISSIONS.md) so staff access is granted deliberately; nothing is granted by default.

## Install

1. Stop the server.
2. Copy `AuraPunishments-1.0.0.jar` into `plugins/`. (SHA-256 is published next to the jar; see `release/`.)
3. Make sure JailReloaded has an enabled jail (see the Admin handbook, "Required JailReloaded setup").
4. Start the server and read the startup summary (`JailReloaded: ... (compatible)`, `LuckPerms: connected`).
5. Grant permissions with the LuckPerms commands from [PERMISSIONS.md](PERMISSIONS.md) section 3. At minimum for the
   owner: `lp group owner permission set aurapunishments.* true`, `aurapunishments.limit.*`, `aurapunishments.level.100`.
6. Remove EssentialsX punishment permissions ([PERMISSIONS.md](PERMISSIONS.md) section 4).
7. Optional: configure the Discord webhook, then `/ap reload`.

## Recommended controlled-deployment test (30 minutes)

Do this on the live server with two staff accounts and one test player before announcing it:

1. `/punish <testplayer>` opens the GUI with the correct head/status; a staff account with only `aurapunishments.punish` is refused.
2. Create a case with evidence; start **pretrial detention**. Confirm the player is teleported to the jail (online) and
   that `/jail check` / JailReloaded lists the prisoner with reason `[AP-000N] ...`.
3. Try `/unjail <testplayer>` as an ordinary admin: it must be refused with the case reference.
4. As the owner: assign yourself as judge, move the case to *In Trial*, enter **Not Guilty** - the player must be released.
5. Repeat and enter **Guilty**, sentence a short **jail** sentence: the *same* JailReloaded record must show the remaining time.
6. Mute the player; confirm public chat, `/msg`, `/r`, `/g` (and your TownyChat channels) are blocked.
7. Ban the test player; confirm login is refused with the ban screen (no IP shown), then revoke the ban.
8. `/ap status` and `/ap audit verify` report healthy.
9. Restart the server; confirm bans/mutes/custody persisted and `/ap status` is clean.

## Upgrade

1. Stop the server, **back up `plugins/AuraPunishments/`** (the `.db`, `-wal`, `-shm` files and the three YAMLs).
2. Replace the jar and start. Schema migrations run automatically and copy the old database to `backups/` first.
3. New config keys are added by editing `config.yml`; a missing key uses its built-in default, so an old file keeps working.
   `messages.yml` keys you delete fall back to the built-in text.

## Rollback

| Situation | Action |
|---|---|
| New jar misbehaves, **no schema change** | Stop, put the previous jar back, start. |
| New jar migrated the database | Stop, put the previous jar back, **restore the matching backup** from `plugins/AuraPunishments/backups/punishments.db.pre-vN-<timestamp>` over `punishments.db` (delete the `-wal`/`-shm` files). A plugin refuses to start on a *newer* schema than it understands, so a mismatch can never silently corrupt data. |
| Remove the plugin entirely | Stop, delete the jar. Prisoners already held by JailReloaded **stay jailed** (it owns them): release them with `/unjail`, or let their sentences end. Active bans/mutes issued through AuraPunishments are *not* enforced without the plugin - re-issue them in your replacement system first (the database is plain SQLite, e.g. `SELECT * FROM punishments WHERE status='ACTIVE'`). |

## Building from source

```
./gradlew build            # JDK 25 required (toolchain); produces build/libs/AuraPunishments-1.0.0.jar
./gradlew test             # 126 unit/integration tests (JUnit 5, MockBukkit)
./gradlew :live-test:jar   # the live-server verification driver (see docs/TEST_REPORT.md)
```

`libs/` contains the supplied custom plugin jars (`JailReloaded-1.2.2.jar`, `AuraCommandFilter-1.0.0.jar`). JailReloaded is
compiled against (`compileOnly`) and never bundled; AuraCommandFilter is used only by the live tests.
