# AuraPunishments 1.3.0 - Installation, Upgrade, Deployment and Rollback

## Before you start

1. Back up the server (at minimum `plugins/JailReloaded/` and, if present, `plugins/AuraPunishments/`).
2. Confirm: Paper **26.3**, Java **25**, JailReloaded **1.2.x** (tested 1.2.2), LuckPerms installed.
3. Read [PERMISSIONS.md](PERMISSIONS.md) so staff access is granted deliberately; nothing is granted by default.

## Install

1. Stop the server.
2. Copy `AuraPunishments-1.3.0.jar` into `plugins/`. (SHA-256 is published next to the jar; see `release/`.)
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

## Upgrade to 1.3.0 (from 1.2.x, 1.1.x or 1.0.0)

1. Stop the server and **copy `plugins/AuraPunishments/`** (the plugin also makes its own backup, `backups/punishments.db.pre-v<N>-<time>` - N = 3 from 1.1.x/1.2.x, 2 from 1.0.0 - before migrating).
2. Replace the old jar with `AuraPunishments-1.3.0.jar` and start. The log shows `Database migrated to schema v3`.
3. **Nothing changes by itself.** Your `config.yml` has no `judicial.enabled` line, so the judicial system stays **on** and everything behaves as in 1.2.x. `config.yml`, `messages.yml`, `offenses.yml` are never rewritten; the console prints how to see the new options (`config-1.3.0-reference.yml`).
4. Optional, in your own time: give the server a name (`network.server-name`), decide whether to turn the judicial system off ([JUDICIAL-MODE.md](JUDICIAL-MODE.md) - read it first if you have open cases or detainees), grant `aurapunishments.appeal.decide` to appeal reviewers ([APPEALS.md](APPEALS.md)), grant `aurapunishments.punish.jail` for jail punishments ([JAIL.md](JAIL.md)), and look at the Discord punishment log options ([DISCORD_WEBHOOKS.md](DISCORD_WEBHOOKS.md)).
5. Rollback: [ROLLBACK.md](ROLLBACK.md) - 1.2.x refuses to start on a v3 database, so restore the pre-migration backup with the old jar.

### A brand-new install of 1.3.0

The shipped `config.yml` has `judicial.enabled: false`: punishments are final and immediate, evidence and notes attach to punishments, jail is a punishment, appeals are recorded against punishments. Set `network.server-name` (for example `smp`) **before** first use if this server will ever join a network.

## Upgrade to 1.2.1 (from 1.2.0, 1.1.x or 1.0.0)

1. Stop the server, back up `plugins/AuraPunishments/`.
2. Delete the old AuraPunishments jar, put `AuraPunishments-1.2.1.jar` in `plugins/`, start the server.
3. No migration, no config or permission changes are required. Text entry is now a dialog window instead of chat; there is nothing to configure (`gui.prompt-timeout-seconds` still sets how long a dialog may stay open).
4. The CoreProtect `log-cancelled-chat` advice from 1.1.1 no longer applies (nothing is typed into chat).
5. Rollback: put the previous jar back (same schema).

## (history) Upgrade from 1.1.0 to 1.1.1 (hotfix)

1. Stop the server, back up `plugins/AuraPunishments/`.
2. Replace `AuraPunishments-1.1.0.jar` with `AuraPunishments-1.2.1.jar`, start the server.
3. Nothing else changes: **no database migration, no configuration change, no permission change**. Your `config.yml`, `messages.yml`, database and permissions are used as they are.
4. If CoreProtect is installed, the startup log may print *"CoreProtect is configured with log-cancelled-chat: true ..."*. To keep prompt answers out of CoreProtect's chat log set `log-cancelled-chat: false` in `plugins/CoreProtect/config.yml` and run `/co reload`.
5. 1.1.1 now loads *before* TownyChat / EssentialsX / DiscordSRV (see the changelog). This is automatic; no action needed. If you have your own chat plugin that must see chat before AuraPunishments, tell us - it is not supported for prompt answers.
6. Rollback: stop, put the 1.1.0 jar back, start (the schema is the same).

## Upgrade from 1.0.0 (step by step)

1. **Back up first.** Stop the server and copy the whole `plugins/AuraPunishments/` folder (the `.db`, `-wal`, `-shm` files and the YAMLs) somewhere safe, plus `plugins/JailReloaded/`.
2. Replace `AuraPunishments-1.0.0.jar` with `AuraPunishments-1.2.1.jar` (verify the SHA-256 in `release/`) and start the server.
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
./gradlew build            # JDK 25 required (toolchain); produces build/libs/AuraPunishments-1.2.1.jar
./gradlew test             # 196 unit/integration tests (JUnit 5, MockBukkit)
./gradlew :live-test:jar   # the live-server verification driver (see docs/TEST_REPORT.md)
```

The custom/proprietary plugin jars (`JailReloaded-1.2.2.jar`, `AuraCommandFilter-1.0.0.jar`) and the third-party jars used only to compile/test (`CoreProtect-25.1.jar`, `TownyChat-0.122.jar`) are **not** stored in the repository: place them in `libs/` yourself (see `libs/README.md`). JailReloaded and CoreProtect are `compileOnly` and never bundled.
