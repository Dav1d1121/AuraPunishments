# AuraPunishments 1.0.0 - Staff Handbook

For every Aurehaven staff member. What you can do depends on your rank's permissions ([PERMISSIONS.md](PERMISSIONS.md));
buttons you may not use are shown **locked** (grey, "Locked - not available to you") or hidden, depending on server config.
Showing a button never grants it: the server re-checks your authority every time you act.

## 1. The big picture

```
Minor / moderate offense                       Major offense, proven beyond reasonable doubt
        |                                                   |
   Create CASE  (evidence)                      DIRECT PUNISHMENT (evidence + justification)
        |                                         - the exception, not the normal workflow
   Pretrial detention (optional, JailReloaded; 48h review limit)
        |
   Awaiting trial -> hearing in Discord (judge: the owner) -> VERDICT -> SENTENCE
```

Pretrial detention is **not** a punishment and **not** a verdict. Nobody is held indefinitely: every detention has a
review deadline (default 48h) and is released automatically if nobody decides.

## 2. `/punish <player>`

Opens that player's screen directly (online players and anyone who has joined before - names are verified, never guessed;
if two players ever used the same name you must use their UUID). You cannot open yourself, and you cannot act on staff of
equal or higher rank.

```
 Row 1  [Create Case] [View Cases] [Submit Evidence] [Pretrial Detention] [Jail Status] [History] [Staff Notes]
 Row 2  [Warning] [Kick] [Mute] [Ban] [IP Ban] [Direct Punishment] [Emergency Action]
 Row 3  [Judicial Review]  [Release / Revoke Punishment]
 Below  the player's open cases (click to open)       Top: head, online status, active punishments, custody, counts
```

Other commands: `/punishments` (your dashboard), `/ap help`, `/ap case <id>`, `/ap cases`, `/ap history <player>`,
`/ap judge`, and command-line equivalents `/ap warn|kick|mute|ban|ipban|unmute|unban ...` (same rules as the GUI).

**Typing text:** when a button needs text (reason, description, evidence, note) the GUI closes and asks you to **type in
chat**. Your line is captured privately (nobody else sees it). Type `cancel` to abort. You have 90 seconds.

## 3. Create a case (the normal path)

1. `/punish <player>` -> **Create Case**.
2. **Offense** - pick the category (harassment, griefing, ...). The default severity comes with it; click **Severity** to change.
3. **Incident description** - what happened (at least 10 characters).
4. **Contextual notes** - optional.
5. **Evidence** - add at least what the offense requires (harassment, griefing, theft: 1). Pick the type (screenshot, video,
   chat log, **CoreProtect** lookup, server log, written report, anti-cheat alert, link, other), then type/paste the
   reference. Links must start with `http(s)://`. Written reports need 30+ characters.
6. **Submit Case.** You are recorded as the **prosecutor**. A duplicate open case for the same player and offense within
   10 minutes is refused (add evidence/notes to the existing case instead).

*Trainees* use the same screen to **report an incident**: it creates an ordinary case, but only you and the judge can see
it and you cannot start detention.

## 4. Evidence and notes

* **Submit Evidence** (from the player screen or inside a case) - evidence is **never deleted**. To fix a mistake use
  **Right-click: void** (reason required) or **Shift + Right-click: correct** (voids and replaces). Both are audited.
* **Staff Notes** - private notes on a case or on the player. Sensitive notes need extra permission. Notes are voided, not deleted.
* Left-click an evidence item to get a clickable link / copyable reference in chat.

## 5. Pretrial detention

Use it when the player should be held until a trial (for example, to prevent evidence tampering or repeat offenses).

1. **Pretrial Detention** on the player screen (or the **Custody** button inside the case) -> choose the case -> confirm.
2. JailReloaded imprisons the player. If JailReloaded cannot do it you are told **why** and nothing is recorded as done.
3. The case shows the review deadline. Staff with custody alerts are warned 12h and 1h before the deadline, and every
   hour once overdue. If nobody decides, the player is **released automatically** 12h after the deadline.
