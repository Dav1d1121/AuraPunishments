# AuraPunishments

GUI-based moderation, **optional** judicial case management and punishment enforcement for the **Aurehaven Towny SMP** and its network.
Paper **1.21.11 or 26.3** (one jar for both) - Java **21 or newer** - integrates with **JailReloaded** (imprisonment), **LuckPerms** (authority),
**AuraCommandFilter** (command visibility), EssentialsX/Towny/TownyChat (chat and command enforcement) and Discord webhooks.

* Main command: **`/punish <player>`** - the player's complete disciplinary GUI. Also `/punishments`, `/ap help|case|cases|history|judge|reload|...`.
* **One jar, one switch (1.3.0): `judicial.enabled`.** Off (new installs): a punishment is final and immediate; evidence, notes, jail, appeals, emergency actions and owner review all work without cases. On (upgrades): the full judicial system, exactly as before. See [docs/JUDICIAL-MODE.md](docs/JUDICIAL-MODE.md).
* **The case workflow (1.3.1): `casework.enabled`.** With the judicial system off, staff still **open a case, add evidence and notes, and punish from it** - no court, no detention, no verdict; the punishment is final at once and the case stays open. Quick buttons stay for routine actions. See [docs/WORKFLOW_CASEWORK.md](docs/WORKFLOW_CASEWORK.md).
* **Choose the punishment inside Create Case (1.3.2):** the New case screen has a Punishment button that opens the usual punishment screens; Submit opens the case and issues the punishment in one step. No owner approval, no court. **Owner review is off by default** for new installs.
* **Paper 1.21.11 and 26.3 from one jar (1.3.2).**
* Judicial path (when on): **case -> pretrial detention (JailReloaded) -> Discord hearing -> verdict -> sentence** (owner is the judge).
* **Jail is a punishment** (`/ap jail`, `/ap unjail`), **appeals** go through Discord Ticket Tool and are decided with `aurapunishments.appeal.decide`, and Discord punishment notifications are configurable (private log + optional sanitized public log).
* Exception path: **direct punishment** for proven major offenses (evidence + justification) and **emergency actions**.
* **No chat input (1.2.0):** all text entry is a native dialog window with Submit / Cancel; nothing is ever typed into chat.
* Routine **warnings** (title + sound + chat, queued for offline players) and immediate **quick punishments** that also go to an **owner review** queue (never automatic, never reversed by a timer).
* Work queue, defendant notifications, case export, read-only CoreProtect evidence, Discord channel routing (**1.1.0**).
* AuraPunishments never implements a jail: JailReloaded imprisons; this plugin records, verifies and reconciles.

## Documentation

| Document | For |
|---|---|
| [docs/JUDICIAL-MODE.md](docs/JUDICIAL-MODE.md) | The `judicial.enabled` switch: states, switching on/off safely (1.3.0) |
| [docs/WORKFLOW_CASEWORK.md](docs/WORKFLOW_CASEWORK.md) | Cases without the court: open a case, punish from it, no verdict (1.3.1) |
| [docs/WORKFLOW_NO_JUDICIAL.md](docs/WORKFLOW_NO_JUDICIAL.md) / [docs/JAIL.md](docs/JAIL.md) / [docs/APPEALS.md](docs/APPEALS.md) | Day-to-day with the judicial system off; jail punishments; appeals (1.3.0) |
| [docs/DATABASE.md](docs/DATABASE.md) / [docs/ARCHITECTURE_DECISIONS.md](docs/ARCHITECTURE_DECISIONS.md) | Schema v3, network foundation; decisions, assumptions, limitations (1.3.0) |
| [docs/COMMANDS.md](docs/COMMANDS.md) | Every command, with what changes when the judicial system is off (1.3.0) |
| [docs/INSTALLATION.md](docs/INSTALLATION.md) | Install, controlled-deployment test, upgrade, **rollback** |
| [docs/ADMIN_HANDBOOK.md](docs/ADMIN_HANDBOOK.md) | Configuration, database, integrations, Discord, maintenance, troubleshooting |
| [docs/STAFF_HANDBOOK.md](docs/STAFF_HANDBOOK.md) | `/punish`, cases, evidence, detention, direct punishment, history |
| [docs/JUDICIAL_HANDBOOK.md](docs/JUDICIAL_HANDBOOK.md) | Hearings, verdicts, sentencing, acquittal/release, appeals, reversals |
| [docs/DISCORD_WEBHOOKS.md](docs/DISCORD_WEBHOOKS.md) | Discord channel/webhook setup and operation (1.1.0) |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) / [docs/ROOT_CAUSE_ANALYSIS_1.1.1.md](docs/ROOT_CAUSE_ANALYSIS_1.1.1.md) | 1.1.1 chat-input hotfix: what changed and why |
| [docs/RELEASE_NOTES.md](docs/RELEASE_NOTES.md) / [docs/BUG_FIX_SUMMARY.md](docs/BUG_FIX_SUMMARY.md) / [docs/ROLLBACK.md](docs/ROLLBACK.md) | What changed, defects fixed, how to roll back |
| [docs/PERMISSIONS.md](docs/PERMISSIONS.md) | Every permission node, per-rank recommendations, **LuckPerms commands** |
| [docs/INTEGRATION_REPORT.md](docs/INTEGRATION_REPORT.md) | What was inspected in each plugin, how it is integrated, what was verified |
| [docs/TEST_REPORT.md](docs/TEST_REPORT.md) | Test evidence, results, limitations, readiness assessment |
| [docs/RELEASE_NOTES.md](docs/RELEASE_NOTES.md) | Features and **known limitations** |
| [release/](release/) | `AuraPunishments-1.3.2.jar`, its SHA-256 and the exact source commit it was built from |

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
  gui/          menus, GUI manager (click safety), dialog-window text entry
src/test/       services, MockBukkit GUI/listener flows, Discord failure handling, migrations, jail/appeal/judicial-mode suites
live-test/      driver plugin run inside a real Paper 26.3 server (live-test-121/: the same phases on Paper 1.21.11) with the real JailReloaded/LuckPerms/EssentialsX/Towny
libs/           supplied custom jars (compile-only / test-only; not bundled)
docs/           handbooks, reports, evidence
release/        built jar + checksum + build info
```
