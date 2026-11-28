# AuraPunishments changelog

(This file was accidentally emptied in the 1.2.0 release commit; it is rebuilt here from the release notes and commit history. Nothing else was affected.)

## 1.3.2 - choose the punishment inside Create Case; Paper 1.21.11 and 26.3 from one jar; owner review off by default
**Added**
* **Punishment inside the New case screen** (case workflow). A **6. Punishment** button opens the same list and the same punishment screens used everywhere else (type, duration, reason, note); "Use this punishment" keeps the choice on the draft. **Submit case + punishment** opens the case and issues the punishment in **one transaction**: both exist afterwards or neither does. The offense decides what is possible (types and longest duration from `offenses.yml`) and its recommendation for the player's history is shown as a hint. With no punishment chosen only the case is opened (as in 1.3.1). One confirmation (type-the-name for bans and permanent punishments when configured).
* **Paper 1.21.11 and 26.3 from one jar.** The plugin is compiled against the 1.21.11 API for Java 21 (`api-version: 1.21.11`) and runs on both servers. The unit tests run on both APIs (`gradle test` on 26.3 + MockBukkit v26.3, `gradle test121` on 1.21.11 + MockBukkit v1.21) and the live phases run on both a real Paper 1.21.11 and a real Paper 26.3 server.

**Changed**
* **Owner review is off by default for new installs** (`review.enabled: false` in the shipped file; a missing line still means on, so upgrades change nothing). A punishment issued from a case **never creates an owner review** in the case workflow, whatever the setting. The dashboard's owner-review buttons and `/ap review` appear only while review is on or reviews from before are still open. Emergency actions keep their own senior-staff review.
* "Strikes" for the offense recommendation in the case workflow are the player's earlier punishments issued from cases (not kicks, not revoked ones), since there are no verdicts.
* The case screen's Punish button and the Create Case screen's Punishment button share one chooser.

**Upgrade:** replace the jar and restart. No database migration. To turn owner review off on an existing server add/set `review:` / `  enabled: false` and run `/ap reload`.

## 1.3.1 - the case workflow: cases without the court
You liked opening a case first. With the judicial system off, **staff can still create a case, add evidence and notes, and punish from the case** - with no court: no pretrial detention, hearing, judge, verdict, sentencing or time-served credit. Read [WORKFLOW_CASEWORK.md](WORKFLOW_CASEWORK.md).

**Added**
* `casework.enabled` (config.yml; new installs: `true`, a missing section = `false`, so an upgrade never changes behaviour by itself). Only used while `judicial.enabled` is `false`. A fourth state, **CASEWORK**, joins ENABLED / DISABLED / WIND_DOWN.
* **Create Case is the first button** on the player screen; the quick buttons (Issue Warning / Kick / Mute / Ban / IP Ban / Jail) and `/ap warn|kick|mute|ban|ipban|jail` stay for routine actions.
* **Punish** button on the case screen: Warning, Kick, Mute, Ban, IP Ban or Jail with the usual duration, reason, evidence, note and confirmation. The punishment is **final and immediate**, **linked to the case**, recorded on the case timeline, and **the case stays open** for more evidence, notes and appeals until staff close it. Closing a case never lifts what was issued from it; a closed case can be reopened (Under Review -> Open).
* The case's rules are enforced when punishing from it: the case must be about that player and open; the offense must allow the punishment and its maximum duration (`offenses.yml`; a kick is always allowed); evidence already on the case counts toward `punishments.quick.require-evidence`; owner review works exactly as for quick punishments and points at the case.
* `casework.require-case-for: [BAN, IPBAN, JAIL]` (optional): chosen types can only be issued from a case; the quick buttons and `/ap` commands refuse them with an explanation.
* Appeals are filed against the **punishment** (Ticket Tool reference), as without cases; an overturned punishment is revoked and the case records it.
* Leftover court work (pretrial detainees, cases awaiting a verdict or sentence, case appeals) from when the judicial system was on is **reported, never touched**: console, administrators on join, `/ap judicial`, the dashboard banner; the court screens stay reachable only until it is finished.
* No new permission nodes.

**Changed**
* The case screen in casework hides judge, trial and hearing buttons; the owner of a case is shown as "Opened by" / "Reassign case owner"; the status chooser offers Open, Under Investigation, Under Review and Closed only; accused players are not sent hearing notices.
* `/ap judicial` and `/ap status` show the CASEWORK state.
* Evidence added to a punishment that has an open case lands on the case.

