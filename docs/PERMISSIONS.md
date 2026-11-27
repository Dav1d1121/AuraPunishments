# AuraPunishments 1.3.0 - Permission Matrix

Everything is **default-deny**, including for server operators, with one deliberate exception: `aurapunishments.command` (the bare `/ap` command, which only shows help and a list of the subcommands you separately hold) defaults to **op** so the server console can run `/ap`. The console is always trusted for `/ap` subcommands; players still need each node. Nothing in this plugin trusts a rank *name*;
authority comes only from LuckPerms nodes, so your existing Trainee -> Owner groups keep working and you can
adjust any single privilege without touching code.

Having `/punish` never means being allowed to punish: opening the GUI needs `aurapunishments.punish`, and **every
action** inside it is a separate node, re-checked at the moment it runs (a node revoked while a GUI is open takes
effect on the next click).

## 1. How authority is decided

| Question | Mechanism |
|---|---|
| May this person do X? | The node for X (see appendix). |
| May they act on *this player*? | **Strict hierarchy**: `aurapunishments.level.<N>` - the highest N a player holds is their level. You may only act against a **lower** level. Players with `aurapunishments.immune` cannot be acted on at all; `aurapunishments.immunity.bypass` (Owner only) ignores this. You can never act on yourself. Works for **offline** staff too (read from LuckPerms; falls back to the level cached at last logout when LuckPerms is unavailable). |
| How long may a timed punishment be? | Duration **tiers**: `aurapunishments.limit.<type>.<tier>`; the longest tier you hold is your cap. Tier names/lengths are in `config.yml` (`punishments.limits`). Permanent needs `aurapunishments.punish.<type>.permanent` (or `aurapunishments.direct.<type>.permanent` for direct punishment). |
| May they charge this offense? | Optional per-offense `permission:` node in `offenses.yml`. |
| May they judge? | `judicial.verdict` (assignable independently via LuckPerms; the Owner is the only holder at launch) plus the assigned-judge rule (`judicial.verdict.any` overrides it). |

## 2. Recommended assignment by rank

Levels are spaced by ten so you can insert ranks later. Group names below (`trainee` ... `owner`) are examples:
**substitute your real LuckPerms group names.** The grants are *incremental*: they assume each group inherits the
group below it (`/lp group helper parent add trainee`, and so on). If your groups do not inherit, apply every
row at or below the rank.

| Rank | Level | Recommended access (incremental) |
|---|---|---|
| Trainee | 10 | Open `/punish`, submit incident **reports**, see only cases they are involved in, add/view evidence on those. |
| Helper | 20 | Create cases, add and read notes, view history, staff dashboard. |
| Jr Mod | 30 | See all cases, **initiate pretrial detention**, view custody, **kick**, **temporary mute up to 1h**, case alerts. |
| Mod | 40 | Warnings, temporary mutes up to **7d**, release a prisoner (reason recorded), custody alerts. |
| Sr Mod | 50 | Case management (edit, investigate, schedule trials, close), void evidence/notes, file appeals, judicial dashboard (read), mutes up to 30d, **emergency actions**, verdict and emergency alerts. |
| Jr Admin | 60 | **Temporary bans** (up to 7d) and **temporary IP bans** (up to 7d), revoke their own quick punishments, direct-punishment alerts. |
| Admin | 70 | **Permanent bans and IP bans**, bans up to 180d, **direct punishment** (mute / ban). |
| Sr Admin | 80 | **Revoke any punishment** (reversals), **sensitive information** (IPs, sensitive notes), audit log, **emergency review**, direct IP ban / permanent direct ban. |
| Manager | 90 | Comprehensive administration: judicial management (assign judges, trial status, extend custody), diagnostics, reload, custody override, jail sentence tiers. |
| Owner | 100 | Everything, including the judicial powers (verdict, sentence, release, overturn), `immunity.bypass` and `legacy.bypass`. |

