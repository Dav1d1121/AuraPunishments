# AuraPunishments 1.2.1 - Release notes

Small update: the "enter the player's name" step before bans, IP bans and permanent punishments is now optional and **off by default** (`punishments.quick.type-name-to-confirm`). For direct punishments edit `direct.type-name-to-confirm` in your existing config to `[]`. Replace the jar and restart; no migration, no permission changes. Details: [CHANGELOG.md](CHANGELOG.md).

---

# AuraPunishments 1.2.0 - Release notes

**No more typing in chat.** Every text entry in the GUI is now a Minecraft dialog window with a text box and Submit / Cancel. Nothing is read from chat, so nothing can leak to chat plugins, Discord relays or logs. See [CHANGELOG.md](CHANGELOG.md).

**Upgrade (from 1.1.1, 1.1.0 or 1.0.0):** replace the jar and restart. No database migration, no required config changes, no permission changes. `gui.prompt-cancel-word` is ignored (leave it or delete it). Rollback: put the previous jar back (schema unchanged).

---

# AuraPunishments 1.1.1 - Release notes (hotfix)

**1.1.1 fixes the chat-input defect**: text typed into GUI prompts could be seen by other plugins as ordinary chat. Full analysis: [ROOT_CAUSE_ANALYSIS_1.1.1.md](ROOT_CAUSE_ANALYSIS_1.1.1.md); changes: [CHANGELOG.md](CHANGELOG.md).

**Upgrade from 1.1.0 / 1.0.0:** replace the jar and restart. No database migration (schema stays v2), no config changes, no permission changes. **Recommended:** in `plugins/CoreProtect/config.yml` set `log-cancelled-chat: false` and run `/co reload`, otherwise CoreProtect stores prompt answers in its chat log (AuraPunishments prints a warning at startup when this applies). Rollback: put the 1.1.0 jar back (the database is compatible).

---

# AuraPunishments 1.1.0 - Release notes

1.1.0 is an enhancement of 1.0.0: everything that worked before still works (all 1.0.0 tests and the live regression runs pass).

## New

* **Better warnings** - chat message (kept) plus a configurable title (`OFFICIAL WARNING`, subtitle, fade 10 / stay 100 / fade 20 ticks), optional sound (sound, volume, pitch, source) and optional action bar. Reasons shown in a title are truncated safely. Warnings to offline players are queued and shown at next login, in order, once each, and only marked delivered after the player actually sees them. Routine warnings need only `aurapunishments.punish.warn`, respect hierarchy/immunity, never need owner review, and a **repeat-warning indicator** (default 3 in 30 days) is shown to staff - it never causes an automatic jail, mute or ban.
* **Warning history** in the player profile (Warnings screen): each warning, whether it was shown, and whether any later action was taken.
* **Owner review of quick punishments** - quick mutes/bans/IP bans stay immediate, and also create a review record (Pending Review / Under Review / Upheld / Modified / Reversed / Further Investigation Required). Reviewers can inspect, add private notes, ask the issuer to explain, uphold, modify, reverse or refer to a judicial case. Evidence deadline (24 h) and review deadline (72 h) are targets with reminders - **nothing is ever reversed automatically**, and timed punishments still expire. Punishment types are labelled everywhere: Routine Warning / Emergency Quick Punishment / Direct Punishment / Emergency Protective Action / Judicial Sentence.
* **Discord channel routing** - seven categories (punishment, judicial, custody, owner-review, direct-emergency, appeal, administrative), per-event overrides, fallbacks, several destinations, role mentions (opt-in), webhook secrets kept out of `config.yml`, a persisted outbox with retries/rate-limit handling/de-duplication. See [DISCORD_WEBHOOKS.md](DISCORD_WEBHOOKS.md).
* **Work queue** - one prioritised list (priorities configurable) of overdue/approaching custody, emergency reviews, quick-punishment reviews, appeals, awaiting sentencing / verdict / trial, further investigation, with dashboard counts.
* **Defendant communication** - detention (case number, offense, reason, hearing instructions; explicitly *not* a finding of guilt), trial scheduled/postponed, status changes, verdict, sentence, release, acquittal. Persistent: offline defendants get them at login, once, across restarts.
* **CoreProtect evidence (read-only)** - lookup a player's blocks, chat, commands or sessions and optionally attach the snapshot as evidence. No rollback/restore/purge capability exists in the code.
* **Case summary export** (Markdown / text; `/ap export <case>`), limited by the exporter's own permissions; no IPs or private notes.
* **Appeal tickets** - link a Discord Ticket Tool ticket (number or link) to an appeal; routed to the appeal log. Appeals can be taken ("Under Review") before being decided.
* **Custody / sentencing** - unchanged 48 h review + 12 h grace (**about 60 h effective automatic release**, both configurable). Time-served credit default is now explicit: **jail is credited automatically; bans and mutes are at the judge's discretion** (`judicial.time-served.default-credit-for`). Custody events distinguish *requested* from *confirmed* and *failed*.
* **Security/administrative events** to the admin Discord log: configuration reloads, exports, refused elevated actions.
* New commands: `/ap review`, `/ap queue`, `/ap export`, `/ap warnings`, `/ap discord`.

## Upgrade notes

Schema v1 -> v2 (automatic, with a backup). Legacy quick punishments are imported as *Legacy* and are not shown as new work. `config.yml` and `messages.yml` are never rewritten; new options default safely and a reference file is written once. 1.0.0 Discord settings keep working and new event types are not posted until you opt in. See [INSTALLATION.md](INSTALLATION.md) and [ROLLBACK.md](ROLLBACK.md).

