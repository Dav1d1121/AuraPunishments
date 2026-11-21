# AuraPunishments 1.1.0 - Judicial Handbook

## What's new in 1.1.0 (judicial)

* **Work queue** (dashboard -> *Work queue* or `/ap queue`): everything waiting for a decision, most urgent first (overdue custody, emergency reviews, overdue then pending quick-punishment reviews, appeals, awaiting sentencing / verdict / trial, further investigation). Order is configurable (`dashboard.priority.*`). Click an item to open the case or review.
* **Custody timing is unchanged:** a 48 h review deadline, then (if `ALERT_THEN_RELEASE`) a 12 h grace period - **about 60 hours** before automatic release. Both are configurable; the upgrade does not change your values.
* **Time served:** pretrial *custody time is credited automatically to jail sentences*; for **bans and mutes it is your decision** (toggle on the sentencing screen). An admin can change which types default to automatic credit (`judicial.time-served.default-credit-for`). A permanent sentence never gets credit.
* **Defendant messages** are sent automatically at detention, scheduling/postponement, status changes you configure, verdict, sentence, release and acquittal. Only the verdict/sentence messages state a finding.
* **Appeals:** *Take appeal* marks it *Under review*; link the Discord Ticket Tool ticket with *Appeal ticket* (number or channel link); decisions are logged to the appeal channel.
* **Owner review of quick punishments** is separate from trials: a reviewer can *Refer to judicial case*, which opens a standard case against the player (the quick punishment stays in force) for a full hearing.
* **Evidence:** CoreProtect lookups can be attached as evidence (read-only snapshot). They are evidence like any other: they never decide a case and nothing is rolled back.
* **Case summary export** (`/ap export <case>`) produces a Markdown/text file for a hearing or an appeal ticket.

For the judge (the owner at launch) and anyone later granted judicial powers. Hearings take place in a private Discord
voice call; AuraPunishments records the **outcomes**. It does not run the call, manage voice channels, or count jury votes.

## 1. Roles and powers

| Role | Who | Plugin permissions |
|---|---|---|
| **Judge** | Owner (only holder initially) | `judicial.verdict`, `judicial.sentence`, `judicial.release`, `judicial.overturn` (+ `judicial.manage`, `judicial.view`) |
| **Prosecutor** | The staff member who opened the case (default) | case/evidence/notes permissions |
| **Defendant** | The accused, speaking for themselves | none |
| **Advisers / jury** | Staff invited when the judge wants another opinion | none required - the **judge decides** |

Judicial authority is assigned **independently in LuckPerms** (see [PERMISSIONS.md](PERMISSIONS.md), "a second judge").

## 2. Dashboard

`/ap judge` (or **Judicial Review**): pending trials, awaiting verdict, **awaiting sentencing**, **custody** (with
overdue reviews), **appeals**, **emergency reviews**, all cases. Custody overdue and emergency reviews raise Discord/in-game alerts.

## 3. Case life cycle

```
Open -> Under Investigation -> Awaiting Trial -> In Trial -> Awaiting Verdict
                                                     |
        Guilty -> (Awaiting Sentencing) -> Sentenced -> Appealed / Under Review / Closed
        Not Guilty | Dismissed | Continued (back to Awaiting Trial)
Direct Punishment Issued -> Appealed / Under Review / Closed
```

Only valid transitions are offered; every transition records who and when. Acquittals (**Not Guilty**) and **Dismissals** are
final statuses distinct from guilty findings and are never confused with them in history or escalation.

## 4. Before the hearing

On the case screen (`/ap case <id>`):

1. Review **Evidence** and **Notes**.
2. **Assign judge** (click = yourself). Only the assigned judge may enter the verdict and sentence unless another holder has
   `judicial.verdict.any`. (If no judge is assigned when you enter a verdict, you become the assigned judge.)
3. **Schedule trial** (`2d` or `yyyy-MM-dd HH:mm`) - this moves the case to *Awaiting Trial* and alerts Discord.
4. **Hearing reference** - the Discord channel or link.
5. If the accused is detained, mind the **review deadline** (48h default). **Extend** (right-click Custody; reason required)
   at most twice for 24h, or release. If nothing is decided the player is released automatically after the grace period.