> **The judicial powers are deliberately not granted to any rank below Owner.** `judicial.verdict`, `judicial.sentence`,
> `judicial.release` and `judicial.overturn` are separate nodes; to add a second judge later, grant them to that
> person's *user* (or a dedicated `judge` group) in LuckPerms - no code change.

## 3. Ready-to-use LuckPerms commands

Run in the console (or in-game with LuckPerms rights). Replace the group names if yours differ. Set up inheritance first
if you want the incremental behaviour (skip those lines if your groups already inherit):

```
lp group helper parent add trainee
lp group jrmod parent add helper
lp group mod parent add jrmod
lp group srmod parent add mod
lp group jradmin parent add srmod
lp group admin parent add jradmin
lp group sradmin parent add admin
lp group manager parent add sradmin
lp group owner parent add manager
```

### Trainee (`trainee`, level 10)

```
lp group trainee permission set aurapunishments.level.10 true
lp group trainee permission set aurapunishments.command true
lp group trainee permission set aurapunishments.punish true
lp group trainee permission set aurapunishments.punish.others true
lp group trainee permission set aurapunishments.report true
lp group trainee permission set aurapunishments.case.view.own true
lp group trainee permission set aurapunishments.case.evidence.add true
lp group trainee permission set aurapunishments.case.evidence.view true
```

### Helper (`helper`, level 20)

```
lp group helper permission set aurapunishments.level.20 true
lp group helper permission set aurapunishments.case.create true
lp group helper permission set aurapunishments.case.note.add true
lp group helper permission set aurapunishments.case.note.view true
lp group helper permission set aurapunishments.history.view true
lp group helper permission set aurapunishments.dashboard true
```

### Jr Mod (`jrmod`, level 30)

```
lp group jrmod permission set aurapunishments.level.30 true
lp group jrmod permission set aurapunishments.case.view.all true
lp group jrmod permission set aurapunishments.custody.initiate true
lp group jrmod permission set aurapunishments.custody.view true
lp group jrmod permission set aurapunishments.punish.kick true
lp group jrmod permission set aurapunishments.punish.mute true
lp group jrmod permission set aurapunishments.limit.mute.short true
lp group jrmod permission set aurapunishments.notify.case-created true
```

### Mod (`mod`, level 40)

```
lp group mod permission set aurapunishments.level.40 true
lp group mod permission set aurapunishments.punish.warn true
lp group mod permission set aurapunishments.limit.mute.medium true
lp group mod permission set aurapunishments.limit.mute.long true
lp group mod permission set aurapunishments.custody.release true
lp group mod permission set aurapunishments.notify.custody-started true
lp group mod permission set aurapunishments.notify.custody-alerts true
```

### Sr Mod (`srmod`, level 50)

```
lp group srmod permission set aurapunishments.level.50 true
lp group srmod permission set aurapunishments.case.manage true
lp group srmod permission set aurapunishments.case.close true
lp group srmod permission set aurapunishments.case.evidence.void true
lp group srmod permission set aurapunishments.case.note.void true
lp group srmod permission set aurapunishments.case.appeal.file true
lp group srmod permission set aurapunishments.limit.mute.extended true
lp group srmod permission set aurapunishments.judicial.view true
lp group srmod permission set aurapunishments.emergency.use true
lp group srmod permission set aurapunishments.notify.verdicts true
lp group srmod permission set aurapunishments.notify.emergency true
```

### Jr Admin (`jradmin`, level 60)

```
lp group jradmin permission set aurapunishments.level.60 true
lp group jradmin permission set aurapunishments.punish.ban true
lp group jradmin permission set aurapunishments.punish.ipban true
lp group jradmin permission set aurapunishments.limit.ban.short true
lp group jradmin permission set aurapunishments.limit.ban.medium true
lp group jradmin permission set aurapunishments.limit.ipban.short true
lp group jradmin permission set aurapunishments.limit.ipban.medium true
lp group jradmin permission set aurapunishments.punish.revoke true
lp group jradmin permission set aurapunishments.notify.direct-punishments true
```