4. **Release** (Custody button or Jail Status) needs a reason, which is recorded. A judge can **extend** the deadline (twice, 24h each).

Do **not** use JailReloaded's `/unjail` for these prisoners; it is blocked with a message naming the case. Release through
the case so the record stays correct.

An offline player is registered with JailReloaded and placed in jail when they next log in; releasing an offline
prisoner completes at their next login ("release accepted").

## 6. Preparing the prosecution

Open the case (`/ap case 12`, `/ap cases`, or the player's open-case list):

* **Change status** - *Under Investigation*, *Awaiting Trial*, ... only valid next steps are enabled.
* **Schedule trial** - type `yyyy-MM-dd HH:mm` (server time) or a delay like `2d`.
* **Hearing reference** - the Discord voice channel name or link.
* **Edit details**, **Reassign prosecutor**, add evidence/notes. Everything is versioned: if someone else changed the case
  while you were looking, your change is refused and you are asked to reopen it (nothing is overwritten).

The hearing itself happens in a private Discord voice call: judge (owner), you as prosecutor, the accused speaking for
themselves, optional staff advisers. The judge records the verdict in the GUI ([Judicial handbook](JUDICIAL_HANDBOOK.md)).

## 7. Quick punishments (within your authority)

Warning / Kick / Mute / Ban / IP Ban -> choose the duration (anything above **your** limit is locked), enter the reason,
add evidence where required (bans and IP bans by default), **Continue**, confirm. Bans and IP bans also ask you to type the
player's name to confirm. Notes:

* **Kick** needs the player online. A **warning** to an offline player is shown when they next log in.
* A player can have only one active mute and one active ban at a time.
* Command line: `/ap mute Steve 30m Spamming global chat`, `/ap ban Steve 7d Cheating -e https://link`.
* Revoke with **Release / Revoke Punishment** or `/ap unmute|unban <player> <reason>` (a reason is always required). You can
  revoke punishments you issued yourself; senior staff can revoke any.

## 8. Direct punishment (major offenses only)

For serious, **proven** misconduct (severe cheating, exploitation, malicious attacks). It skips the hearing, so the screen
will not let you continue until **all** of these are done:

1. a *direct-eligible* major offense (only those appear);
2. severity MAJOR or SEVERE;
3. the punishment (mute, ban, IP ban - within your limit; permanent needs its own permission);
4. a **detailed description**;
5. **why bypassing the hearing is justified**;
6. the required **evidence** (usually 2 items);
7. confirmation (bans: type the player's name).

It creates a case marked *Direct Punishment Issued* with the evidence and justification attached, and alerts senior staff
(and Discord). It can be appealed and reviewed like any other outcome. **If in doubt, open a case.**

## 9. Emergency actions

For an *urgent threat in progress* (mass griefing, abuse flooding chat), staff with the separate emergency permission can
apply a **temporary mute or ban (max 24h)** immediately. A reason is mandatory, senior staff are alerted, and it **expires
by itself** unless a senior reviews it and replaces it with a proper punishment. Open the case it created to follow up.

## 10. History

**Punishment History** (or `/ap history <player>`) shows every case, finding and acquittal, and every punishment including
revoked and reversed ones. Acquittals, dismissals and overturned findings are shown as such and **never** count against the
player when a later sentence is recommended.

## 11. Things that are refused (and why)

| Message | Meaning |
|---|---|
| "You do not have permission..." | The node is missing; ask an admin if you should have it. |
| "...equal or higher staff authority" / "immune" | Hierarchy protection. |
| "...exceeds your limit... (your maximum: 1h)" | Duration tier. |
| "...requires at least N evidence reference(s)" | Add evidence first. |
| "This case was changed by someone else" | Reopen the case; your edit was not applied. |
| "JailReloaded is not available..." | The jail plugin is down/incompatible; nothing was changed. Tell an admin. |
| "Still working on your previous action" | Wait a moment; duplicate clicks are ignored on purpose. |
