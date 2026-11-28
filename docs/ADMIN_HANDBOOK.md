# AuraPunishments 1.1.0 - Administrator Handbook

## What's new in 1.1.0 (administration)

### Configuration (all optional; defaults shown in `config.yml` / `config-1.1.0-reference.yml`)

| Section | Controls |
|---|---|
| `warnings.notifications.*` | chat on/off; title (text, subtitle, optional reason with `reason-max-chars`, fade-in 10 / stay 100 / fade-out 20 ticks); sound (key, volume 0-2, pitch 0.5-2, source); action bar; `spacing-ticks` between queued warnings |
| `warnings.repeat.*` | indicator window (30d) and threshold (3); **never** triggers punishment |
| `review.*` | enable; `required-types` (MUTE, BAN, IPBAN; never WARN/KICK/JAIL); `min-duration`; `exempt-level`; `evidence-deadline` 24h; `review-deadline` 72h; reminder timing; in-game notices |
| `dashboard.*` | `approaching-custody` window; `priority.*` sort weights |
| `defendant.*` | enable; hearing instructions text; include reasoning; which status changes are announced |
| `exports.*` | enable; folder name inside the plugin folder; timeline |
| `integrations.coreprotect.*` | enable; max radius / time window; sample size |
| `judicial.time-served.default-credit-for` | types credited automatically (default `[JAIL]`) |
| `discord.*` | see [DISCORD_WEBHOOKS.md](DISCORD_WEBHOOKS.md) |

`/ap reload` validates every value and lists *all* problems; an invalid file leaves the running configuration untouched. Upgrades never overwrite your values.

### Operations

* `/ap status` - database, JailReloaded, LuckPerms, Discord outbox (sent / undeliverable / pending) and CoreProtect state.
* `/ap discord [retry]` - outbox status; re-queue undeliverable messages.
* Owner review is **information, never automation**: overdue reviews only produce reminders. To make the plugin stop requiring reviews, set `review.enabled: false` (quick punishments are unaffected).
* The review/notification/outbox data lives in the same SQLite file; it is covered by your normal backups of `plugins/AuraPunishments/`.
* Refused attempts at elevated actions (direct/emergency/judicial/review modify-reverse, admin, export...) are logged to the administrative Discord channel, at most once per person and permission per minute.
* Troubleshooting: `Discord ... undeliverable` in `/ap status` after a webhook was deleted -> fix the URL, `/ap reload`, `/ap discord retry`. `CoreProtect: not installed` -> the CoreProtect button is hidden; install CoreProtect 25.x and restart.

Audience: server administrators and the person responsible for the Aurehaven plugin stack.
Companion documents: [Installation & rollback](INSTALLATION.md), [Permission matrix](PERMISSIONS.md),
[Integration report](INTEGRATION_REPORT.md), [Staff handbook](STAFF_HANDBOOK.md), [Judicial handbook](JUDICIAL_HANDBOOK.md).

## 1. What the plugin is (and is not)

AuraPunishments is the single authority for punishments on Aurehaven. It provides:

* `/punish <player>` - one GUI per player: cases, evidence, pretrial detention, warnings, kicks, mutes, bans, IP bans,
  direct punishment (major offenses), emergency actions, history, notes.
* A judicial workflow: **case -> optional pretrial detention -> trial (held in Discord) -> verdict -> sentence**,
  with the owner as judge. Direct punishment exists for proven, serious cases but is the exception, not the workflow.
* Enforcement: login bans, chat/PM/channel mutes, expiry, revocation, history, hash-chained audit log.

It is **not** a jail. JailReloaded does all imprisonment; AuraPunishments only records *why* and *until when*, asks
JailReloaded through its public API, **verifies** the result, and reconciles after crashes.

## 2. Requirements

| Requirement | Detail |
|---|---|
| Server | Paper **1.21.11** or **26.3** - the same jar runs on both (compiled against the 1.21.11 API, `api-version: 1.21.11`; tested on `1.21.11-132` and `26.3.build.256-stable`). |
| Java | **21 or newer** (Paper 1.21.11 needs 21, Paper 26.3 needs 25; the plugin is compiled for Java 21). |
| JailReloaded | **1.2.0 or newer**, tested with **1.2.2**. Without it custody and jail sentences are disabled (everything else works). |
| LuckPerms | Strongly recommended (and required by AuraCommandFilter). Used for offline staff-hierarchy lookups and to close open GUIs when permissions change. Without it the plugin works with reduced offline protection (cached levels). |
| Optional | AuraCommandFilter, EssentialsX, Towny, TownyChat, CoreProtect/Grim (as *evidence references only*), Discord webhook. |