### Admin (`admin`, level 70)

```
lp group admin permission set aurapunishments.level.70 true
lp group admin permission set aurapunishments.punish.ban.permanent true
lp group admin permission set aurapunishments.punish.ipban.permanent true
lp group admin permission set aurapunishments.limit.ban.long true
lp group admin permission set aurapunishments.limit.ban.extended true
lp group admin permission set aurapunishments.limit.ipban.long true
lp group admin permission set aurapunishments.direct true
lp group admin permission set aurapunishments.direct.mute true
lp group admin permission set aurapunishments.direct.ban true
```

### Sr Admin (`sradmin`, level 80)

```
lp group sradmin permission set aurapunishments.level.80 true
lp group sradmin permission set aurapunishments.punish.revoke.any true
lp group sradmin permission set aurapunishments.sensitive.ip true
lp group sradmin permission set aurapunishments.sensitive.notes true
lp group sradmin permission set aurapunishments.audit.view true
lp group sradmin permission set aurapunishments.emergency.review true
lp group sradmin permission set aurapunishments.direct.ipban true
lp group sradmin permission set aurapunishments.direct.ban.permanent true
lp group sradmin permission set aurapunishments.direct.mute.permanent true
```

### Manager (`manager`, level 90)

```
lp group manager permission set aurapunishments.level.90 true
lp group manager permission set aurapunishments.judicial.manage true
lp group manager permission set aurapunishments.admin true
lp group manager permission set aurapunishments.admin.reload true
lp group manager permission set aurapunishments.custody.override true
lp group manager permission set aurapunishments.direct.ipban.permanent true
lp group manager permission set aurapunishments.limit.jail.short true
lp group manager permission set aurapunishments.limit.jail.medium true
lp group manager permission set aurapunishments.limit.jail.long true
lp group manager permission set aurapunishments.limit.jail.extended true
```

### Owner (`owner`, level 100)

```
lp group owner permission set aurapunishments.level.100 true
lp group owner permission set aurapunishments.* true
lp group owner permission set aurapunishments.limit.* true
lp group owner permission set aurapunishments.immunity.bypass true
lp group owner permission set aurapunishments.legacy.bypass true
```

### Optional per-person overrides

```
# a second judge (separate from the Owner)
lp user <name> permission set aurapunishments.judicial.verdict true
lp user <name> permission set aurapunishments.judicial.sentence true
lp user <name> permission set aurapunishments.judicial.release true

# protect someone from all disciplinary action (e.g. a trusted non-staff builder)
lp user <name> permission set aurapunishments.immune true

# let one specific staff member use JailReloaded's own /unjail on case prisoners
lp group <group> permission set aurapunishments.custody.override true
```


## 3b. New in 1.1.0 - owner review, export, CoreProtect, appeal tickets

New nodes (all default-deny). **Nothing is granted automatically on upgrade.** The server owner keeps full authority through
`aurapunishments.*`, which now includes every node below; give any of them to other ranks (or to one person) with LuckPerms.

| Node | Allows |
|---|---|
| `aurapunishments.review.view` | See the owner-review queue and review records. (Issuers can always see *their own* reviews, with no node.) |
| `aurapunishments.review.review` | Start a review, add private notes, request an explanation, uphold, mark *Further investigation*, refer to a judicial case. |
| `aurapunishments.review.modify` | Modify a reviewed punishment (shorten it, change the reason). Lengthening additionally needs the normal issue authority for that length. |
| `aurapunishments.review.reverse` | Reverse a reviewed punishment. |
| `aurapunishments.review.deadlines` | Change a review's evidence / review deadlines. |
| `aurapunishments.review.evidence.sensitive` | See private reviewer notes. |
| `aurapunishments.export` | Export a case summary (Markdown / text). Content still follows the exporter's own evidence / note permissions. |
| `aurapunishments.case.evidence.coreprotect` | Run read-only CoreProtect lookups for a case (attaching a snapshot also needs `case.evidence.add`). |
| `aurapunishments.case.appeal.ticket` | Link a Discord Ticket Tool ticket to an appeal. |
| `aurapunishments.notify.warnings` / `.quick-punishments` / `.reviews` / `.admin` | In-game alerts for warnings, quick punishments, review activity, and configuration / integration / security notices. |

