# Jail as a punishment

JailReloaded still does the imprisonment (teleport, cell, inventory, release); AuraPunishments records why and for how long, verifies the result, and keeps the record truthful. Nothing here is a jail of our own.

## Two different things

| | Pretrial detention (judicial system) | Jail punishment (this page) |
|---|---|---|
| Needs a case | yes | **no** |
| Meaning | holding someone until a hearing; *not* a punishment, review deadline and alerts | a punishment with a fixed end |
| Created by | case screen -> Pretrial Detention | `/punish` -> Issue Jail, `/ap jail` |
| Ends | release, sentence, deadline | JailReloaded's timer, early release, revoke |
| Available | judicial system on (or wind-down for existing detainees) | judicial system **off** (with it on, jail is a sentence) |

They never share a record: a jail punishment is not custody, and custody is not a punishment.

## Issuing

GUI: `/punish <player>` -> **Issue Jail** -> duration -> reason -> (evidence, staff note) -> confirm. Command: `/ap jail <player> <duration|permanent> <reason> [-e <evidence>]`.

* Permission `aurapunishments.punish.jail`; the duration is capped by your `aurapunishments.limit.jail.<tier>` (tiers in `punishments.limits.jail`, defaults 1h / 6h / 1d / 7d); permanent ("until released") needs `aurapunishments.punish.jail.permanent`. Hierarchy applies. Evidence is optional unless `JAIL` is added to `punishments.quick.require-evidence`.
* Jail/cell: `punishments.jail.jail-name` / `cell-name` (blank = `custody.jail-name` / `custody.cell-name`, then JailReloaded's own default); `mute-while-jailed`.
* An **offline** player is registered with JailReloaded and placed at their next login (the record says `PENDING_PLACEMENT`, then `HELD`).

## What is recorded and verified

Three steps, each recoverable: (1) the punishment is recorded as PLACING together with a pending operation; (2) JailReloaded is asked on the main thread and its real prisoner records are checked; (3) the verified result is recorded (HELD / PENDING_PLACEMENT). If JailReloaded refuses or is down, **nobody is reported as jailed**: when it is down nothing is created at all; when it refuses, the attempt stays on record, closed as "Jail was not started: <reason>". A crash between the steps is resolved at the next start by comparing with JailReloaded (a prisoner tagged `[AP-J0012]` is adopted; an unconfirmed attempt older than 60 s is closed as not started). The punishment record carries the JailReloaded prisoner id, jail name and state.

## Ending a jail punishment

* **Time up:** JailReloaded releases the prisoner; the punishment becomes SERVED ("Sentence served"). (Jail time is JailReloaded's clock - it runs only while the player is online unless you configured JailReloaded otherwise; the expiry shown is nominal.)
* **Early release:** `/ap unjail <player> <reason>` or the punishment screen. Allowed for `punish.revoke.any`, `custody.release`, `judicial.release`, or the issuer with `punish.revoke`. The punishment is revoked with your name and reason, and the prisoner is freed. If JailReloaded cannot do it right now (down, busy, player offline) the revocation stands, you are told the release is still pending, and it is retried every minute and completed at the player's next login.
* **Released inside JailReloaded** by something other than us before the sentence ended: recorded honestly as "ended outside AuraPunishments" and staff are alerted.

## Safeguards

* JailReloaded commands that would free or alter a prisoner serving a jail punishment (`/jail unjail`, `unjailforce`, `time`, `transfer`, `clear`, `mute`, re-jailing...) are refused with a message pointing to `/ap unjail`, unless the sender has `aurapunishments.custody.override` (`custody.guard-jail-commands`, `custody.guarded-jail-commands`).
* A release or time change only ever touches the JailReloaded record whose id was stored for the punishment.
* History, audit entries (`jail.start`, `jail.failed`, `jail.release`, `jail.served`, `jail.ended-externally`) and Discord notices exist like for any punishment; the operation id ties each audit entry to its action.

## Requirements and limits

JailReloaded 1.2.x must be present and compatible (`/ap status`). Online-player jailing and the release/timer paths were verified live against JailReloaded 1.2.2 (see [TEST_REPORT.md](TEST_REPORT.md)). JailReloaded's own behaviour (teleport, inventory, offline placement) is JailReloaded's.