**Not changed**
* With `casework.enabled: false` and `judicial.enabled: false` everything behaves exactly as 1.3.0; with `judicial.enabled: true` exactly as 1.3.0. Schema, storage, permissions and Discord formats are unchanged.

**Upgrade:** replace the jar and restart. No database migration. `config.yml` is never rewritten; to get the workflow on an existing server add this at the top level of `config.yml` (same indentation as `judicial:`, not inside it):

```yaml
casework:
  enabled: true
  require-case-for: []
```

then `/ap reload`. A reference copy of the new options is written once as `config-1.3.1-reference.yml`. Rollback: put the 1.3.0 jar back (nothing in the database changed; punishments linked to cases simply show as ordinary punishments).

## 1.3.0 - optional judicial system, case-free workflows, standalone jail, appeals, network-ready storage
One jar, one switch. **`judicial.enabled`** turns the whole judicial system (cases, pretrial detention, hearings, verdicts, sentencing) on or off. New installs ship with it **off**: a punishment is final and enforced the moment staff issue it. An upgraded server keeps it **on** and behaves exactly as before until its administrator decides otherwise. Everything else (the `/punish` GUI, dialog text entry, confirmations, warnings, kicks, mutes, bans, IP bans, jail, emergency actions, owner review, evidence, staff notes, history, revoke, offline players, hierarchy, rank limits, LuckPerms, Discord logging, audit log) keeps working in both modes. Read [JUDICIAL-MODE.md](JUDICIAL-MODE.md) before switching a server that already has cases.

**Added**
* `judicial.enabled` (config.yml). Missing key = on (upgrade-safe); the file shipped to a new install says off. Explicit values always win and are never changed by detection or restart. Three states: **ENABLED**, **DISABLED**, and **WIND_DOWN** (switched off while cases/detainees/appeals are still open: nothing is released, closed, deleted or resolved automatically; new judicial work is refused, existing work can be finished with the normal tools; console, administrators on join, `/ap status`, `/ap judicial` and the staff dashboard keep reminding until it is done). Switching back on restores everything exactly.
* **Evidence and staff notes attach directly to a punishment** (at issuance and afterwards from the history screen) when there is no case. Evidence types, evidence requirements for serious punishments (`punishments.quick.require-evidence`), submitter/time/type/description and the void-never-delete rule with corrected replacements are all kept. Evidence that already lives on a case stays there and is shown alongside.
* New **punishment screen** (history -> click a punishment, `/ap punishment <id>`): details, evidence, notes, appeals, owner review, the case it came from, revoke / release, emergency review.
* **Standalone jail punishment** (JailReloaded still does the imprisonment): GUI "Issue Jail", `/ap jail`, `/ap unjail`, recorded on the punishment (prisoner id, jail, state), verified before it is reported, automatic release when JailReloaded's timer ends (recorded as served), early release (revokes the punishment, frees the prisoner), crash-safe three-step recovery with reconciliation at startup and every minute, guard against releasing the prisoner with JailReloaded's own commands, distinct from pretrial detention. Needs `aurapunishments.punish.jail` (+ `.permanent`) and a `limit.jail.<tier>`.
* **Emergency protective actions without a case**: a punishment that expires by itself and is reviewed by senior staff (uphold or revoke; the owner review record, when present, is settled by the same decision).
* **Owner review rules**: `review.required-types` now accepts JAIL; new `review.permanent-only-types` (for example review permanent mutes but not routine temporary ones) and `review.include-emergency`. Review stays a queue for oversight, never an approval step.
* **Appeals against punishments** (Discord Ticket Tool reference; AuraPunishments never reads or writes Discord tickets): record, link a ticket, take, uphold / reduce / overturn with reviewer and rationale kept, history on the punishment. An appeal never suspends a punishment. New permission **`aurapunishments.appeal.decide`** (not granted by default, not part of `aurapunishments.*`); with the judicial system on `judicial.overturn` is accepted as well; with it off only `appeal.decide` can decide. Hierarchy applies. `appeals.require-ticket`, `appeals.min-reason-length`.
* **Discord punishment notifications**: `discord.punishment-log` (private staff log: which types, which events - issued / modified / revoked / expired / appeal / judicial - and which fields, including originating server, punishment id, expiry, evidence count, source and network status; own webhooks optional) and `discord.public-log` (optional public announcements, **off by default**, sanitized by construction: player, type, duration/expiry, server and - only if `show-reason` - the reason; never the issuing staff member, evidence, notes, internal ids or addresses; its own webhooks only). No duplicates, retries and rate limits as before; upgraded installs do not start posting new kinds of notice by themselves.
* **Storage foundation for the network release**: database dialect layer (SQLite today; MariaDB/MySQL plug in behind the same interface), schema v3 migration with automatic backup, globally unique ids (UUID) and the originating server name on every record, unique operation ids on audit entries and punishments, `network.server-name`. Everything works standalone.
* Commands: `/ap jail`, `/ap unjail`, `/ap appeal`, `/ap appeals`, `/ap punishment`, `/ap judicial [status|recount]`.
* In-game alerts and logs say "punishment #12" instead of "case -" for events that have no case; new `alert.punishment-expired`, `command.jail-guard` messages (older `messages.yml` files fall back to the built-in text).

