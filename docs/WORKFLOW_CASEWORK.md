# The case workflow (1.3.1): cases without the court

For staff and administrators. This is the middle path between the full judicial system and plain "punish immediately": **you still open a case, add evidence and notes, and punish from the case - but there is no court.** No pretrial detention, no hearing, no judge, no verdict, no sentencing, no time-served credit.

```yaml
judicial:
  enabled: false      # the court is off

casework:
  enabled: true       # the case workflow is on (the default for new installs)
```

## What staff do

1. `/punish <player>` -> **Create Case** (the first button on the screen).
2. Choose the offense, write what happened, add evidence (links, screenshots, chat logs, CoreProtect lookups ...) and, if you like, notes. **Submit.** The case opens as **Open**; you are its owner.
3. On the case screen press **Punish** -> choose Warning, Kick, Mute, Ban, IP Ban or Jail -> duration, reason, (optionally more evidence and a note) -> **Continue** -> **Confirm**.
4. The punishment **takes effect immediately and is final**. It is linked to the case. **The case stays open**: you can keep adding evidence and notes, see the punishment on the case, record an appeal against it, and close the case when you are done (**Change status -> Closed**, with a reason).

A closed case can be reopened (Change status -> Under Review -> Open) and punished from again. Closing a case never lifts what was issued from it: the mute keeps running, the prisoner stays in jail.

## What the quick buttons do

They are still there. **Issue Warning / Kick / Mute / Ban / IP Ban / Jail** and `/ap warn|kick|mute|ban|ipban|jail` work exactly as with the judicial system off: one click, no case, evidence and note attached to the punishment itself. Use them for routine things; open a case when the matter deserves a record, evidence and follow-up.

Administrators who want bans (or any type) to *always* come from a case list those types:

```yaml
casework:
  enabled: true
  require-case-for: [BAN, IPBAN, JAIL]   # quick buttons and /ap commands refuse these with an explanation
```

## What is checked when you punish from a case

Everything a quick punishment checks, plus the case's own rules:

| Rule | Where it comes from |
|---|---|
| You may issue this type; the duration is within your tier; permanent needs `punish.<type>.permanent` | your permissions (unchanged) |
| You outrank the player; immunity; self-protection | hierarchy (unchanged) |
| The case is **about this player** | the case's accused |
| The case is **open** (Open, Under Investigation, Under Review) | closed cases must be reopened first |
| The offense **allows** this punishment and its **maximum duration** (permanent only where the offense has no cap) | `offenses.yml` (a kick is not a sentence and is always allowed) |
| Bans / IP bans need evidence | `punishments.quick.require-evidence`; evidence already **on the case** counts, so you do not re-attach it |
| Owner review | a ban from a case is reviewed exactly like a quick ban, and the review points to the case |
| You can see the case | `case.view.all` / `case.view.own` |

Nothing new to grant: **no new permission nodes** were added in 1.3.1. Whoever may punish (`punish.<type>`) and may see cases may punish from a case.

## What is not here (on purpose)

Pretrial detention, direct punishment, assigning a judge, scheduling a trial, hearing references, verdicts, sentencing, time-served credit, case-level appeals, and the notices that tell an accused about hearings. The status chooser offers only **Open, Under Investigation, Under Review, Closed**. Staff who open a case are its owner (the "prosecutor" field, shown as "Opened by"). Every attempt to use a court tool is refused with an explanation and changes nothing.

## Appeals

Appeals are filed against the **punishment** (history -> the punishment -> Appeals, or `/ap appeal <id> [-t <ticket>] <grounds>`), with the Discord Ticket Tool reference, exactly as without cases ([APPEALS.md](APPEALS.md)). An appeal never suspends the punishment. If it is overturned the punishment is revoked and the case records it.

## Switching between modes

| `judicial.enabled` | `casework.enabled` | State | What staff see |
|---|---|---|---|
| true | (ignored) | **ENABLED** | the full judicial system, as in 1.2 |
| false | true | **CASEWORK** | cases and Punish-from-case; no court |
| false | false (or the section is missing) | **DISABLED** / **WIND_DOWN** | punishments only; cases are history |

* **Court -> case workflow** (`judicial.enabled: false`, `casework.enabled: true`): nothing is released, closed or resolved for you. Any pretrial detainee, case awaiting a verdict or sentence, or case appeal still open is **reported** (console, administrators on join, `/ap judicial`, the staff dashboard banner) and the court screens stay reachable **only so you can finish them**. When they are all resolved the court screens disappear. New court work is refused.
* **Case workflow -> court** (`judicial.enabled: true`): every case you opened keeps its history, evidence, notes and the punishments linked to it. Open cases simply gain the court tools. Punishments that were issued from cases are ordinary final punishments and stay as they are.
* **Case workflow -> nothing** (`casework.enabled: false`): cases become read-only history (nobody can open new ones); punishments linked to them stay in force.
* A server upgraded from 1.0-1.3.0 has no `casework` section, which means **off** - upgrading changes nothing by itself. To get the workflow, add the two lines above and run `/ap reload`.

## Discord and logs

A punishment from a case is announced like any other (`discord.punishment-log`, with the case reference), and the case timeline, history screens and audit log record who issued it and why.
