# Day-to-day moderation with the judicial system off

For staff. Nothing here needs a case. Every action takes effect immediately and is final; it stays on record, can be revoked with a reason, and can be appealed.

## Punishing (the normal way)

`/punish <player>` -> choose the punishment -> set a duration (timed types), enter the reason in the dialog, optionally add **evidence** and a **staff note**, press CONTINUE and then CONFIRM. The confirmation screen says the punishment is immediate and final.

| Button | What it does |
|---|---|
| Issue Warning | official warning on record (chat message, title, sound; shown at next login if offline) |
| Issue Kick | disconnects an online player |
| Issue Mute | blocks chat, private messages and channels; temporary or permanent |
| Issue Ban / Issue IP Ban | blocks login (and the last known address); temporary or permanent |
| Issue Jail | puts the player in JailReloaded for a set time, released automatically ([JAIL.md](JAIL.md)) |
| Emergency Action | short protective mute/ban for an urgent threat; expires by itself; senior review |

Limits, hierarchy and evidence rules are unchanged: you may only act against a lower rank; the longest `limit.<type>.<tier>` you hold is your cap; permanent punishments need `punish.<type>.permanent`; types listed in `punishments.quick.require-evidence` (BAN and IPBAN by default) need at least one evidence item. Jail does **not** need evidence unless an administrator adds `JAIL` to that list.

## Evidence and notes

* **At issuance:** the Evidence and Staff note buttons on the punishment screen. Evidence types: screenshot, video, chat log, CoreProtect, server log, incident report, anticheat, external link, other.
* **Afterwards:** `/punish <player>` -> Punishment History -> Punishments tab -> click the punishment (or `/ap punishment <id>`) -> Evidence / Staff notes. The issuer, and staff with `case.evidence.add`, can add evidence; `case.evidence.view` is needed to see it; `case.evidence.void` to void it. Evidence is never deleted: it is voided with a reason and may be replaced by a corrected item (the last evidence item of a punishment type that requires evidence cannot be voided without a replacement). Each item records who submitted it, when, its type and description.
* Staff notes: `case.note.add` / `case.note.view` / `case.note.void`; sensitive notes need `sensitive.notes`. Notes are voided, never deleted.
* Evidence and notes added while cases existed stay on those cases and are shown with the punishment when it came from a case.

## Revoking, releasing, modifying

* **Revoke** (history -> punishment -> Revoke, `/ap unmute`, `/ap unban`, the "Release / Revoke Punishment" button): needs a reason; the issuer may revoke their own quick punishments with `punish.revoke`; `punish.revoke.any` revokes anything.
* **Release from jail early:** `/ap unjail <player> <reason>` or the punishment screen. Revoking a jail punishment frees the prisoner; if JailReloaded cannot do it right now you are told, and it is retried automatically.
* **Modify:** the owner review (below) can shorten/lengthen/re-word a punishment; an upheld-with-reduction appeal shortens it. The original expiry is kept for the record.

## Owner review (oversight, not approval)

Substantive punishments (by default mutes, bans, IP bans - configurable by type, and "permanent only" per type) also create a review record. The punishment is in force the whole time. The owner team can uphold, modify, reverse, ask the issuer to explain or request more evidence. Warnings and kicks are never reviewed. `/ap review` (queue) and `/ap review mine` (your own punishments under review).

## Appeals

Players appeal in Discord (Ticket Tool). Staff record the appeal against the punishment and reference the ticket; reviewers uphold, reduce or overturn it. See [APPEALS.md](APPEALS.md).

## Emergency actions

For an urgent threat when no senior staff can be asked first: a short mute or ban (default 6 h, at most 24 h) with a description of the threat. It takes effect immediately, alerts senior staff, expires on its own and must be reviewed within the deadline (default 12 h): **uphold** (it runs to its expiry) or **revoke**. With the judicial system off there is no case and no "escalate to trial".

## Commands

`/punish <player>`, `/punishments` (dashboard), `/ap warn|kick|mute|ban|ipban|jail <player> ...`, `/ap unmute|unban|unjail <player> <reason>`, `/ap history <player>`, `/ap punishment <id>`, `/ap appeal <id> [-t <ticket>] <grounds>`, `/ap appeals`, `/ap review [mine]`, `/ap queue`, `/ap warnings <player>`; administrators also have `/ap judicial`, `/ap status`, `/ap audit [verify]`, `/ap discord [status|retry]`, `/ap reload`. `-e <link>` adds evidence on the command line.
