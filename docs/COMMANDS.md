# AuraPunishments 1.3.0 - Commands

**1.3.1:** with the case workflow on (`judicial.enabled: false`, `casework.enabled: true`) the "judicial off" column applies, plus: `/ap case`, `/ap cases` and the case screens are available and **create case / punish from the case** replaces nothing - the quick commands (`/ap warn|kick|mute|ban|ipban|jail`) keep working unless `casework.require-case-for` lists the type. There are no new commands; `/ap judicial` shows the CASEWORK state. See [WORKFLOW_CASEWORK.md](WORKFLOW_CASEWORK.md).

Every `/ap` subcommand re-checks the same permissions, hierarchy, limits and evidence rules as the GUI; the server console is trusted. Tab completion and `/ap help` list only what you may use. **New** = added in 1.3.0, **changed** = behaviour depends on `judicial.enabled`.

| Command | Permission (any of) | Judicial on | Judicial off | Notes |
|---|---|---|---|---|
| `/punish <player>` | `aurapunishments.punish` + `punish.others` | full screen | judicial buttons hidden; jail + appeals added | **changed** |
| `/punishments` | `aurapunishments.dashboard` | dashboard | dashboard (judicial tiles replaced by appeals; "Case history" read-only; wind-down banner) | **changed** |
| `/ap help` | `aurapunishments.command` | | | lists only what you may use |
| `/ap case <id>` / `/ap cases [all\|open\|trial\|verdict\|sentence\|appeals\|mine]` | `case.view.all` / `case.view.own` | cases | **historical records stay readable** | |
| `/ap history <player>` | `history.view` | | | punishments tab now opens the punishment screen |
| `/ap punishment <id>` | `history.view` | | | **new**: evidence, notes, appeals, review, case, revoke/release, emergency review |
| `/ap judge` | `judicial.view` | judicial dashboard | hidden while DISABLED; available during WIND_DOWN | **changed** |
| `/ap judicial [status\|recount]` | `aurapunishments.admin` | state | state + open work | **new** |
| `/ap warn <player> <reason>` | `punish.warn` | | | `-e <evidence>` supported on all punishment commands |
| `/ap kick <player> <reason>` | `punish.kick` | | | |
| `/ap mute\|ban\|ipban <player> <duration\|permanent> <reason> [-e <evidence>]` | `punish.<type>` (+ limit tier / `.permanent`) | immediate; evidence rules | immediate and final; evidence/notes attach to the punishment | **changed** |
| `/ap jail <player> <duration\|permanent> <reason> [-e <evidence>]` | `punish.jail` (+ `limit.jail.<tier>` / `punish.jail.permanent`) | refused (jail is a sentence) | issues a jail punishment | **new** |
| `/ap unjail <player> <reason>` | `punish.revoke.any`, `custody.release`, `judicial.release`, or the issuer with `punish.revoke` | releases a jail-punishment prisoner | same | **new**; always available so a jail left from a judicial-off period can be released |
| `/ap unmute\|unban <player> <reason>` | `punish.revoke` / `punish.revoke.any` | | | |
| `/ap appeal <id> [-t <ticket>] <grounds>` | `case.appeal.file` | punishments not appealed through a case | any punishment in force or on record | **new** |
| `/ap appeals` | `appeal.decide`, `case.appeal.file`, `case.appeal.ticket`, `judicial.overturn` | open appeals against punishments | same | **new** |
| `/ap review [mine\|<id>]` | `review.view` / `punish` | | | |
| `/ap queue` | `judicial.view`, `review.view`, `emergency.review` | everything waiting | judicial entries omitted unless wind-down | **changed** |
| `/ap export <case> [md\|txt]` | `export` | | historical cases | |
| `/ap warnings <player>` | `history.view` | | | |
| `/ap discord [status\|retry]` | `admin` | | | |
| `/ap network` | `admin` | | | **1.4.0** (shared database only): registry, change feed, who sends Discord / runs housekeeping, the network jail |
| `/ap migrate [check\|run <file> [label] [convert-jails] confirm]` | `admin` | | | **1.4.0** (shared database only): import an old server's SQLite database ([NETWORK_DEPLOYMENT.md](NETWORK_DEPLOYMENT.md)) |
| `/ap admin`, `/ap status`, `/ap audit [verify]`, `/ap reload` | `admin` / `audit.view` / `admin.reload` | | | `/ap status` shows server name, schema, judicial state; a reload that flips `judicial.enabled` is audited |

Legacy `/ban`, `/mute`, `/kick`... (EssentialsX and others) are still redirected to `/punish` (`legacy-commands`). JailReloaded commands that would free or alter a prisoner held for a case **or serving a jail punishment** are refused unless the sender has `aurapunishments.custody.override`.