6. At the start of the hearing: **Change status -> In Trial** (needs an assigned judge).

## 5. The verdict

Case screen -> **Enter verdict** (available *In Trial* / *Awaiting Verdict*). Choose, enter your **reasoning** (required),
confirm:

| Verdict | Effect |
|---|---|
| **Guilty** | Case becomes *Guilty (awaiting sentence)*; sentencing unlocks. |
| **Not Guilty** | Case closes as an **acquittal**. No sentence. Pretrial custody is **released through JailReloaded** (the confirmation tells you whether it completed or is pending the player's next login). |
| **Dismissed** | Closes with your recorded reason; custody is released as above. |
| **Continued / Pending review** | Case returns to *Awaiting Trial* for more proceedings; custody is untouched. No finding is recorded. |

If JailReloaded cannot release an acquitted player at that moment, the **verdict is still recorded**, staff are alerted
("WARNING: ... could not be released yet"), and the release is retried automatically every minute until it succeeds.

## 6. Sentencing

Case screen -> **Impose sentence** (after *Guilty*). The screen shows the player's **prior guilty findings** and a **recommended
sentence** (a star) from `offenses.yml`; you are free to choose differently. Prior findings count only previous guilty
verdicts and direct punishments that were not overturned - **never** acquittals, dismissals or unproven allegations.

Choose: **Warning, Mute, Ban, IP ban or Jail** (only those allowed for the offense), a **duration** (presets or custom; permanent
only where the offense and your authority allow; jail must be finite), toggle **time-served credit**, enter the **reasoning**,
submit, confirm.

### What happens behind the scenes

* **Time served** - pretrial detention time is deducted from **timed** sentences (jail/mute/ban/IP ban), at the configured rate
  (default 1:1). Permanent sentences get no credit. A sentence fully covered by time served is recorded as already served.
* **Jail sentence while detained** - the *same* JailReloaded record is converted from "until released" to the remaining time;
  no second jail is created. If the player was not detained, JailReloaded jails them for the full sentence.
* **Mute / Ban / IP ban / Warning while detained** - the player is **released from custody first** (verified through JailReloaded),
  then the punishment is applied. The two can never contradict each other. If the release fails, the sentence is **not** applied and
  the case stays *Guilty* so you can retry.
* Everything (judge, verdict, sentence, duration, reasoning, credit, timestamps) is stored and audited. If the server crashes
  between the jail step and the database step, the interrupted sentence is completed or cleanly abandoned at the next start.

## 7. Appeals, review and reversal

* **File appeal** (needs `case.appeal.file`) against a *Sentenced* case or a direct punishment - the grounds are recorded.
* **Decide appeal** (`judicial.overturn`): **Upheld** (outcome stands), **Overturned** (all the case's punishments are revoked, custody
  released, the case becomes *Not Guilty* and is **flagged overturned**, so it can never count towards escalation), or **Modified**
  (punishments revoked and the case returns to *Guilty* so a new sentence can be imposed; direct punishments go to *Under Review*).
* **Overturn findings** (any time after sentencing/direct punishment): same effect as an overturned appeal, with your reasoning.
* Nothing is deleted. The original verdict, the sentence and the reversal all remain in the record and the audit log.
* **Revoking** a single punishment (Release/Revoke) does not change the case's finding; use *overturn* if the finding itself was wrong.

## 8. Reviewing direct punishments and emergency actions

* A **direct punishment** appears in the dashboard and Discord with its justification and evidence; appeal/overturn as above.
* An **emergency action** (max 24h) needs a senior review within 12h: open its case and choose **Escalate to trial** (the action runs
  to its expiry, the case goes to trial) or **Revoke and dismiss**. A formal direct punishment can also replace it (the emergency
  action is then revoked automatically). Overdue reviews alert every hour; the action expires on its own either way.

## 9. Checklist for a clean hearing

1. Case has evidence, a judge, a scheduled trial and a hearing reference.
2. Detention (if any) is within its review deadline.
3. Hearing held in Discord; status *In Trial*.
4. Verdict entered **with reasoning**; custody released if not guilty/dismissed.
5. If guilty: sentence chosen, credit reviewed, reasoning entered, confirmed.
6. Check `/ap cases` for anything left in *Awaiting Sentencing* or an unreviewed appeal.
