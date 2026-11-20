# AuraPunishments 1.0.0 - Permission Matrix

Everything is **default-deny**, including for server operators. Nothing in this plugin trusts a rank *name*;
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

## 6. Appendix: every node

Generated from `plugin.yml`. Parent wildcards: `aurapunishments.*`, `aurapunishments.case.*`, `aurapunishments.custody.*`, `aurapunishments.punish.*`, `aurapunishments.direct.*`, `aurapunishments.emergency.*`, `aurapunishments.judicial.*`, `aurapunishments.sensitive.*`, `aurapunishments.notify.*`.

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