Rules that hold whatever you grant: nobody can take, decide or refer a review of a punishment **they issued themselves** (console excepted);
routine **warnings need only `aurapunishments.punish.warn`** (plus `punish` / `punish.others`) and never create a review;
every reviewer action is re-checked when it runs.

Suggested assignment (adjust to taste; add to the matching rank, inheritance carries it upward):

```
# Trainee / Helper / Jr Mod: nothing new (they can see reviews of their own punishments automatically)
# Mod: may export summaries of cases they can already see
lp group mod permission set aurapunishments.export true
# Sr Mod: look at the owner-review queue read-only, use CoreProtect evidence, link appeal tickets
lp group srmod permission set aurapunishments.review.view true
lp group srmod permission set aurapunishments.case.evidence.coreprotect true
lp group srmod permission set aurapunishments.case.appeal.ticket true
lp group srmod permission set aurapunishments.notify.warnings true
# Jr Admin: review and uphold, ask for explanations, refer
lp group jradmin permission set aurapunishments.review.review true
lp group jradmin permission set aurapunishments.notify.reviews true
# Admin: modify reviewed punishments, set deadlines, see private review notes
lp group admin permission set aurapunishments.review.modify true
lp group admin permission set aurapunishments.review.deadlines true
lp group admin permission set aurapunishments.review.evidence.sensitive true
lp group admin permission set aurapunishments.notify.quick-punishments true
# Sr Admin: reverse reviewed punishments, receive admin/security notices
lp group sradmin permission set aurapunishments.review.reverse true
lp group sradmin permission set aurapunishments.notify.admin true
# Manager / Owner: everything (owner already holds aurapunishments.*)
lp group owner permission set aurapunishments.* true
```

To delegate only the review queue to one trusted person: `lp user <name> permission set aurapunishments.review.view true` and `...review.review true`.
No rank names are hard-coded anywhere in the plugin.

## 3c. New and changed in 1.3.0

Everything is still default-deny. One new node, two new punishment nodes, and a few nodes whose meaning widened because evidence, notes and appeals are no longer tied to cases.

| Node | Status | Meaning |
|---|---|---|
| `aurapunishments.appeal.decide` | **new** | Take and decide appeals against punishments (uphold / reduce / overturn). **Never granted automatically and not included in `aurapunishments.*`** - grant it deliberately. With the judicial system **on**, `judicial.overturn` is accepted as well; with it **off**, only this node decides. Hierarchy applies. |
| `aurapunishments.punish.jail` | **new** (was used by the plugin but undeclared) | Issue a jail punishment (judicial system off). Also needs a `limit.jail.<tier>` for the duration. |
| `aurapunishments.punish.jail.permanent` | **new** | Jail "until released" (no end time). |
| `aurapunishments.case.evidence.add` / `.view` / `.void` | **widened** | now also cover evidence attached directly to a punishment (the issuer of a punishment may always add and see evidence on it) |
| `aurapunishments.case.note.add` / `.view` / `.void`, `sensitive.notes` | **widened** | now also cover staff notes attached to a punishment |
| `aurapunishments.case.appeal.file` | widened | record an appeal against a **punishment** (and, as before, against a sentenced case) |
| `aurapunishments.case.appeal.ticket` | widened | link a Ticket Tool ticket to an appeal on a punishment or a case |
| `aurapunishments.judicial.overturn` | unchanged | still decides case appeals; also decides punishment appeals while the judicial system is on |
| `aurapunishments.custody.release`, `judicial.release` | widened | also release a prisoner serving a **jail punishment** (`/ap unjail`) |
| `aurapunishments.custody.override` | widened | also bypasses the guard on JailReloaded commands for jail-punishment prisoners |
| `aurapunishments.emergency.review` | widened | also reviews case-free emergency actions |
| `aurapunishments.direct`, `.direct.<type>`, `custody.initiate`, `case.create`, `judicial.*` | unchanged | their features are unavailable (refused with a clear message) while the judicial system is off |