## Known limitations (also in TEST_REPORT.md)

* Real Discord delivery could not be tested from the build environment (no access to discord.com); verified against a local stand-in only.
* Live JailReloaded phases 4 and 5 (incompatible jar, version drift) were not re-run for 1.1.0; phases 1, 2, 3 and 6 were.
* Evidence void/correct was verified by automated GUI tests, not by hand on the live server.
* Online players in live tests are server-side test players (full login/join sequence over an in-memory channel), not a Minecraft client.

---

# 1.0.0 release notes (history)

# AuraPunishments 1.0.0 - Release Notes

First release. Target: Paper 26.3, Java 25, JailReloaded 1.2.x, LuckPerms.

## Features

* **`/punish <player>`** opens a per-player GUI (head, online/offline, active punishments, custody, history counts, only the
  actions the staff member holds permission for). Online players and verified offline players; unknown/ambiguous names refused;
  UUIDs never invented.
* **Judicial workflow**: case creation (offense, severity, description, context, evidence, prosecutor), optional **pretrial
  detention through JailReloaded** with a 48h review deadline (alerts, extensions, automatic release after grace), trial
  preparation (judge, trial time, Discord hearing reference), verdicts (Guilty / Not Guilty / Dismissed / Continued),
  sentencing (warning, mute, ban, IP ban, jail) with **time-served credit**, appeals, overturns and reversals.
* **Direct punishment** for major offenses (evidence + justification + confirmation, separately authorised, duration-limited) and
  **emergency protective actions** (separately authorised, <=24h, senior notification, review deadline, auto-expire).
* **Enforcement**: login bans/IP bans, mutes across public chat, private messages and TownyChat/EssentialsX channel commands,
  warnings (delivered at next login if offline), kicks, expiry across restarts, revocation, full history.
* **Safety**: default-deny permissions (console can run `/ap`), strict (offline-aware) staff hierarchy, immunity, duration tiers, optimistic versioning
  for concurrent edits, duplicate-click/stale-window/item-movement protection, typed-name confirmation, hash-chained audit log.
* **Integration**: JailReloaded through a verified, recoverable adapter; AuraCommandFilter-compatible command metadata; legacy
  `/ban /mute /kick ...` redirected to `/punish`; Discord webhooks (best-effort, async, mention-safe).
* **Operations**: validated, hot-reloadable YAML configuration; SQLite with schema versioning, automatic pre-migration backups
  and downgrade protection; crash recovery and reconciliation; `/ap status`, `/ap audit verify`.
* **Documentation**: Administrator, Staff and Judicial handbooks, permission matrix with LuckPerms commands, integration report,
  installation/rollback, test report.

## Known limitations (read before deploying)

Each item states what is and is not verified. See [TEST_REPORT.md](TEST_REPORT.md) for the evidence.

1. **Online-player jailing was not verified on a live server.** A headless test server cannot have connected players, so the live
   tests used offline players (JailReloaded's "pending placement" path). The adapter calls the same public methods that
   JailReloaded's own `/jail` and `/unjail` use for online players, but the teleport/inventory part is JailReloaded's and was not
   exercised. **Run the controlled-deployment test in [INSTALLATION.md](INSTALLATION.md) before relying on it.**
2. **TownyChat 0.122 was not tested** (jar unavailable in the build environment). Muted public chat is cancelled at `LOWEST`
   priority and channel/PM *commands* are blocked; if TownyChat ignores cancelled chat events, a muted player could still speak in
   a TownyChat channel typed as ordinary chat. Verify during the deployment test; if it leaks, tell us the event priority
   TownyChat uses and the fix is a one-line change.
3. **AuraCommandFilter per-player visibility was not verified** (needs a connected player): the command metadata was verified
   with its `/acf scan`; confirm with `/acf debug <player> punish` once.
4. Tested with **LuckPerms 5.5.71** and **EssentialsX 2.22.0** (your servers run 5.5.91 and 2.22.1-dev+28) and Towny 0.103.2.0.
   EssentialsX 2.22.0 reports "unsupported server version" on Paper 26.3 but registers its commands.
5. **No import** of existing EssentialsX/vanilla bans, mutes or jail records. They keep being enforced by their own plugins until they
   expire; AuraPunishments' history starts empty.
6. **Chat-prompt text entry** (not anvil or the Dialog API) - a deliberate reliability choice; see the Admin handbook.
7. GUI titles/lore and short validation sentences are fixed in code; player-facing screens, alerts, prompts and command feedback
   are configurable in `messages.yml`. `messages.yml` validation checks value types/parse errors; MiniMessage itself is lenient,
   so a mistyped tag shows as literal text rather than being rejected.
8. **Settings are edited in YAML**, not in the GUI (the administration screen offers status, audit, integrity check and reload).
9. **IP bans** match the player's last known IP and any login from that exact address. Shared/CGNAT addresses can affect other
   players; IPv6/proxy behaviour was not tested. IPs are stored in plain text in the SQLite file.
10. **SQLite only.** MySQL/MariaDB is designed for but not implemented. **PlaceholderAPI** placeholders and **DiscordSRV** are not
    implemented. **Folia** is not supported. Bedrock (Floodgate) usernames are accepted by the resolver but untested.
11. Releasing an *offline* prisoner completes at their next login (JailReloaded's behaviour); a banned prisoner's JailReloaded record
    remains until they next join.
12. Discord is notification-only; the hearing itself is manual by design. CoreProtect/Grim are evidence *types* (references), not
    automatic lookups.

## Upgrade notes

None (first release). See [INSTALLATION.md](INSTALLATION.md) for upgrade and rollback procedures.
