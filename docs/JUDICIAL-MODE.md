# The judicial switch (`judicial.enabled`)

One jar, one switch. The judicial system - cases, pretrial detention, hearings, verdicts and sentencing - can be turned off. With it off, **a punishment is final and enforceable the moment staff issue it**; there is no mandatory case, no pretrial detention, no hearing, no verdict and no sentencing step. Everything else in AuraPunishments keeps working.

```yaml
judicial:
  enabled: false     # new installs      |   true  = exactly the behaviour of 1.0 - 1.2
```

> **1.3.1:** "off" has two flavours. With `casework.enabled: true` (the default for new installs) staff still open cases and punish from them - just without the court. That is the CASEWORK state described in [WORKFLOW_CASEWORK.md](WORKFLOW_CASEWORK.md). Everything below describes the plain off mode (`casework.enabled: false`, or the section missing), which is exactly 1.3.0.

## Which value do I get?

| Situation | Value |
|---|---|
| Brand-new install (no `config.yml` yet) | the shipped file says `enabled: false` |
| Upgrade from 1.0 / 1.1 / 1.2 (your `config.yml` has no `judicial.enabled` line) | **on** - nothing changes by itself. A reference file `config-1.3.0-reference.yml` is written once, and the console says so |
| You wrote the line yourself | **your value, always** - it is never changed by detection, restart or upgrade |
| `config.yml` deleted on a server that already has cases | a fresh file is generated (off); the open work is found in the database and the server starts in **wind-down** (below), warning you |

The switch is read on start and on `/ap reload` (a change is audited and logged).

## The three states

| State | When | What happens |
|---|---|---|
| **ENABLED** | `enabled: true` | Everything as in 1.2.x. |
| **DISABLED** | `enabled: false` and nothing judicial is open | Punishments are final and immediate. Judicial screens, buttons, commands (`/ap judge`), work-queue entries, custody/judicial scheduled checks and judicial notifications are hidden or idle. **Historical cases stay readable and exportable** (`/ap case`, `/ap cases`, "Case history" on the dashboard, history screens) for staff who hold the permissions. |
| **WIND_DOWN** | `enabled: false` but cases, pretrial detainees or case appeals are still open | **Nothing is released, closed, deleted or resolved automatically.** New judicial work is refused (new case, new pretrial detention, direct punishment, "refer to a case", reopening a finished case). Existing work can be finished with the normal tools. Administrators are told at startup (console), when they join, in `/ap status`, in `/ap judicial` and on the staff dashboard (orange "Judicial wind-down" banner) until the last item is resolved. When it is, the state becomes DISABLED by itself. |

Judicial punishments already in force (bans, mutes, jail sentences from sentences) **stay enforceable and keep expiring normally** in every state.

## Switching OFF on a server that has judicial work

1. Set `judicial.enabled: false`, run `/ap reload` (or restart). The plugin counts what is open: `/ap judicial`.
2. Nothing happens to anyone. A detainee stays in JailReloaded, a case stays open, appeals stay pending.
3. Finish the open items with the usual tools while the state is WIND_DOWN:
   * **Pretrial detainees** - release them (`/ap judge` -> custody, or the case screen "Release") or impose a sentence. A detainee's JailReloaded record is only ever touched by an explicit staff action, and a release still completes at the player's next login if they are offline.
   * **Open cases** - give a verdict and sentence, dismiss, or close them (`/ap cases open`, the case screen). The status "closed" does not undo a punishment already imposed.
   * **Case appeals** - decide them (`judicial.overturn` or `appeal.decide`). New appeals are recorded on the punishment instead (see [APPEALS.md](APPEALS.md)).
   * **Emergency actions that opened a case before the switch** - review them as usual.
4. When `/ap judicial` reports nothing open, the state is DISABLED. You can switch back to `true` at any time and everything is restored - no data is lost either way.

Things that need an administrator's decision (the plugin never decides them for you): a detainee you want to keep jailed after switching off (sentence them to a jail punishment: when the judicial system is off, a *new* jail is an ordinary punishment), a case you want to keep open (leave the switch on until it is finished).

## Switching ON

`enabled: true` + `/ap reload`. Cases, pretrial detention, verdicts, sentencing, direct punishments, the judicial dashboard and the work-queue entries return exactly as they were. Punishments issued while it was off stay as they are (they were final decisions); appeals recorded on a punishment stay on the punishment.

## What is hidden, what stays (judicial OFF)

| Hidden / refused | Stays |
|---|---|
| Create case, view cases (except history), pretrial detention, submit evidence *to a case*, direct punishment, judicial review, verdict/sentence screens, `/ap judge`, custody deadline alerts and reconciliation, defendant notifications, refer-review-to-case | `/punish`, dialogs, confirmation screens, warnings (chat/title/sound), kicks, mutes (temporary/permanent), bans, IP bans, **jail**, emergency actions, owner review, evidence and staff notes (on the punishment), history, modify/revoke, **appeals**, Discord ticket references, offline players, hierarchy, rank limits, LuckPerms, AuraCommandFilter, mute enforcement, Discord logging, audit log, admin commands, historical case records |

## Related documents

[WORKFLOW_NO_JUDICIAL.md](WORKFLOW_NO_JUDICIAL.md) (day-to-day with the system off) - [JAIL.md](JAIL.md) - [APPEALS.md](APPEALS.md) - [PERMISSIONS.md](PERMISSIONS.md) - [DATABASE.md](DATABASE.md) - [ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md)
