# AuraPunishments

GUI-based moderation, judicial case management and punishment enforcement for the **Aurehaven Towny SMP**.
Paper **26.3** - Java **25** - integrates with **JailReloaded** (imprisonment), **LuckPerms** (authority),
**AuraCommandFilter** (command visibility), EssentialsX/Towny/TownyChat (chat and command enforcement) and Discord webhooks.

* Main command: **`/punish <player>`** - the player's complete disciplinary GUI. Also `/punishments`, `/ap help|case|cases|history|judge|reload|...`.
* Standard path: **case -> pretrial detention (JailReloaded) -> Discord hearing -> verdict -> sentence** (owner is the judge).
* Exception path: **direct punishment** for proven major offenses (evidence + justification) and **emergency actions**.
* Routine **warnings** (title + sound + chat, queued for offline players) and immediate **quick punishments** that also go to an **owner review** queue (never automatic, never reversed by a timer).
* Work queue, defendant notifications, case export, read-only CoreProtect evidence, Discord channel routing (**1.1.0**).
* AuraPunishments never implements a jail: JailReloaded imprisons; this plugin records, verifies and reconciles.

## Documentation

| Document | For |
|---|---|
| [docs/INSTALLATION.md](docs/INSTALLATION.md) | Install, controlled-deployment test, upgrade, **rollback** |
| [docs/ADMIN_HANDBOOK.md](docs/ADMIN_HANDBOOK.md) | Configuration, database, integrations, Discord, maintenance, troubleshooting |
| [docs/STAFF_HANDBOOK.md](docs/STAFF_HANDBOOK.md) | `/punish`, cases, evidence, detention, direct punishment, history |
| [docs/JUDICIAL_HANDBOOK.md](docs/JUDICIAL_HANDBOOK.md) | Hearings, verdicts, sentencing, acquittal/release, appeals, reversals |
| [docs/DISCORD_WEBHOOKS.md](docs/DISCORD_WEBHOOKS.md) | Discord channel/webhook setup and operation (1.1.0) |
| [docs/RELEASE_NOTES.md](docs/RELEASE_NOTES.md) / [docs/BUG_FIX_SUMMARY.md](docs/BUG_FIX_SUMMARY.md) / [docs/ROLLBACK.md](docs/ROLLBACK.md) | What changed, defects fixed, how to roll back |
| [docs/PERMISSIONS.md](docs/PERMISSIONS.md) | Every permission node, per-rank recommendations, **LuckPerms commands** |
| [docs/INTEGRATION_REPORT.md](docs/INTEGRATION_REPORT.md) | What was inspected in each plugin, how it is integrated, what was verified |
| [docs/TEST_REPORT.md](docs/TEST_REPORT.md) | Test evidence, results, limitations, readiness assessment |
| [docs/RELEASE_NOTES.md](docs/RELEASE_NOTES.md) | Features and **known limitations** |
| [release/](release/) | `AuraPunishments-1.1.0.jar`, its SHA-256 and the exact source commit it was built from |

## Build and test

```
./gradlew build          # needs JDK 25; jar in build/libs/
./gradlew test           # unit + MockBukkit integration tests
./gradlew :live-test:jar # live-server verification driver (see docs/TEST_REPORT.md)
```

## Repository layout

```
src/main/java/dev/aura/punishments/
  model/        case state machine, enums, row records
  db/           SQLite database (single owning thread), versioned schema, repositories, hash-chained audit
  config/       validated settings, offenses, messages
  service/      authority, cases, custody, judicial, direct/emergency, punishments, admin (no Bukkit I/O on the hot path)
  integration/  JailReloaded adapter + bootstrap, LuckPerms, Discord, notifier, enforcement
  listener/     login bans, chat mutes, command guards (mute / legacy / custody)
  command/      /punish, /punishments, /ap
  gui/          menus, GUI manager (click safety), chat prompts
src/test/       126 tests (services, MockBukkit GUI/listener flows, Discord failure handling)
live-test/      driver plugin run inside a real Paper 26.3 server with the real JailReloaded/LuckPerms/EssentialsX/Towny
libs/           supplied custom jars (compile-only / test-only; not bundled)
docs/           handbooks, reports, evidence
release/        built jar + checksum + build info
```
