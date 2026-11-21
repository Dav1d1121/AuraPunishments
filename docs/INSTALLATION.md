# AuraPunishments 1.1.0 - Installation, Upgrade, Deployment and Rollback

## Before you start

1. Back up the server (at minimum `plugins/JailReloaded/` and, if present, `plugins/AuraPunishments/`).
2. Confirm: Paper **26.3**, Java **25**, JailReloaded **1.2.x** (tested 1.2.2), LuckPerms installed.
3. Read [PERMISSIONS.md](PERMISSIONS.md) so staff access is granted deliberately; nothing is granted by default.

## Install

1. Stop the server.
2. Copy `AuraPunishments-1.1.0.jar` into `plugins/`. (SHA-256 is published next to the jar; see `release/`.)
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

## Upgrade from 1.0.0 (step by step)

1. **Back up first.** Stop the server and copy the whole `plugins/AuraPunishments/` folder (the `.db`, `-wal`, `-shm` files and the YAMLs) somewhere safe, plus `plugins/JailReloaded/`.
2. Replace `AuraPunishments-1.0.0.jar` with `AuraPunishments-1.1.0.jar` (verify the SHA-256 in `release/`) and start the server.
3. The database migrates from schema v1 to v2 automatically, **after copying the old database to `plugins/AuraPunishments/backups/punishments.db.pre-v2-<timestamp>`**. If the migration fails, the plugin disables itself and leaves your data untouched.
4. What the migration does with your history: every record is kept as it was. 1.0.0 *quick* mutes / bans / IP bans are imported into the review table as **Legacy (pre-review)** - they are *not* put in the pending queue and *nothing* is posted to Discord for them. Warnings that were never shown to a player (still queued) are carried over so they are still delivered at next login (once).
5. **Your `config.yml` is never modified.** New options use built-in defaults until you add them. A reference copy of the full 1.1.0 file is written once as `config-1.1.0-reference.yml` next to your config so you can adopt what you want. Your existing custody deadlines (default 48h review + 12h grace) and every other value are unchanged. Your `messages.yml` is also untouched; new messages fall back to the built-in text.
6. A 1.0.0 `discord:` block (one webhook, per-event overrides) keeps working exactly as before. New 1.1.0 events (warnings, quick punishments, review activity, security...) are **not** posted until you add the `discord.channels` section or list the event under `discord.events`.
7. Grant the new permission nodes you want ([PERMISSIONS.md](PERMISSIONS.md) section 3b). Nothing new is granted automatically; the owner (`aurapunishments.*`) already has all of them.
8. Run the deployment test below, then `/ap status` (shows Discord and CoreProtect state).

The same upgrade was performed during testing on a real 1.0.0 database produced by the earlier live runs (see `docs/evidence/upgrade-from-1.0.0-real-data-boot.txt`).

## Discord webhooks (optional)

See [DISCORD_WEBHOOKS.md](DISCORD_WEBHOOKS.md). In short: create one webhook per log channel in Discord (Channel settings -> Integrations -> Webhooks), put the URLs in `plugins/AuraPunishments/webhooks.yml` (or use `${env:NAME}` in `config.yml`), set `discord.enabled: true`, `/ap reload`. AuraPunishments never DMs anyone and never creates channels.

## Optional integrations

* **CoreProtect 25.x** - read-only evidence lookups (`/punish` -> case -> *CoreProtect lookup*). Nothing is ever rolled back.
* **TownyChat** - nothing to configure: mutes are enforced on its chat events and the channel/PM commands.
* **AuraCommandFilter** - nothing to configure: every command declares its permission.

## Rollback

| Situation | Action |
|---|---|
| New jar misbehaves, **no schema change** | Stop, put the previous jar back, start. |
| New jar migrated the database | Stop, put the previous jar back, **restore the matching backup** from `plugins/AuraPunishments/backups/punishments.db.pre-vN-<timestamp>` over `punishments.db` (delete the `-wal`/`-shm` files). A plugin refuses to start on a *newer* schema than it understands, so a mismatch can never silently corrupt data. |
| Remove the plugin entirely | Stop, delete the jar. Prisoners already held by JailReloaded **stay jailed** (it owns them): release them with `/unjail`, or let their sentences end. Active bans/mutes issued through AuraPunishments are *not* enforced without the plugin - re-issue them in your replacement system first (the database is plain SQLite, e.g. `SELECT * FROM punishments WHERE status='ACTIVE'`). |

## Building from source

```
./gradlew build            # JDK 25 required (toolchain); produces build/libs/AuraPunishments-1.1.0.jar
./gradlew test             # 196 unit/integration tests (JUnit 5, MockBukkit)
./gradlew :live-test:jar   # the live-server verification driver (see docs/TEST_REPORT.md)
```

The custom/proprietary plugin jars (`JailReloaded-1.2.2.jar`, `AuraCommandFilter-1.0.0.jar`) and the third-party jars used only to compile/test (`CoreProtect-25.1.jar`, `TownyChat-0.122.jar`) are **not** stored in the repository: place them in `libs/` yourself (see `libs/README.md`). JailReloaded and CoreProtect are `compileOnly` and never bundled.