**Deprecated:** none. No node was removed or renamed.

```
# Appeal reviewers (not part of aurapunishments.*):
lp group admin permission set aurapunishments.appeal.decide true
# Jail as a punishment, 1 h to 1 d, for moderators:
lp group mod permission set aurapunishments.punish.jail true
lp group mod permission set aurapunishments.limit.jail.long true
lp group sradmin permission set aurapunishments.punish.jail.permanent true
```

## 4. Stop staff using EssentialsX punishment commands

AuraPunishments redirects `/ban /tempban /banip /unban /pardon /kick /mute /tempmute /unmute /warn` and the
`essentials:`, `minecraft:` and `bukkit:` forms (including every EssentialsX alias, resolved through the server's
command map) to `/punish` for players who lack `aurapunishments.legacy.bypass` - **this works without any
LuckPerms change** (console is never blocked). Still, remove the underlying permissions so the commands also disappear
from tab-completion and cannot be reached some other way:

```
lp group default permission set essentials.ban false
lp group default permission set essentials.banip false
lp group default permission set essentials.tempban false
lp group default permission set essentials.unban false
lp group default permission set essentials.unbanip false
lp group default permission set essentials.kick false
lp group default permission set essentials.kickall false
lp group default permission set essentials.mute false
lp group default permission set essentials.togglejail false
lp group default permission set essentials.setjail false
# and make sure no staff group holds essentials.* or essentials.ban.* / essentials.mute.*
lp group <staffgroup> permission unset essentials.*
```

Keep `essentials.mute.exempt`-style exemptions out of it: AuraPunishments' own protection is the level / immunity
system above. Existing EssentialsX mutes and bans are **not** imported (see Known Limitations) and keep being enforced by
EssentialsX itself until they expire.

## 5. Notification nodes

| Node | Receives |
|---|---|
| `aurapunishments.notify.case-created` | New cases, trial scheduling |
| `aurapunishments.notify.custody-started` | Pretrial custody started/released |
| `aurapunishments.notify.custody-alerts` | Review-deadline and overdue alerts |
| `aurapunishments.notify.verdicts` | Verdicts, sentences, appeals, reversals |
| `aurapunishments.notify.direct-punishments` | Direct punishments |
| `aurapunishments.notify.emergency` | Emergency actions (holders of `emergency.review` also receive them) |
| `aurapunishments.notify.warnings` | Warnings issued (1.1.0) |
| `aurapunishments.notify.quick-punishments` | Quick mutes/bans issued (1.1.0) |
| `aurapunishments.notify.reviews` | Owner-review activity (1.1.0) |
| `aurapunishments.notify.admin` | Configuration, integration and security notices (1.1.0) |

## 6. Appendix: every node

Generated from `plugin.yml`. Parent wildcards: `aurapunishments.*`, `aurapunishments.case.*`, `aurapunishments.custody.*`, `aurapunishments.punish.*`, `aurapunishments.direct.*`, `aurapunishments.emergency.*`, `aurapunishments.judicial.*`, `aurapunishments.sensitive.*`, `aurapunishments.review.*` (1.1.0), `aurapunishments.notify.*`.