**Changed**
* With the judicial system off the staff dashboard, target screen, work queue, command list, scheduled checks (custody deadlines/reconcile) and notifications show/run only what still applies; historical cases remain readable (`/ap case`, `/ap cases`, case history) and exportable.
* History lists open the punishment screen (right-click opens it from the active-punishment list; left-click still revokes there).
* `punishment.revoke` audit/Discord events carry the same structured facts as issuance.
* Warning chat text no longer says "a case against you": "Further breaches may lead to further action against you." (new installs; existing `messages.yml` files are not rewritten).
* A reload that flips `judicial.enabled` is audited and logged.

**Fixed**
* `docs/CHANGELOG.md` was empty since 1.2.0 (rebuilt here).
* A punishment's jail release now has one code path for judicial sentences and standalone jail; a revoked jail punishment whose release is not complete is retried automatically and the staff member is told so instead of seeing a plain success.

**Upgrade:** replace the jar and restart. Schema v2 -> v3 (automatic, backed up to `plugins/AuraPunishments/backups/`; nothing is deleted or rewritten, only evidence and appeals tables are rebuilt with their rows copied). `config.yml`, `messages.yml`, `offenses.yml` are never rewritten; a reference copy with every new option is written once as `config-1.3.0-reference.yml`. Nothing changes behaviour until you edit config. Rollback: [ROLLBACK.md](ROLLBACK.md) (restore the pre-migration backup together with the previous jar).

## 1.2.1 - optional type-the-name confirmation
The "enter the player's name" step before bans, IP bans and permanent punishments is optional and off by default (`punishments.quick.type-name-to-confirm`; `direct.type-name-to-confirm: []`). No migration, no permission changes.

## 1.2.0 - dialog windows replace chat input
Every text entry in the GUI is a Minecraft dialog with a text box and Submit / Cancel. Nothing is read from chat, so nothing can leak to chat plugins, Discord relays or logs. `gui.prompt-cancel-word` is ignored. No migration.

## 1.1.1 - chat input hotfix
**Fixed**
* Text typed in answer to a GUI prompt (punishment/warning reasons, evidence references, case descriptions and notes, verdict and sentencing reasoning, review notes, durations, player names, ...) could be seen by other plugins as ordinary chat - and therefore broadcast, relayed to Discord or logged - because AuraPunishments only captured Paper's *modern* chat event, which Paper fires **after** the legacy one. Prompt answers are now captured at the first event of the line, silenced for every later listener, and processed exactly once. See [ROOT_CAUSE_ANALYSIS_1.1.1.md](ROOT_CAUSE_ANALYSIS_1.1.1.md).
* A muted player's chat line reached legacy-event plugins before the mute cancelled it. Mutes now stop the line at the first event; one notice per line.
* A late event of an earlier chat line could cancel or answer the *next* prompt. Twin matching now works in both event orders.
* Plugin disable/shutdown now tells staff with an open prompt that nothing was changed.

**Changed**
* `plugin.yml`: AuraPunishments loads before common chat plugins (`loadbefore`) and no longer soft-depends on Essentials, TownyChat or DiscordSRV.
* New startup / `/ap status` warning when CoreProtect is configured to log cancelled chat.

## 1.1.0 - owner review, better warnings, Discord routing
Warning titles/sounds/action bar and warning history; owner review of quick punishments; Discord channel routing with a persisted outbox; work queue; defendant communication; read-only CoreProtect evidence; case export; appeal tickets; clearer custody events. Schema v1 -> v2 (automatic, backed up). See [RELEASE_NOTES.md](RELEASE_NOTES.md).

## 1.0.0 - first release
`/punish` GUI, judicial workflow with pretrial custody through JailReloaded, direct punishment and emergency actions, enforcement of bans/IP bans/mutes/warnings/kicks, hierarchy and rank limits, hash-chained audit log, Discord webhooks, validated YAML configuration. See [RELEASE_NOTES.md](RELEASE_NOTES.md).
