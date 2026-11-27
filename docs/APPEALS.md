# Appeals (Discord Ticket Tool)

Players appeal in your Discord with **Ticket Tool**. AuraPunishments does not talk to Discord tickets: staff **record the appeal against the punishment** and reference the ticket, then reviewers decide it here, so the decision, the reviewer and the reasoning live with the punishment forever. (With the judicial system on, punishments that came from a sentence or direct charge are still appealed on their case, as before.)

## Flow

1. The player opens a ticket in Discord. Support staff open the punishment (`/ap punishment <id>`, or history -> click it) and press **Appeals** (`/ap appeal <id> [-t <ticket>] <grounds>`). They enter the grounds and the ticket number (`0042`, `appeal-0042`) or the ticket channel link (`https://discord.com/channels/...`). Anything else is refused. `appeals.require-ticket: true` makes the ticket mandatory.
2. The appeal is listed in the appeals queue (`/ap appeals`, dashboard, work queue). A reviewer presses **Take appeal** (status *Under review*, their name recorded).
3. The reviewer decides:
   * **Uphold** - the punishment stays.
   * **Reduce** - enter the new total length (measured from when it was issued; must be shorter than now). The punishment is modified immediately, the original expiry is kept for the record, enforcement uses the new end at once.
   * **Overturn** - the punishment is revoked at once with "Appeal overturned: <reasoning>".
   An explanation is required. The decision, reviewer, time, reasoning, ticket and new length are stored on the appeal; the punishment screen, audit log and Discord (appeal category) show them.
4. A decision is final; a new appeal may be recorded later if the punishment is still in force.

**An appeal never suspends a punishment.** Only the decision changes it. Appeals can be recorded against punishments that are active or on record (warnings, kicks); not against ones already revoked/expired/served.

## Who may do what

| Action | Permission |
|---|---|
| Record an appeal | `aurapunishments.case.appeal.file` |
| Link or change the ticket | `aurapunishments.case.appeal.ticket` (or `appeal.decide`, or `judicial.overturn`) |
| Take and decide | **`aurapunishments.appeal.decide`** - new in 1.3.0, never granted automatically, not part of `aurapunishments.*`. With the judicial system **on**, `aurapunishments.judicial.overturn` is accepted as well; with it **off**, only `appeal.decide` works. |
| See the queue | any of the above, or `history.view` |

Hierarchy applies: you cannot decide an appeal for a player of equal or higher staff level, and `punish.revoke`/limits are not bypassed (an overturn is an authorised revocation by decision of the reviewer). Case-based appeals that are still open when the judicial system is switched off are decided with `judicial.overturn` or `appeal.decide`.

## Discord

Appeal filed / ticket linked / decision go to the **appeal** Discord category, and (event "appeal") to the punishment log if enabled; fields: ticket, decision, reasoning. See [DISCORD_WEBHOOKS.md](DISCORD_WEBHOOKS.md).

LuckPerms example: `/lp group admin permission set aurapunishments.appeal.decide true`