## 3. First start

On first start the plugin creates `plugins/AuraPunishments/`:

| File | Purpose |
|---|---|
| `config.yml` | All behaviour: custody deadlines, limits, escalation, direct/emergency rules, legacy-command blocking, Discord. |
| `offenses.yml` | Offense categories, sentencing presets, repeat-offense recommendations, evidence minimums. |
| `messages.yml` | Player-facing screens, staff alerts, prompts, command feedback (MiniMessage). |
| `punishments.db` (+ `-wal`, `-shm`) | The SQLite database (WAL mode). |
| `backups/` | Automatic pre-migration copies of the database. |

Check the console for the startup summary:

```
[AuraPunishments] AuraPunishments 1.0.0 enabled in 154ms (schema v1, 0 active punishments).
[AuraPunishments] JailReloaded: JailReloaded 1.2.2 (compatible)
[AuraPunishments] LuckPerms: connected (offline staff protection active)
[AuraPunishments] AuraCommandFilter detected: ...
```

`/ap status` (permission `aurapunishments.admin`, or the console) prints the same information plus database health, Discord state and
counts of custody / pending operations.

### Required JailReloaded setup

AuraPunishments does not create jails. Before using pretrial detention make sure JailReloaded has:

1. At least one **enabled jail** with arrival and release points (`/jail create`, see JailReloaded docs).
2. Either JailReloaded's own default jail configured, or set `custody.jail-name:` in AuraPunishments' `config.yml`.
3. `allowJaillingOfflinePlayers: true` (JailReloaded's own spelling; the default) if you want to detain players who are offline.
4. Remember `jail.cantbejailed`: a player holding it cannot be imprisoned by anyone (the custody attempt fails with a clear message).

## 4. Configuration

Everything is commented inline. Edit, then `/ap reload`. **A reload is validated first**: if any value is invalid the
old settings stay active and every problem is listed to you. The same validation runs at startup; an invalid
configuration refuses to start the plugin with the list of problems (it never runs half-configured).

### Settings that need a restart

| Setting | Why |
|---|---|
| `database.*` | The database is opened once. |
| `custody.reconcile-interval-seconds` | The reconciliation timer is created at startup. |
| `integrations.luckperms.close-guis-on-permission-change` | The LuckPerms subscription is created at startup. |

Everything else (limits, deadlines, offenses, messages, mute/legacy command lists, Discord, JailReloaded on/off) applies
on `/ap reload`.

### Highlights

| Section | What it controls |
|---|---|
| `custody` | `review-deadline` (default **48h**), pre-deadline alerts, overdue repeat, `on-deadline` (`ALERT_THEN_RELEASE` default, or `ALERT_ONLY`), `grace-period` (12h), extensions (2 x 24h), jail/cell, guard of JailReloaded commands. |
| `judicial` | Whether the prosecutor may judge (default yes, because the owner is the only judge), assigned-judge rule, **time-served credit** (on, rate 1.0). |
| `escalation` | Repeat-offense scope (`category` / `all`) and whether direct punishments count. Acquittals, dismissals, overturned findings and unproven allegations never count. |
| `direct` | Minimum severity (MAJOR), description/justification lengths, minimum evidence, typed-name confirmation for bans. |
| `emergency` | Allowed types (MUTE, BAN), default/max duration (6h / 24h), review deadline (12h). |
| `punishments.limits` | Duration tiers that `aurapunishments.limit.<type>.<tier>` nodes refer to. |
| `protections` | Hierarchy, self-targeting, immunity node. |
| `mute` | Commands blocked while muted (private messages, emotes, TownyChat channels) incl. sub-commands such as `mail send`. |
| `legacy-commands` | Redirect `/ban /mute /kick ...` to `/punish`. |
| `discord` | Webhooks, per-event enable/URL. |
| `retention` | Audit pruning (default: keep forever). |

### Offenses

`offenses.yml` defines the eleven categories (chat misconduct, harassment, advertising, griefing, theft, Towny rule
violation, cheating, exploiting, staff obstruction, severe malicious activity, other). Each has severity, whether it is
**direct-eligible**, evidence minimums (for cases and for direct punishment), allowed punishment types, a duration cap,
repeat-offense **recommendations** (the judge still decides), duration presets and an optional required permission.
The `other` category is mandatory (fallback).

## 5. Database management

* SQLite, single owning thread, WAL, `synchronous=FULL`. All access is off the server thread.
* **Schema versioning**: a `meta` table holds `schema_version`; migrations run in order inside transactions.
  Before migrating an existing database the plugin copies it to `backups/punishments.db.pre-vN-<timestamp>`.
* **Downgrade protection**: if the database is *newer* than the plugin, the plugin refuses to start (nothing is modified).
* **Backups**: stop the server and copy `punishments.db` (and any `-wal`/`-shm` files), or use `sqlite3 punishments.db ".backup 'copy.db'"` while running.
* **Sensitive data**: last-known IP addresses are stored in plain text (needed for IP bans). Protect the file with
  filesystem permissions and exclude it from public backups. IPs are shown only to holders of
  `aurapunishments.sensitive.ip` and are never written to Discord, logs, audit entries or kick screens.
* MySQL/MariaDB is **not implemented** in 1.0.0 (the schema/queries are written so a dialect can be added later).

### The audit log

Every elevated action (direct punishment, emergency, verdict, sentence, overturn, appeal decision, evidence void,
permanent bans, IP bans, reload...) is written **in the same transaction as the change**, chained with SHA-256 so edits
are detectable. `/ap audit verify` (or Administration -> Verify audit integrity) recomputes the chain and reports the
first damaged entry. `retention.audit-days` > 0 prunes old entries and re-anchors the chain.

## 6. Integrations

| Plugin | How AuraPunishments uses it |
|---|---|
| **JailReloaded** | Public classes only (`JailsAPI`, `PrisonerManager`, `JailManager`, events). Mirrors what JailReloaded's own `/jail` and `/unjail` do. Records the JailReloaded persistent *jail id* per case, tags the reason `[AP-0012]`, verifies every result, never touches a record that is not the case's own, and reconciles at startup and every minute. |
| **AuraCommandFilter** | Nothing to configure. Every command declares its permission in `plugin.yml`, which is what ACF reads. Verified with `/acf scan` (see Integration report). |
| **LuckPerms** | Offline staff level/immunity lookups; GUI-closing on permission change. |
| **EssentialsX** | `/ban /tempban /banip /unban /kick /mute /tempmute /unmute` (all aliases and `essentials:` forms) are redirected; remove the permissions too (see [PERMISSIONS.md](PERMISSIONS.md) section 4). EssentialsX private messaging commands are blocked while muted. |
| **Towny / TownyChat** | Muted chat is cancelled at `LOWEST` priority, before TownyChat. Channel shortcuts (`/g /tc /nc /lc /ac /ch /channel`) and `town say` / `nation say` are blocked while muted. |
| **CoreProtect, Grim** | Evidence *types* (`COREPROTECT`, `ANTICHEAT`) - the staff member pastes the lookup/alert reference. No automatic lookup. |
| **Discord** | Webhooks only (see below). DiscordSRV is not used. |

### Command conflicts you should know about

* `/ap` is an alias of `/aurapunishments` (the main command). If another plugin also claims `/ap`, run
  `/aurapunishments ...` or remove the alias in `plugin.yml`.
* **`/jail` and `/unjail` are claimed by both JailReloaded and EssentialsX.** On the tested stack JailReloaded answers
  them (AuraCommandFilter's scan reports it). Keep EssentialsX's jail commands disabled/unpermitted.
* JailReloaded's `/unjail`, `/unjailforce` and `/jail time|transfer|clear` against a prisoner **held for an open case**
  are refused by AuraPunishments unless the sender holds `aurapunishments.custody.override` (config:
  `custody.guard-jail-commands`).

### Discord

1. Create a webhook in the hearing/moderation channel; paste the URL into `discord.webhook-url` and set `discord.enabled: true`.
2. Optionally give a different webhook per event under `discord.events.<event>.webhook-url`, or disable single events.
3. `/ap reload`.

Events: case created, custody started/released, trial scheduled, verdict, sentence, direct punishment, emergency action,
appeal, reversal, custody alerts. Messages carry case ids and non-sensitive context only. Mentions are disabled in every
message. Delivery is asynchronous with retry/back-off and rate-limit handling; a failure is logged once a minute at most and
**never** delays or blocks a punishment.

## 7. Operations

### Background tasks

| Every | Task |
|---|---|
| 10 s | Expire elapsed bans/mutes; refresh the custody index used by the JailReloaded command guard. |
| 60 s | Custody deadline alerts and automatic release after grace; emergency-review alerts; retry releases for resolved cases; dashboard counts. |
| `custody.reconcile-interval-seconds` (60) | Reconcile custody records with JailReloaded. |
| Daily | Audit pruning (only if `retention.audit-days` > 0). |
| Startup | Rebuild the ban/mute cache from the database; reconcile custody; finish interrupted sentencing operations. |

### How custody stays truthful

Each custody/sentence action is a three-step operation: **(1)** record intent + a pending operation, **(2)** call JailReloaded
on the main thread and verify, **(3)** record the verified result. If the server crashes in between, the pending operation
is resolved against JailReloaded's real state at the next start. If JailReloaded fails or is missing, the action fails with
a clear message and nothing is faked.

An acquitted or dismissed player is **always** released: if JailReloaded cannot release them at that moment the verdict is
still recorded, staff are alerted, and the release is retried every minute.

### Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "Pretrial detention is unavailable" / `JailReloaded: UNAVAILABLE - ...` at startup | Read the reason: not installed, not enabled, older than 1.2.0, or a different major version. Fix and `/ap reload` (JailReloaded is re-detected on reload). |
| "JailReloaded cannot be used: no usable jail" | Create/enable a jail (`/jail create`), or set `custody.jail-name`. |
| "X has never played ... JailReloaded refuses" | The target never joined; JailReloaded refuses to jail unknown players. |
| Custody shows `RELEASING` | The prisoner is offline; JailReloaded completes the release at their next login. The record turns `RELEASED` automatically. |
| A custody was "ended outside AuraPunishments" alert | Someone used JailReloaded commands directly (or its timer ended a sentence). The record was reconciled; investigate who. |
| Player can still chat while muted | Another chat plugin handles chat at a higher priority and ignores cancellation (see Known Limitations for TownyChat); check `/ap status`, then test with a plain account. |
| "Database ... PROBLEM" in `/ap status` | The last operation failed; operations fail with "Nothing was changed" until the database recovers. The plugin reconnects automatically. If the file is damaged restore a backup. |
| `AUDIT LOG DAMAGED` | An audit row was edited or removed outside the plugin. Preserve the database file and investigate. |
| Plugin disabled itself at startup | Invalid configuration, database cannot be opened, or active punishments could not be loaded - the console says which. The plugin refuses to run rather than silently skip bans. |

### Optional name confirmation (1.2.1)
`punishments.quick.type-name-to-confirm` (default `false`) and `direct.type-name-to-confirm` (default `[]`) control whether heavy actions ask for the player's name in a dialog after Confirm. Both take effect on `/ap reload`; existing config files keep their own values.

### Text entry (1.2.0): a native dialog window, never chat

Whenever the GUI needs text (a reason, evidence reference, note, description, duration, player name, a typed-name confirmation), the
GUI closes and Minecraft opens a **dialog window** with a text box and **Submit** / **Cancel** buttons (Paper's Dialog API). The
text travels in the dialog's own packet: no chat event exists, so no chat plugin, Discord relay or chat logger can see it, and
nothing is written to CoreProtect's chat log. A too-short/too-long answer re-opens the dialog with the problem shown and the typed text
kept. The dialog cannot be closed with Esc (use Cancel); an unanswered dialog closes after `gui.prompt-timeout-seconds` (default 90 s),
and disconnecting, running a command or a plugin disable ends the session without changing anything. Each staff member has their own
session. Chat itself is never read for input. `gui.prompt-cancel-word` is no longer used (old config files still load).

Requirements: a client version that supports dialogs (any client that can join a Paper 26.3 server).

## 8. Security model (summary)

* Default-deny permissions (the only default-op node is `aurapunishments.command`, so the console can run `/ap`; subcommands still need their own nodes for players); every action re-checked at execution; `/punish` access alone grants nothing.
* Strict staff hierarchy (offline-aware), self-protection, immunity, duration tiers, separate direct and emergency nodes.
* GUI: all clicks/drags cancelled, stale or duplicated windows closed, 250 ms click cooldown, in-flight lock, typed-name
  confirmation for bans, optimistic version checks (a case changed by someone else is refused, not overwritten).
* Text from staff/players is inserted into messages as *plain text* (no MiniMessage/click injection), evidence links must be
  http(s), SQL is parameterised, Discord mentions disabled.
* Failed external actions are never reported as successful.


---

## 1.3.0 additions for administrators

| Setting (config.yml) | Default (new install / upgrade) | What it does |
|---|---|---|
| `judicial.enabled` | `false` / *missing = true* | The judicial switch. Read [JUDICIAL-MODE.md](JUDICIAL-MODE.md) before turning it off on a server with open cases. `/ap judicial` shows state and open work. |
| `network.server-name` | blank (generated) | Name stamped on every record (`smp`, `towny`, `skyblock`, `lobby`). Choose once. [DATABASE.md](DATABASE.md) |
| `punishments.jail.*` | blank / false | Jail and cell for jail punishments, mute while jailed. [JAIL.md](JAIL.md) |
| `review.required-types` | `[MUTE, BAN, IPBAN]` | now may include `JAIL` |
| `review.permanent-only-types` | `[]` | types reviewed only when permanent |
| `review.include-emergency` | `true` | (judicial off) emergency actions also enter the owner review queue |
| `appeals.require-ticket`, `appeals.min-reason-length` | `false`, `10` | appeals through Ticket Tool. [APPEALS.md](APPEALS.md) |
| `discord.punishment-log.*`, `discord.public-log.*` | staff log on, public log off | which punishment notices go to Discord, with which fields. [DISCORD_WEBHOOKS.md](DISCORD_WEBHOOKS.md) |

New permission: `aurapunishments.appeal.decide` (never automatic, not in `aurapunishments.*`). New punishment nodes: `punish.jail`, `punish.jail.permanent`. See [PERMISSIONS.md](PERMISSIONS.md) section 3c.

`/ap status` now reports the server name, schema version and the judicial state (with open items during wind-down). `/ap judicial [status|recount]` is the dedicated view. When `judicial.enabled` is changed and reloaded, an audit entry `admin.judicial-switch` and a console warning are written.

Scheduled work while the judicial system is off: custody deadline sweeps and custody reconciliation are idle; jail-punishment reconciliation (every `custody.reconcile-interval-seconds`), release retries, emergency-review alerts, owner-review reminders, notification expiry and the Discord outbox keep running.

Daily workflow without the judicial system: [WORKFLOW_NO_JUDICIAL.md](WORKFLOW_NO_JUDICIAL.md).

## 1.3.1 additions for administrators

| Setting (config.yml) | Default (new install / upgrade) | What it does |
|---|---|---|
| `casework.enabled` | `true` / *missing = false* | The case workflow: with `judicial.enabled: false` staff still open cases and punish from them, with no court. Ignored while the judicial system is on. [WORKFLOW_CASEWORK.md](WORKFLOW_CASEWORK.md) |
| `casework.require-case-for` | `[]` | Punishment types that can only be issued from a case (for example `[BAN, IPBAN, JAIL]`); the quick buttons and `/ap` commands refuse them with an explanation. |

No new permission nodes, no database migration. `/ap judicial` and `/ap status` show the state (ENABLED, CASEWORK, WIND_DOWN, DISABLED) and any court work left over from before. To adopt the workflow on an upgraded server add the `casework:` section shown in [INSTALLATION.md](INSTALLATION.md) and run `/ap reload`.

## 1.3.2 additions for administrators

| Change | Detail |
|---|---|
| **Paper 1.21.11 and 26.3** | One jar. Needs Java 21+ on 1.21.11 and Java 25 on 26.3 (what those servers require anyway). Plugins you depend on must themselves load on your server version (for example a plugin whose `plugin.yml` says `api-version: 26.3` will not load on 1.21.11). |
| `review.enabled` | **`false` in the shipped file** (new installs): owner review is off. A missing line still means on, so upgrades do not change. To turn it off on an existing server set `review:` / `  enabled: false` and `/ap reload`. Reviews that already exist stay readable. Emergency actions keep their own senior-staff review. |
| Review screens | The dashboard's owner-review buttons and `/ap review` appear only while `review.enabled` is true or reviews from before are still open. |
| Punishments from a case | Never create an owner review (the case workflow), whatever `review.enabled` says. |

## Running a network (1.4.0)

Checklist (details in [NETWORK_DEPLOYMENT.md](NETWORK_DEPLOYMENT.md)):

1. One dedicated MariaDB 10.6+/MySQL 8+ database and user; NTP on every machine.
2. The same jar on every backend; each with its own `network.server-name`; `database.type: mariadb|mysql` and the connection settings.
3. First backend with `database.allow-migration: true` (creates the schema), then the others.
4. Velocity modern forwarding, backends reachable only through the proxy (so IP bans see real player addresses).
5. Optional: `network.jail.backend` and JailReloaded on that one backend only.
6. `/ap network` shows every backend, the change-feed state, who sends Discord and who runs housekeeping; `/ap status` shows the storage and any outage.
7. Importing old servers: `/ap migrate check <file> <label>`, then `run ... confirm`.

What to expect during a database outage: known bans and mutes keep being enforced, staff actions are refused with "Nothing was changed", and everything resumes by itself when the database is back.