| Node | Purpose |
|---|---|
| `aurapunishments.command` | Use /ap (help and the subcommands you separately hold permission for). |
| `aurapunishments.punish` | Open the punishment GUI (/punish). Does NOT grant authority to punish. |
| `aurapunishments.punish.others` | Access another player's disciplinary management (required to target anyone). |
| `aurapunishments.dashboard` | Open the staff dashboard (/punishments). |
| `aurapunishments.history.view` | View punishment and case history. |
| `aurapunishments.report` | Submit incident reports (limited case creation; cannot start custody). |
| `aurapunishments.case.view.own` | See only cases you prosecute or judge. |
| `aurapunishments.case.view.all` | See every case. |
| `aurapunishments.case.create` | Open full disciplinary cases. |
| `aurapunishments.case.manage` | Edit case details, change investigation status, reassign prosecutor. |
| `aurapunishments.case.close` | Administratively close cases. |
| `aurapunishments.case.evidence.add` | Submit evidence. |
| `aurapunishments.case.evidence.view` | View evidence. |
| `aurapunishments.case.evidence.void` | Void or correct evidence (never deleted). |
| `aurapunishments.case.note.add` | Add staff notes. |
| `aurapunishments.case.note.view` | View staff notes. |
| `aurapunishments.case.note.void` | Void staff notes. |
| `aurapunishments.case.appeal.file` | Record an appeal against a sentence or direct punishment. |
| `aurapunishments.custody.view` | View custody status and the custody list. |
| `aurapunishments.custody.initiate` | Start pretrial detention (JailReloaded imprisons the player). |
| `aurapunishments.custody.release` | Release a prisoner held for a case (with a recorded reason). |
| `aurapunishments.custody.override` | Use JailReloaded commands against prisoners held for an open case. |
| `aurapunishments.punish.warn` | Issue warnings. |
| `aurapunishments.punish.kick` | Kick players. |
| `aurapunishments.punish.mute` | Issue timed mutes (needs a duration tier limit node). |
| `aurapunishments.punish.mute.permanent` | Issue permanent mutes. |
| `aurapunishments.punish.ban` | Issue timed bans (needs a duration tier limit node). |
| `aurapunishments.punish.ban.permanent` | Issue permanent bans. |
| `aurapunishments.punish.ipban` | Issue timed IP bans (needs a duration tier limit node). |
| `aurapunishments.punish.ipban.permanent` | Issue permanent IP bans. |
| `aurapunishments.punish.revoke` | Revoke punishments you issued through the quick pathway. |
| `aurapunishments.punish.revoke.any` | Revoke any punishment, including judicial and direct ones. |
| `aurapunishments.limit.mute.short` | Mute up to the 'short' tier (default 1h). |
| `aurapunishments.limit.mute.medium` | Mute up to the 'medium' tier (default 1d). |
| `aurapunishments.limit.mute.long` | Mute up to the 'long' tier (default 7d). |
| `aurapunishments.limit.mute.extended` | Mute up to the 'extended' tier (default 30d). |
| `aurapunishments.limit.ban.short` | Ban up to the 'short' tier (default 1d). |
| `aurapunishments.limit.ban.medium` | Ban up to the 'medium' tier (default 7d). |
| `aurapunishments.limit.ban.long` | Ban up to the 'long' tier (default 30d). |
| `aurapunishments.limit.ban.extended` | Ban up to the 'extended' tier (default 180d). |
| `aurapunishments.limit.ipban.short` | IP ban up to the 'short' tier (default 1d). |
| `aurapunishments.limit.ipban.medium` | IP ban up to the 'medium' tier (default 7d). |
| `aurapunishments.limit.ipban.long` | IP ban up to the 'long' tier (default 30d). |
| `aurapunishments.limit.jail.short` | Jail sentence up to the 'short' tier (default 1h). |
| `aurapunishments.limit.jail.medium` | Jail sentence up to the 'medium' tier (default 6h). |
| `aurapunishments.limit.jail.long` | Jail sentence up to the 'long' tier (default 1d). |
| `aurapunishments.limit.jail.extended` | Jail sentence up to the 'extended' tier (default 7d). |
| `aurapunishments.direct` | Use the direct punishment pathway (also needs a per-type direct node). |
| `aurapunishments.direct.mute` | Direct mute. |
| `aurapunishments.direct.mute.permanent` | Direct permanent mute. |
| `aurapunishments.direct.ban` | Direct ban. |
| `aurapunishments.direct.ban.permanent` | Direct permanent ban. |
| `aurapunishments.direct.ipban` | Direct IP ban. |
| `aurapunishments.direct.ipban.permanent` | Direct permanent IP ban. |
| `aurapunishments.emergency.use` | Apply emergency protective actions. |
| `aurapunishments.emergency.review` | Review emergency actions (also receives emergency alerts). |
| `aurapunishments.judicial.view` | Open the judicial dashboard and judicial lists. |
| `aurapunishments.judicial.manage` | Assign judges, schedule trials, manage trial status and extend custody. |
| `aurapunishments.judicial.verdict` | Enter verdicts (also required to be assigned as judge). |
| `aurapunishments.judicial.verdict.any` | Enter a verdict on a case assigned to a different judge. |
| `aurapunishments.judicial.sentence` | Impose sentences. |
| `aurapunishments.judicial.release` | Release prisoners as part of judicial decisions. |
| `aurapunishments.judicial.overturn` | Overturn findings and decide appeals. |
| `aurapunishments.sensitive.ip` | See IP addresses. |
| `aurapunishments.sensitive.notes` | Read and write sensitive staff notes. |
| `aurapunishments.audit.view` | View the audit log. |
| `aurapunishments.admin` | Diagnostics, audit verification and administrative tools. |
| `aurapunishments.admin.reload` | Reload the configuration. |
| `aurapunishments.immune` | Cannot be targeted by disciplinary actions (except by immunity bypass). |
| `aurapunishments.immunity.bypass` | Ignore hierarchy, self-targeting and immunity protections. Owner-level. |
| `aurapunishments.legacy.bypass` | Use vanilla/EssentialsX punishment commands (/ban, /mute ...) that are normally redirected. |
| `aurapunishments.mute.bypass` | Not blocked from chat commands while muted. Intended for nobody. |
| `aurapunishments.notify.case-created` | Alerts when cases are opened and trials scheduled. |
| `aurapunishments.notify.custody-started` | Alerts for pretrial custody. |
| `aurapunishments.notify.custody-alerts` | Custody deadline alerts. |
| `aurapunishments.notify.verdicts` | Verdict, sentence, appeal and reversal alerts. |
| `aurapunishments.notify.direct-punishments` | Direct punishment alerts. |
| `aurapunishments.notify.emergency` | Emergency action alerts. |
| `aurapunishments.level.<N>` | Authority level N (any integer); highest held wins. Not a wildcard - grant the exact number. |
| `aurapunishments.limit.<type>.<tier>` | Duration tier from `config.yml`; `type` is `mute`, `ban`, `ipban` or `jail`. LuckPerms wildcards such as `aurapunishments.limit.*` work. |

> Notes: `aurapunishments.mute.bypass` exists only so a deliberate exception can be made for the command restrictions
> that apply to muted players; no recommended group holds it.
| `aurapunishments.review.view` | (1.1.0) See the owner-review queue. |
| `aurapunishments.review.review` | (1.1.0) Start/annotate/decide reviews (uphold, further investigation, refer, request explanation). |
| `aurapunishments.review.modify` | (1.1.0) Modify a reviewed punishment. |
| `aurapunishments.review.reverse` | (1.1.0) Reverse a reviewed punishment. |
| `aurapunishments.review.deadlines` | (1.1.0) Change review deadlines. |
| `aurapunishments.review.evidence.sensitive` | (1.1.0) See private review notes. |
| `aurapunishments.export` | (1.1.0) Export case summaries. |
| `aurapunishments.case.evidence.coreprotect` | (1.1.0) Read-only CoreProtect lookups. |
| `aurapunishments.case.appeal.ticket` | (1.1.0) Link a Ticket Tool ticket to an appeal. |
