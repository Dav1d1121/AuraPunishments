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
* **Safety**: default-deny permissions, strict (offline-aware) staff hierarchy, immunity, duration tiers, optimistic versioning
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
