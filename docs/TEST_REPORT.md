# AuraPunishments 1.4.0 - Test report

1.4.0 is the **network release**: one shared MariaDB/MySQL database, network-wide enforcement, the network jail, outage behaviour, import of old SQLite databases, Discord once per event. This section covers what 1.4.0 added and the full re-run on the 1.4.0 jar; the 1.3.2, 1.3.1 and 1.3.0 reports follow unchanged.

## 1.4.0 summary

| Category | Result |
|---|---|
| **A. Automated tests, standalone SQLite**, on **both** API versions (`gradle test`: Paper API 26.3 + MockBukkit v26.3; `gradle test121`: Paper API 1.21.11 + MockBukkit v1.21) | **457 tests, 0 failures on each** (375 from 1.3.2, all still asserting what they asserted, plus 82 new) |
| **B. The same 457 tests against a real MariaDB 10.11** (`gradle testMariadb`: every fixture creates its own throw-away database on the shared engine) | **457 run, 0 failures, 1 skipped** (a test that is SQLite-only by definition) |
| **C. Live network lab** (`tools/network-lab`): MariaDB 10.11.14, **Velocity 4.3.1** (modern forwarding), **five real Paper backends**: lobby, survival, jail on **26.3**; towny, skyblock on **1.21.11**; real TCP protocol clients of **both versions** connecting **through the proxy**; LuckPerms 5.5.71 with one shared MariaDB storage on lobby/survival/towny; JailReloaded 1.2.2 on the jail backend only | **111/111 checks pass** (one run, [evidence](evidence/live-network-lab-1.4.0-results.txt)) |
| **D. Live regression of everything from 1.3.x on a standalone server** (SQLite, real JailReloaded, LuckPerms, Towny, TownyChat, EssentialsX + Chat, CoreProtect, AuraCommandFilter; real protocol clients) | **Paper 26.3: 533/533** (phases 13/12/8/9/10/7/6/1/2/3 = 60/115/98/11/22/91/65/54/9/8, identical to 1.3.2). **Paper 1.21.11: @@R121@@** |
| **E. Real-data upgrade** (data folders written by 1.0.0 and 1.x booted with the 1.4.0 jar, compared row by row with the originals) | **26.3: @@UP26@@; 1.21.11: @@UP121@@** |
| **Build** | one shaded jar (1.84 MB): MariaDB driver and HikariCP relocated under `dev.aura.punishments.libs`, class-file version 65 (Java 21), `api-version: 1.21.11`, verified outside the test framework against MariaDB |

### What the live network lab exercised (all passing)

| Area | Checks |
|---|---|
| Boot | all five backends and the proxy start; every backend reports the shared MariaDB, schema v4, no AuraPunishments warning at start; the registry lists all five and flags the jail backend |
| **Bans across servers and versions** | a ban issued on skyblock (1.21.11) disconnects the player on survival (26.3) in **2.0 s**; the database decides at login on every backend; an unban on towny lifts it everywhere; a ban from the lobby (26.3) disconnects a 1.21.11 client |
| Mutes | a mute from the jail backend cancels the player's chat on survival; an unmute from towny lifts it |
| Warnings | a warning for an offline player reaches them at login, once |
| **Network jail** | ordered on the lobby for a player on survival: the origin is recorded, the player is moved to the jail backend through the proxy, JailReloaded holds them, the record turns HELD; `/ap unjail` from towny frees them and sends them back to survival; a player jailed while offline lands in jail at their next login on any backend |
| **Jail backend frozen** (SIGSTOP) | the jail is still accepted (heartbeat fresh); the player cannot be moved so stays **held** on survival: chat cancelled, commands refused with the reason; when the backend answers again the player is carried off and jailed |
| Concurrency | three backends ban the same player at the same instant: **exactly one** active ban; 120 warnings from two backends stored in 0.6 s; the shared audit hash chain verifies after both |
| Forwarded addresses | the backend records the player's own address from the proxy's forwarding; an IP ban on a loopback/private address is refused and nothing is recorded |
| Rapid switching | six server hops in 6.5 s; the player is on exactly one backend afterwards; a later ban still reaches them |
| Restart | skyblock stopped: the registry shows it stopped; a ban issued meanwhile is enforced the moment it is back |
| **Hierarchy across backends** (LuckPerms, shared storage) | a moderator cannot ban an **offline** administrator from the lobby (26.3) nor from towny (1.21.11); can ban an ordinary player, who is removed from another backend |
| **Database outage** (MariaDB shut down, later restarted) | a ban the server already knew is still enforced at login; players can still connect (ENFORCE_KNOWN); a staff action is **refused** and nothing is recorded; after the restart every backend is back in the registry and staff actions work **without restarting anything** |
| **Import of a real 1.0.0 database** | a prisoner still held on the old server **blocks** the import and nothing is written; after the operator releases the prisoner: dry run, then the real import (10 punishments, 14 cases, 77 audit entries), every imported punishment still points at its case, the audit chain verifies, repeating adds nothing, the file in the migration folder is untouched |

### Defects found by the 1.4.0 tests and fixed in 1.4.0 (none was left documented)

| Found by | Defect | Fix |
|---|---|---|
| MariaDB test run | `sensitive` is a reserved word on MariaDB (notes table) | quoted in DDL and in every statement |
| MariaDB test run | an `UPDATE` that read a column it also changed saw the new value (MariaDB evaluates assignments left to right, SQLite does not): the original expiry was lost | assignment order fixed |
| Network tests | two backends could both pass the "no active ban yet" check | per-player row lock taken before the check |
| Network tests | audit entries written by two backends at once could chain to the same predecessor | one lock row serialises appends |
| Network jail tests | a time change racing a placement could be applied twice or not at all | row lock; the placement re-reads the end after locking |
| Live lab (outage) | **logins stalled for 30 s while the database was away** (the proxy gave up on the backend) | a login waits at most 2.5 s and skips the database while it is known to be away |
| Live lab (outage) | heartbeats queued up behind the dead database | at most one in flight; the pool refuses quickly and probes the server once a second |
| Live lab | a network backend without JailReloaded logged "pretrial custody UNAVAILABLE" | informative message instead |
| Unit test | `Database.close()` threw when called twice | idempotent |

### Not tested (said plainly)

MySQL 8 as the server (only MariaDB was available; the SQL is the same and the unit suite is engine-parameterised); BungeeCord (Velocity only); CrossChat and other cross-server chat plugins (not available); real Discord delivery (a local HTTP stand-in; no route to discord.com from the build environment); official-client rendering of dialogs; mixed-version *players* moving between 1.21.11 and 26.3 backends (needs ViaVersion on a real network; the lab keeps each client on backends of its own version, the data layer is exercised across versions); long soak / load tests beyond the 120-warning burst.

---

# AuraPunishments 1.3.2 - Test report

1.3.2 adds the **punishment choice inside Create Case**, makes **owner review off by default** (and never applied to punishments from a case), and makes **one jar run on Paper 1.21.11 and Paper 26.3**. The 1.3.1 and 1.3.0 reports follow below unchanged; this section covers what 1.3.2 changed and the full re-run on the 1.3.2 jar, on both servers.

## 1.3.2 summary

| Category | Result |
|---|---|
| **A/B. Automated tests**, run on **both** API versions (`gradle test`: Paper API 26.3 + MockBukkit v26.3; `gradle test121`: Paper API 1.21.11 + MockBukkit v1.21) | **375 tests, 0 failures on each** (366 from 1.3.1, still asserting what they asserted, plus 9 new/changed: `CaseworkTest` 31, `CaseworkGuiTest` 8, `UpgradeV13Test` 6) |
| **C1. Live, real Paper 26.3** (real JailReloaded 1.2.2, LuckPerms, Towny, TownyChat, EssentialsX + Chat, CoreProtect, AuraCommandFilter, DiscordSRV not connected; real TCP protocol clients) | **533/533 checks pass** in 10 phases: new phase 13 (punishment inside Create Case) **60/60**; phase 12 (case workflow) 115/115; 8/9/10 = 98/11/22; 7/6/1/2/3 = 91/65/54/9/8 |
| **C2. Live, real Paper 1.21.11** (build 132; same plugins except AuraCommandFilter, see below) | **519/520**: phase 13 60/60; 12 = 115/115; 8/9/10 = 98/11/22; 7/6/1/2/3 = 91/51 of 52/54/9/8. **The one miss is "AuraCommandFilter present": your AuraCommandFilter plugin declares `api-version: 26.3` and Paper 1.21.11 refuses to load it. It is not part of AuraPunishments and nothing in AuraPunishments depends on it.** |
| **D. Real-data upgrade** (data folders written by 1.0.0 and 1.x, booted with the 1.3.2 jar, compared row by row with the originals) | **26/26 runtime checks on each server**; 204 and 492 original rows across 12 and 16 tables: **all identical**, on 26.3 and on 1.21.11 |
| **E. Not tested** | official game client rendering of dialogs; real Discord delivery; Velocity / multi-server (not part of this release); load/soak; other 1.21.x versions (only 1.21.11 and 26.3 were run); live phases 4 and 5 (incompatible JailReloaded jar, version drift - unchanged code path) |

The jar that was tested on both servers is the jar that is delivered: SHA-256 `97bd8646f21f450d76fb14b54d79125e662a4e9b3f4d6836eec37d43a70668f0`. Its classes are Java 21 bytecode (class-file major version 65), compiled against the 1.21.11 API, `api-version: 1.21.11`. The separate live-test **drivers** (never shipped; `live-test` for 26.3, `live-test-121` for 1.21.11 - the latter differs only in the two classes that touch server internals, which Mojang renamed between the versions) were adjusted during the run, never the plugin (see "How the live run went").

### What the new tests cover

* `CaseworkTest` (31): everything from 1.3.1 plus - **a punishment from a case never creates an owner review, even with review on** (while a quick punishment without a case still does); **a case opened together with its punishment in one atomic step** (case OPEN, punishment linked, evidence and timeline, no review); the case's evidence satisfies a ban's evidence requirement; **a refused punishment leaves no case behind** (limit, offense disallows the type, an active ban already exists, JailReloaded missing) so the draft can be corrected and resubmitted; a report-only account cannot punish; the same player is required; judicial-on refuses it; **strikes for the recommendation** count case punishments that were not revoked and not kicks.
* `CaseworkGuiTest` (8): the **Punishment button** under the steps on the New case screen; with no offense yet nothing opens; the usual list of punishments (a type the offense does not allow, here an IP ban for harassment, is greyed out); the usual punishment screen opened for the new case; "Use this punishment" returns to the draft **with nothing saved or issued yet**; Submit asks one confirmation and opens the case **and** issues the punishment (linked, case open, enforced, no review); Shift+Click removes the chosen punishment and the case is then opened alone.
* `UpgradeV13Test`: unchanged (a 1.3.0 config keeps working and gets the reference file).

### What live phase 13 proves, on a real server of each version (60 checks each)

A fresh install as shipped is in the case workflow with **owner review off**; the staff dashboard has **no** owner-review buttons and `/ap help` does not list `/ap review`. Through the real wizard with real dialog windows: Create Case -> offense, description, evidence -> **6. Punishment** -> the usual list (IP Ban locked, Mute available) -> the usual Mute screen titled "for the new case" -> duration and reason -> "Use this punishment" -> **nothing exists yet** -> Submit -> one confirmation -> the new case opens; the case and the mute exist together, the mute ACTIVE, STANDARD (no court), linked, enforced (the muted player cannot chat and is told so), **no owner review row**, no custody and no verdict. The same for **Jail** (really held by the real JailReloaded, linked to the case and not pretrial custody; `/ap unjail` releases) and for a **Ban** (the case's evidence satisfied the ban's evidence rule; the online player is disconnected with the ban screen). Three cases, each open with exactly one linked punishment; audit chain intact; no pending operation left.

### How the live run went (nothing hidden)

* **No defect in the plugin was found by the 1.21.11 runs.** Every failure along the way was in the test setup: the new 1.21.11 test server was first left on default settings (wrong port, online mode on); the 1.21.11 driver needed its own `api-version` and a "Mojang mappings" manifest line; the driver now waits for the server's listener before a phase starts; and phase 13's first draft used player names that contained "Mute", "Jail" and "Ban", so button lookups hit the player's head, and logged one player in twice (which Minecraft answers by kicking the first connection). All fixed in the driver; the whole suite was then run on both servers on the unchanged plugin jar.
* The plugin jar also gained one manifest line (`paperweight-mappings-namespace: mojang`): it uses no server internals, so Paper 1.21.11 has nothing to remap, and without it Paper remaps the jar on every first load.
* A worker restart interrupted the first 1.21.11 run during phase 6; phases 13, 12, 8, 9, 10 and 7 had already finished (results kept) and phases 6, 1, 2 and 3 were re-run from a clean state.
* **AuraCommandFilter** (a separate plugin) cannot run on 1.21.11 while its `plugin.yml` says `api-version: 26.3`; AuraPunishments detects it only to say so in the console and does not need it.

### Known limitations of 1.3.2

* The Create Case punishment step always goes through the same checks as a quick punishment plus the offense's limits; an offense that does not allow the type you want must be changed in `offenses.yml` or the case punished differently.
* A punishment chosen on the New case screen applies the staff note only to the case (there is no separate punishment note in the case workflow).
* Only Paper 1.21.11 and 26.3 were run; other 1.21.x versions should work (the plugin uses the 1.21.11 API) but are not claimed.
* Everything listed under "Not tested" above, and every limitation in the 1.3.1 and 1.3.0 reports below, still applies.

---

# AuraPunishments 1.3.1 - Test report

1.3.1 adds the **case workflow** (open a case, punish from it, no court) on top of 1.3.0. The 1.3.0 report follows below unchanged; this section covers what 1.3.1 changed and the full re-run on the 1.3.1 jar.

## 1.3.1 summary

| Category | Result |
|---|---|
| **A/B. Automated tests** (real services over a real SQLite database, fake JailReloaded, MockBukkit GUI/command/lifecycle) | **366 tests, 0 failures** (335 from 1.3.0, all still asserting what they asserted: only the fixtures that mean "judicial off" now also say `casework.enabled: false`, which is what 1.3.0 meant; plus 31 new: `CaseworkTest` 24, `CaseworkGuiTest` 6, one upgrade test) |
| **C. Live server, real protocol clients** (Paper 26.3, real JailReloaded 1.2.2, LuckPerms, Towny, TownyChat, EssentialsX, CoreProtect, AuraCommandFilter, DiscordSRV not connected) | **473/473 checks pass** in 9 live phases: **new phase 12 (case workflow) 115/115**; 1.3.0 behaviour unchanged with the workflow off: phases 8/9/10 = 98/11/22; regression phases 7/6/1/2/3 = 91/65/54/9/8 |
| **D. Real-data upgrade** (data folders written by 1.0.0 and 1.x, booted with the 1.3.1 jar, compared row-by-row with the originals) | **26/26 runtime checks**, 204 and 492 original rows across 12 and 16 tables: **all identical** |
| **E. Not tested** | official game client rendering of dialogs; real Discord delivery; Velocity / multi-server (not part of this release); live phases 4 and 5 (incompatible JailReloaded jar, version drift - unchanged code path); load/soak testing |

The plugin jar tested live is the jar that is delivered: SHA-256 `13cdffe27c56094d60b775b00613c1384ff27111be7d0883c1584c84f5c18284`. The live-test **driver** (a separate jar, not shipped) was changed twice during the run, never the plugin: a peaceful, daytime test world and a version-aware file name in one upgrade check (see "How the live run went").

### What the new tests cover

* `v13.CaseworkTest` (24): the shipped file gives a fresh install the workflow; a missing section means off; the four states; plain-off mode still refuses cases exactly as 1.3.0; judicial-on is unchanged; every court step (custody, direct punishment, trial status, judge, trial date, hearing reference) refused and leaves the case untouched; investigate / close / reopen; no hearing notices; a punishment from a case is final, immediate, linked, recorded on the timeline, and the case stays open; evidence and notes supplied while punishing land on the case and are not duplicated; every type (warn, mute, jail with the fake JailReloaded, kick, IP ban); the offense's allowed types and maximum duration; target/case mismatch; closed case refused until reopened; evidence already on the case counts toward the requirement; permissions, hierarchy, duration tiers; who cannot see the case cannot punish from it; quick buttons without a case; `require-case-for`; owner review created and linked to the case; appeals against the punishment; closing a case never lifts what was issued; leftover pretrial custody reported and never released by a configuration change.
* `gui.CaseworkGuiTest` (6): the player screen (Create Case first, quick buttons kept, no court buttons), the wizard through to Punish -> Mute -> confirm with the punishment linked and the case still open, the case screen without court buttons (not even locked ones), the status chooser, the dashboard.
* `v13.UpgradeV13Test` (+1): a 1.3.0 config (no `casework` section) is left byte-for-byte untouched, the workflow stays off, and a reference file showing the new section is written.

### What live phase 12 proves on the real server (115 checks)

A fresh install as shipped is in the CASEWORK state; the real GUI offers Create Case first and hides every court button; a case is opened through the real wizard with real dialog windows; **Punish -> Ban** from the case disconnects the online player with the ban screen, refuses their next login, links the ban to the case, leaves the case open and records it on the timeline; **Punish -> Jail** puts a real player in the real JailReloaded jail (linked to the case, not pretrial custody) and `/ap unjail` releases them with the case untouched; every court tool is refused; quick `/ap warn` / `/ap mute` work without inventing a case; `casework.require-case-for` refuses a quick ban with an explanation and still allows a kick; an appeal is recorded against the punishment with its ticket and overturning it revokes the ban and lets the player back in; a case is closed, refused punishing until reopened, then punished from again; then the judicial system is switched ON, a real player is held in real pretrial detention, it is switched OFF with the workflow on: state CASEWORK, the detainee counted and **not released**, released by the normal tool, the leftover case closed, and the court screens disappear; the audit chain stays intact, every record carries its uid and origin, nothing is orphaned or left pending.

### How the live run went (nothing hidden)

* The first live pass of phase 12 found one real defect: a locked, greyed "Enter verdict" button was still drawn on case screens in the workflow. **Fixed** (the judicial row appears only for cases that carry court state from before) with a unit assertion added.
* A second full run then failed in 40+ places for a reason unrelated to the plugin: the shared test world was at night and zombies, skeletons and a creeper killed the protocol clients, which closed their menus. The driver now sets peaceful difficulty and daytime in every world at start, and a world-clock error in the nether is ignored. The whole suite was then re-run from the beginning on the unchanged plugin jar.
* The upgrade phase expected the reference file to be called `config-1.3.0-reference.yml`; the plugin correctly writes `config-<its version>-reference.yml`. The check now asks the running plugin for its version, and the upgrade phase was re-run (13/13 on both data sets, rows identical).
* One more product change came from review, not from a failing test: a server upgraded from 1.3.0 (which already has `judicial.enabled`) would never have been told that the new `casework` section exists. It now gets the one-time reference file and a console hint, covered by a new test.

### Known limitations of 1.3.1

* Case-level appeals do not exist in the workflow (there is no verdict to appeal): appeals are filed against the punishment.
* A punishment from a case has no "sentence" semantics: it does not count as a guilty finding for escalation recommendations (`priorFindings`), because no court found anything.
* The case list cards still label the case owner "Prosecutor"; the case screen and creation wizard say "Opened by" / "owner".
* Evidence/notes added later to a punishment whose case has since been closed attach to the punishment, not the case.
* Everything listed under "Not tested" above, and every limitation in the 1.3.0 report below, still applies.

---

# AuraPunishments 1.3.0 - Test report

Results are grouped by how they were obtained, because they do not all prove the same thing. Nothing below is claimed beyond what the category shows.

## 1. Summary

| Category | Result |
|---|---|
| **A. Automated unit / integration tests** (real services over a real SQLite database, fake JailReloaded, controllable clock) | **335 tests, 0 failures** (212 inherited from 1.2.1, all unmodified in what they assert; 123 new). All tests re-run from a clean test state (`gradlew cleanTest test`). |
| **B. MockBukkit GUI / command / plugin-lifecycle tests** (the real plugin class loaded in a mocked Paper server, real menus, real dialog flow, real command dispatch) | included in A (gui.* classes), see the table below |
| **C. Live server, real protocol clients** (Paper 26.3, real JailReloaded 1.2.2, LuckPerms, Towny, TownyChat, EssentialsX, CoreProtect, AuraCommandFilter, DiscordSRV not connected) | **358/358 checks pass** in 8 live phases (3 new + 5 regression) |
| **D. Real-data upgrade** (data folders written by 1.0.0 and 1.x on the live server, booted with 1.3.0, then compared row-by-row with the originals) | 2 real data folders (schema v1 and v2) upgraded in place: **26/26 runtime checks** and **696 original rows in 28 table comparisons, all identical** |
| **E. Not tested** | official game client rendering of dialogs; real Discord delivery; Velocity / multi-server (not part of 1.3.0); live phases 4 and 5 (incompatible JailReloaded jar, version drift - unchanged code path); load/soak testing |

The jar that was tested live is the jar that is delivered: SHA-256 `be77920ea0d9a6cecc534bf4250ff64b9040026e86a242044ab1cb5d06a5691a`.

## 2. A/B - automated tests by area

| Test class | Tests | Failed | |
|---|---|---|---|
| `CaseAndCustodyTest` | 31 | 0 |  |
| `ConfigAndModelTest` | 12 | 0 |  |
| `DefendantAndEventsTest` | 8 | 0 |  |
| `DiscordServiceTest` | 15 | 0 |  |
| `JudicialAndPunishmentTest` | 44 | 0 |  |
| `MigrationV2Test` | 3 | 0 |  |
| `QueueExportAppealTest` | 11 | 0 |  |
| `ReviewAndWarningTest` | 17 | 0 |  |
| `ReviewFixesRegressionTest` | 7 | 0 |  |
| `SmokeMockTest` | 1 | 0 |  |
| `UpgradeAndConfigTest` | 6 | 0 |  |
| `gui.BukkitLayerTest` | 30 | 0 |  |
| `gui.CommandsV13Test` | 5 | 0 | **new** |
| `gui.DialogInputTest` | 8 | 0 |  |
| `gui.GuiFlowTest` | 5 | 0 |  |
| `gui.MuteChatTest` | 5 | 0 |  |
| `gui.NonJudicialGuiTest` | 8 | 0 | **new** |
| `gui.ReviewGuiTest` | 6 | 0 |  |
| `gui.TypeNameConfirmTest` | 3 | 0 |  |
| `v13.DialectTest` | 4 | 0 | **new** |
| `v13.DiscordPunishmentLogTest` | 15 | 0 | **new** |
| `v13.JailModificationTest` | 6 | 0 | **new** |
| `v13.JudicialModeTest` | 12 | 0 | **new** |
| `v13.MigrationV3Test` | 9 | 0 | **new** |
| `v13.NonJudicialWorkflowTest` | 18 | 0 | **new** |
| `v13.PunishmentAppealTest` | 16 | 0 | **new** |
| `v13.StandaloneJailTest` | 25 | 0 | **new** |
| `v13.UpgradeV13Test` | 5 | 0 | **new** |
| **Total** | **335** | **0** | 123 new, 212 inherited |

What the new suites cover (the categories requested for this release):

| Requested category | Where |
|---|---|
| Fresh install / default config | `JudicialModeTest` (shipped file = off; missing key = on; explicit value wins), `NonJudicialGuiTest` (a real fresh plugin load), `UpgradeV13Test` |
| Upgrade from earlier versions | `UpgradeV13Test` (1.0.0 and 1.2.1 configs, a real upgraded plugin folder: config/messages/offenses byte-for-byte untouched, reference file, data loaded), `MigrationV3Test`, `MigrationV2Test` |
| Mode transitions | `JudicialModeTest`: on -> off with open cases and a detainee (nothing released/closed/deleted; new work refused; existing work finishable; DISABLED when empty; back ON restores; finished case not reopenable), `CommandsV13Test` (`/ap judicial`), `NonJudicialGuiTest` (dashboard banner) |
| Active cases / pretrial detention | `JudicialModeTest`, the 212 inherited judicial tests (unchanged) |
| Every punishment type, final and immediate, no case | `NonJudicialWorkflowTest` (warn, kick, mute, ban, IP ban, jail; offline players; hierarchy; rank limits; permanent nodes) |
| Standalone jail | `StandaloneJailTest` (25), `JailModificationTest` (6), `NonJudicialGuiTest`, `CommandsV13Test` |
| Evidence / notes without cases | `NonJudicialWorkflowTest`, `NonJudicialGuiTest` (at issuance and afterwards via history) |
| Appeals | `PunishmentAppealTest` (16: ticket validation, require-ticket, take/decide/uphold/reduce/overturn, permission in both modes, hierarchy, no suspension), `JailModificationTest`, `NonJudicialGuiTest` |
| Owner review rules / emergency | `NonJudicialWorkflowTest` (permanent-only types, review switched off, emergency without a case, both queues settle each other, unchanged with judicial on) |
| Discord notifications | `DiscordPunishmentLogTest` (15: type/event/field filters, own webhooks, judicial gate, public log sanitization, public never uses private webhooks, no duplicates, no new notice kinds on upgraded installs, no IPs) plus the inherited `DiscordServiceTest` (retries, rate limits, failures never block moderation) |
| Permissions / hierarchy | `PunishmentAppealTest`, `StandaloneJailTest`, `NonJudicialWorkflowTest`, inherited suites |
| GUI / dialogs | `NonJudicialGuiTest` (8), inherited `GuiFlowTest`, `BukkitLayerTest`, `DialogInputTest`, `TypeNameConfirmTest`, `ReviewGuiTest`, `MuteChatTest` |
| Data preservation | `MigrationV3Test` (9): counts, ids, voiding/correction links, appeal decisions and tickets, custody, audit chain still verifies, unique ids, triggers, re-open idempotent, services work on the migrated data |
| Storage abstraction | `DialectTest` (4) |
| Full regression | the 212 inherited tests run unchanged (the only fixture change: the test fixtures that exercise judicial behaviour switch `judicial.enabled` on, because the shipped file for a fresh install has it off) |

## 3. C - live server, real protocol clients

| Phase | What it proves | Result | Evidence file |
|---|---|---|---|
| 8 | Fresh install, judicial system OFF (as shipped): GUI visibility, mute/ban/jail/appeal/evidence/emergency/owner-review through real dialogs and clicks, online player removed at once and refused at login, real JailReloaded placement, guarded `/jail unjail`, `/ap unjail`, timer end -> served | **98/98** | `docs/evidence/live-results-1.3.0-phase-8-fresh-install-judicial-off.txt` |
| 9 | Same server after a **real restart**: every record, mute enforcement, offline-jailed player placed at login, reconcile, unjail | **11/11** | `docs/evidence/live-results-1.3.0-phase-9-after-restart.txt` |
| 10 | Judicial **ON -> OFF -> ON** with real open work (a case, a really-jailed online detainee): nothing released/closed by the switch, new work refused, work finished with normal tools, DISABLED when empty, ON restores | **22/22** | `docs/evidence/live-results-1.3.0-phase-10-judicial-switch-wind-down.txt` |
| 7 | Regression (judicial ON): every dialog workflow of 1.2.x - warnings, mute, ban + evidence, case wizard, judicial, review, dashboard, concurrency | **91/91** | `docs/evidence/live-results-1.3.0-regression-phase-7.txt` |
| 6 | Regression: online players, TownyChat mute, AuraCommandFilter, CoreProtect, real login enforcement | **65/65** | `docs/evidence/live-results-1.3.0-regression-phase-6.txt` |
| 1 | Regression: full functional run (cases, custody, sentencing, direct, emergency, reversals) | **54/54** | `docs/evidence/live-results-1.3.0-regression-phase-1.txt` |
| 2 | Regression: after restart (persistence) | **9/9** | `docs/evidence/live-results-1.3.0-regression-phase-2.txt` |
| 3 | Regression: JailReloaded absent (fails safely) | **8/8** | `docs/evidence/live-results-1.3.0-regression-phase-3.txt` |
| | **Total** | **358/358** | |

Environment: Paper 26.3 (build 256), Java 25, Towny 0.103.2, TownyChat 0.122, EssentialsX 2.22.0 + Chat, LuckPerms 5.5.71, **JailReloaded 1.2.2**, AuraCommandFilter 1.0.0, CoreProtect 25.1, DiscordSRV 1.30.4 (not connected to Discord), `online-mode=false`. Clients are protocol clients doing the real handshake/login/configuration and sending real command, container-click and dialog `custom_click_action` packets.

Notes on the live runs, stated plainly:
* Phase 8 of the **first** pass of the final jar scored 97/98: one check (the guarded `/jail unjail` message) failed because the vanilla server answered a protocol client's command with "Chat disabled in client options" - a test-driver artefact, not a plugin behaviour (the same check passed in the earlier run and in the rerun). The driver now resends a command once if that answer appears; Phase 8 was rerun in full on the same jar and scored **98/98** (the table shows that rerun; the first pass is kept as `live-results-1.3.0-phase-8-first-pass-97of98.txt`).
* Two further driver mistakes were found and fixed while running (user names longer than Minecraft's 16-character limit in phases 9 and 10; two wrong expectations in the upgrade phase - see section 4). None involved the plugin.
* Phases 4 and 5 (incompatible / outdated JailReloaded jar, EssentialsX-only) were not re-run: the code paths they cover were not touched.


## 4. D - real-data upgrade

| Data folder | Written by | Boot result with 1.3.0 | Row-by-row comparison with the original database |
|---|---|---|---|
| **1.0.0** (schema v1; real live data: 10 punishments, 14 cases, 7 custody records, 77 audit entries) | 1.0.0 | **13/13** | 204 original rows compared across 12 tables; ALL IDENTICAL (`docs/evidence/upgrade-v1-row-comparison.txt`) |
| **1.x** (schema v2; real live data: 42 punishments, 24 cases, 12 custody records, 12 reviews, 163 audit entries) | 1.x | **13/13** | 492 original rows compared across 16 tables; ALL IDENTICAL (`docs/evidence/upgrade-v2-row-comparison.txt`) |

JailReloaded was removed for these two boots so that start-up reconciliation of old custody records against an empty jail could not legitimately change rows; the comparison therefore proves the *migration* alone. Checked at runtime: schema v3, judicial system ON (the old `config.yml` has no switch), `config.yml` byte-identical (hash), reference file written, pre-migration backup present, unique ids and server name on every row, audit chain verifies, every punishment that was still running before the upgrade is enforced afterwards and nothing that had run out is, the open-work report matches the data, old cases and punishments open through the services.


## 5. Known limitations and what was not verified

* **No network behaviour.** 1.3.0 is the foundation. Several servers running 1.3.0 with separate databases do not see each other's punishments.
* **Real Discord** could not be reached from the build environment: delivery is verified against a local HTTP stand-in (headers, bodies, retries, 429 handling), the message content against the documented webhook JSON format. The public/private separation and sanitization are verified on the rendered payloads.
* **The official Minecraft client** was not used: the "clients" are protocol clients that perform the real handshake/login/configuration and send the real packets (commands, container clicks, dialog `custom_click_action`). What the dialog *looks like* was not verified.
* **JailReloaded** behaviour is JailReloaded's: jail time counts only while the prisoner is online (configurable in JailReloaded); an offline prisoner's release completes at next login. Our records say so (`PENDING_PLACEMENT`, `RELEASING`) and are reconciled.
* **LuckPerms / EssentialsX versions:** tested with LuckPerms 5.5.71 and EssentialsX 2.22.0 (your servers run 5.5.91 / 2.22.1-dev).
* **Soak / load:** none. SQLite behaviour under heavy concurrent writes was not stress-tested (all writes are serialised on one thread by design).
* **Wind-down** counts open cases of every kind; a case nobody intends to finish has to be closed or the switch kept on.
* Windows were not available: all runs were on Linux.

## 6. Reproduce

```
./gradlew test                       # category A/B
./gradlew jar :live-test:jar         # the plugin and the live driver
# live: Paper 26.3 with the plugins above; write plugins/APLiveTest/phase.txt = 8 (fresh install), 9 (after restart), 10 (judicial on -> off -> on),
# 11 (boot real older data; JailReloaded absent), 7 / 6 / 1 / 2 / 3 (regression, judicial on); start the server; the driver writes results-N.txt and stops it.
# The raw outputs are in docs/evidence/ (live-results-1.3.0-*).
```

---

# AuraPunishments 1.2.1 - Test report

**1.2.1:** 212 automated tests, 0 failures (twice, clean build); new `TypeNameConfirmTest` (setting off: Confirm applies at once, no dialog; setting on: name dialog, a wrong name applies nothing, the right name applies; shipped direct default off). Live, real protocol clients: phase 7 **91/91** (the ban workflow now runs with the setting off, then switched on through the config file and `/ap reload`, and off again), phases 6/1/2/3: 65/54/9/8 passed, 0 failed. Same limits as 1.2.0 below (protocol clients, not the official client; Discord not connected; phases 4/5 not re-run).

---

# AuraPunishments 1.2.0 - Test report (dialog-window text entry; no chat input)

## 1.2.0 summary
* **Design change:** text entry no longer uses chat at all (see CHANGELOG). The earlier 1.1.1 approach (capture chat first) is gone.
* **Automated:** 209 tests, 0 failures, run twice from a clean build. `DialogInputTest` (8) covers submit-once, validation re-open with the typed text, cancel, timeout (closes the dialog), replaced/stale dialogs ignored, independent sessions, disconnect and shutdown cleanup, and asserts the prompt manager has no chat handler; `MuteChatTest` (5) keeps mute enforcement on both chat events. Existing GUI/judicial/review tests were converted to answer through the dialog.
* **Live, real protocol clients over TCP** (real handshake/login/configuration; the client decodes the server's real `show_dialog` packet and answers with a genuine `custom_click_action` packet carrying the text-box value; slot clicks and commands are real packets too). Server: Paper 26.3 with Towny, TownyChat, EssentialsX + EssentialsX Chat, LuckPerms, JailReloaded, AuraCommandFilter, CoreProtect, DiscordSRV (not connected). **Phase 7: 83 / 83** on the final jar: warning (reason + too-short validation), cancel button, timeout (10 s live setting), running a command mid-dialog, mute (invalid then valid duration, reason), ban (duration, reason, evidence type + reference, typed-name confirmation), case wizard (description, notes), judicial (note, assign judge by name, trial time, hearing reference, verdict reasoning, sentence duration + reasoning), owner review (private note, explanation to the issuer's client, decision note), dashboard player search, two staff at once in opposite order, reload mid-dialog, permission revoked mid-dialog, disconnect mid-dialog. A line typed in chat while a dialog is open is ordinary public chat and the dialog stays. Privacy audit: no answer appeared on any other client, in any chat relay probe, or in CoreProtect's chat log, and the console shows only the legitimate ban-screen text.
* **Regression on the final jar (fresh data):** phase 1 54/54, phase 2 9/9, phase 3 8/8, phase 6 65/65 (see the results listed in `docs/evidence/`). Phases 4 and 5 were not re-run.
* **Limits:** clients are protocol clients, not the official game client (the dialog is not rendered; what is verified is the exact packets the server sends and accepts); `online-mode=false`; DiscordSRV not connected to Discord. Dialogs are a vanilla client feature; any client able to join a Paper 26.3 server has it.

---

# 1.1.1 test report (history; superseded by the 1.2.0 design)

# AuraPunishments 1.1.1 - Test report (chat-input hotfix)

## 1.1.1 summary
* **Automated:** 238 tests, 0 failures, run twice from a clean build. New: `ChatInputTest` (11 tests + a 30x repeated race test). 7 of its 11 tests **fail against the 1.1.0 implementation** (verified by swapping the old classes back), so they are genuine regression tests.
* **Live, real protocol clients (the required proof):** Paper 26.3 with **Towny 0.103.2, TownyChat 0.122, EssentialsX 2.22.0 and EssentialsXChat 2.22.0, LuckPerms 5.5.71, JailReloaded 1.2.2, AuraCommandFilter 1.0.0, CoreProtect 25.1, DiscordSRV 1.30.4** (DiscordSRV loaded but not connected: no bot token / no network). The test clients are real Minecraft protocol clients: they open a TCP connection, do the real handshake, login and configuration phases, then send genuine serverbound chat, command and container-click packets that the server decodes from the wire. They use the server's own protocol classes, so nothing is rendered; every packet they receive is recorded. Permissions come from LuckPerms (real login events), staff are in a real Towny town with TownyChat's TOWN channel active.
* **Phase 7 on 1.1.1: 82 / 82** (`docs/evidence/live-results-1.1.1-chat-input-phase-7-after-fix.txt`). The identical run on the 1.1.0 jar: 81 / 82, the failure being that **every one of the 16 prompt answers reached a legacy-event relay/logger at `MONITOR`** (`...-BEFORE-fix-on-1.1.0.txt`); on the first reproduction (CoreProtect at its default) the answer was also written to CoreProtect's chat log.
* **Regression on 1.1.1:** phase 1 (54/54), phase 2 after restart (9/9), phase 3 JailReloaded missing (8/8), phase 6 online players / TownyChat mute / AuraCommandFilter / CoreProtect / real login enforcement (65/65), each on fresh data. (Phases 4 and 5 were not re-run.)

### What phase 7 drives (every prompt call site in the plugin that matters, through the real GUI with real clicks and real chat packets)
| Workflow | Prompt(s) exercised | Result |
|---|---|---|
| Warning | reason; too-short answer refused and prompt kept; delivery to the target's own client (title + reason) | PASS |
| Cancel word / timeout (10 s live setting) / running a command | all three end the prompt, tell the player, return to the screen; ordinary chat afterwards is public chat | PASS |
| Mute | custom duration (invalid then valid), reason; 2 h duration stored; owner review created | PASS |
| Ban | custom duration, reason, evidence type + evidence reference, typed-name confirmation; ban stored with evidence; target disconnected | PASS |
| Case wizard | incident description, contextual notes (shown back in the wizard) | PASS |
| Judicial | staff note, assign judge by name, schedule trial, hearing reference, verdict reasoning, sentence duration + reasoning, sentence recorded | PASS |
| Owner review | private note, request explanation (issuer's own client receives it), decision note | PASS |
| Dashboard | "find a player" name prompt | PASS |
| Concurrency & lifecycle | two staff in prompts at once answered in opposite order (no cross-over); a configuration reload mid-prompt; permission revoked after typing (GUI closed by the permission-change hook; service also refuses); disconnect mid-prompt cleans the session and the reconnected player chats normally | PASS |
| Privacy audit over all answers | none seen by the bystander client, none by a `MONITOR` legacy/modern relay probe, none in CoreProtect's chat log (with `log-cancelled-chat: false`), only the legitimate ban-screen line appears in the console | PASS |

### Limits of this evidence
* DiscordSRV was not connected, so real Discord forwarding was *not* observed; the `MONITOR` probe stands in for relays/loggers.
* The clients are protocol clients, not the official Minecraft client (no signed-chat/secure-profile path: the live server ran `enforce-secure-profile=false`, `online-mode=false`).
* The prompt call sites for emergency action, direct punishment, revoke, evidence void and case edit use the same `PromptManager.ask()` and were covered by the automated suite, not individually by real clients.
* In the first full phase-6 runs on 1.1.1 one check failed twice (a control chat line did not reach a second test player). With the previous plugin load order the same driver passed without any delay; with a 3 s settle after the two logins it passes with the new load order too (65/65, repeated). The explanation that fits the evidence is a timing race in the test driver (TownyChat puts a joining player into its channel shortly after the join event, and the load-order change shifted that timing). I did not prove that mechanism inside TownyChat. A real player who chats within the first moments after joining could in principle be affected by the same timing; the effect was not seen with real protocol clients in phase 7, where the control lines always arrived.

---

# 1.1.0 test report (history)

**Automated suite:** 196 tests, 0 failures (`./gradlew test`): CaseAndCustodyTest 31, ConfigAndModelTest 12, DefendantAndEventsTest 8, DiscordServiceTest 15, JudicialAndPunishmentTest 44, MigrationV2Test 3, QueueExportAppealTest 11, ReviewAndWarningTest 17, ReviewFixesRegressionTest 7, SmokeMockTest 1, UpgradeAndConfigTest 6, BukkitLayerTest 30, GuiFlowTest 5, ReviewGuiTest 6.

**Live server runs** (Paper 26.3, Java 25, real JailReloaded 1.2.2, LuckPerms 5.5.71, EssentialsX 2.22, Towny 0.103.2, **TownyChat 0.122, CoreProtect 25.1**, AuraCommandFilter 1.0.0). Evidence in `docs/evidence/`:

| Run | Result | File |
|---|---|---|
| Upgrade: first boot of 1.1.0 on a **real 1.0.0 database** (10 punishments, 14 cases) | migrated to v2, backup taken, all rows kept, 4 legacy reviews (none pending), `config.yml` byte-identical | `upgrade-from-1.0.0-real-data-boot.txt` |
| 1.0.0 regression, phase 1 (full functional run, fresh data) | 54 / 54 | `live-results-1.1.0-regression-phase-1.txt` |
| 1.0.0 regression, phase 2 (after restart) | 9 / 9 | `live-results-1.1.0-regression-phase-2.txt` |
| 1.0.0 regression, phase 3 (JailReloaded missing) | 8 / 8 | `live-results-1.1.0-regression-phase-3-jailreloaded-missing.txt` |
| **Phase 6 - online players, TownyChat, AuraCommandFilter, CoreProtect, real GUI** | **65 / 65** | `live-results-phase-6-online-players.txt` |

Online players in phase 6 are real server-side players: the driver runs the genuine login events (AsyncPlayerPreLogin, PlayerLogin) and the vanilla join sequence over an in-memory network channel that records every packet the server sends them. This is not a Minecraft client (no rendering), so *what a human sees* is inferred from the exact packets (title text, animation 10/100/20, sound name/volume/pitch, chat lines).

## Requested live checks (spec section 10)

| Item | Status |
|---|---|
| Online JailReloaded custody of a connected player (jailed, teleported, detention message) | **PASS (live)** |
| Disconnect/reconnect while in custody | **PASS (live)** |
| Not-guilty verdict releases the connected prisoner, no punishment recorded, defendant told | **PASS (live)** |
| Guilty verdict / same-record jail conversion / unrelated JailReloaded record untouched | **PASS (live, phase 1 regression; offline players)** - not repeated with a connected player |
| TownyChat: unmuted control reaches listener; muted player's message not delivered; muted player told; 26 channel/PM/emote/mail commands and aliases (`g gc lc tc nc ac msg tell w m r t me ch ...`) deliver nothing; speech restored after unmute | **PASS (live)**. Note: only the global/general channel was exercised as a *chat event*; town/nation channels need a founded town and were checked through their *commands* (blocked) |
| AuraCommandFilter per-player visibility with four real LuckPerms ranks (none / trainee / mod / owner): `/punish`, `/ap`, `/punishments` shown only to holders of the right nodes | **PASS (live)** (ranks are LuckPerms groups on logged-in test players) |
| Real login enforcement: banned player refused with the ban screen at the real login stage, pending owner review created, accepted after revocation | **PASS (live)** |
| Warning title / sound / chat, offline queue, ordering, no duplicates, delivered-after-shown | **PASS (live, packet level)** |
| CoreProtect read-only lookup (blocks, chat), permission required, world unchanged | **PASS (live)** |
| `/punish` opens a real inventory GUI, click navigates, permission revoked mid-GUI is refused by the service | **PASS (live)** + MockBukkit tests |
| GUI evidence void/correct | **PASS (automated GUI tests only)**; not exercised on the live server |
| Duplicate clicks | **PASS (automated)** `doubleClickingADecisionOnlyDecidesOnce`, `rapidRepeatedClicksAreDebounced` |
| Review workflows (take, note, explain, evidence, uphold, modify, reverse, refer, deadlines, reminders, overdue) | **PASS (automated, service + GUI)**; reverse/uphold also through real clicks and chat prompts (MockBukkit) |
| **Discord delivery to real Discord** | **NOT TESTED against Discord** (sandbox cannot reach discord.com). Routing, categories, fallbacks, multiple destinations, role mentions, retries, 429 handling, permanent failure, outage non-blocking, de-duplication, restart recovery: **PASS against a local HTTP stand-in** (`DiscordServiceTest`) |
| Invalid webhook / outage / rate limit behaviour | **PASS (local stand-in)** |
| Live phases 4 and 5 (incompatible JailReloaded; version drift) | **NOT re-run for 1.1.0** (1.0.0 results stand; the code paths were not changed) |

## Spec section 11 test categories - coverage map

| Area | Where |
|---|---|
| Warning system (title/sound/chat/actionbar config, truncation, offline queue, order, duplicates, hierarchy, permission, no review, repeat indicator, history, Discord) | `ReviewAndWarningTest`, `ReviewFixesRegressionTest`, `UpgradeAndConfigTest`, live phase 6, `BukkitEnforcement.shorten` (title truncation is by code point; exercised through packets only for the default non-reason title - **the show-reason title variant has no automated test**) |
| Quick punishments and review (creation, statuses, reviewer actions, deadlines, reminders, no auto reversal, expiry, evidence follow-up, classification) | `ReviewAndWarningTest`, `ReviewGuiTest`, `ReviewFixesRegressionTest` |
| Discord (categories, overrides, fallbacks, multiple, secrets, async, rate limit, retry, dedupe, restart, mass mentions, no IPs, outage) | `DiscordServiceTest`, `UpgradeAndConfigTest` |
| Dashboards, work queue, priorities, permissions filter | `QueueExportAppealTest`, `ReviewGuiTest` |
| Defendant communication | `DefendantAndEventsTest`, live phase 6 |
| Export, appeal ticket, CoreProtect | `QueueExportAppealTest`, live phase 6 |
| Migration, upgrade, config validation, permissions matrix | `MigrationV2Test`, `UpgradeAndConfigTest`, live upgrade run |
| Existing functionality | all 1.0.0 tests (still passing) + live regression phases 1-3 |

Not covered by any test: the show-reason title variant on a live server; deadlines changed through the GUI prompt flow (service tested); `warnings.repeat.recommend-case` (not implemented beyond the indicator).

---

# 1.0.0 test report (history)

# AuraPunishments 1.0.0 - Test Report and Readiness Assessment

**Source commit tested and released:** `4bd660cb7aa2893d07fafc6748d938f964060279`  (see `release/BUILD-INFO.txt`; jar SHA-256 in `release/AuraPunishments-1.0.0.jar.sha256`)

## 1. Summary

| Layer | Result |
|---|---|
| Automated unit/integration tests (JUnit 5, real SQLite, MockBukkit) | **127 tests, 0 failed** |
| Live tests inside a **real Paper 26.3 server** (`26.3.build.256-stable`, Java 25.0.5) with the **real JailReloaded 1.2.2**, AuraCommandFilter 1.0.0, LuckPerms 5.5.71, EssentialsX 2.22.0, Towny 0.103.2.0 | **132 checks, 0 failed** (5 phases) |
| Not verifiable here (needs real clients / unavailable jar) | listed in section 5 - **run the controlled-deployment test** |

Everything below was actually executed. Nothing is described as passed unless the evidence file or test named exists in this repository.

## 2. Environment

* Paper `paper-26.3-256.jar` (SHA-256 `c86baf83...c9bdc`, from fill.papermc.io), OpenJDK 25.0.5, Gradle 9.2.1.
* Supplied custom jars: `JailReloaded-1.2.2.jar`, `AuraCommandFilter-1.0.0.jar` (in `libs/`). LuckPerms/EssentialsX/Towny downloaded from Modrinth (versions above; your servers run LuckPerms 5.5.91, EssentialsX 2.22.1-dev+28, Towny 0.103.2.0).
* TownyChat, CoreProtect, Grim, DiscordSRV, TAB, PlaceholderAPI, QuickShop, WorldGuard/WorldEdit, ProtocolLib, Vault were **not** present in the live tests.
* MockBukkit 4.122.0 with `paper-api 26.3.build.230` for the Bukkit-layer tests (MockBukkit's registry data does not match build 256); the plugin itself compiles against build 256.

## 3. How to reproduce

```
./gradlew test                       # automated suite -> build/reports/tests
./gradlew :jar :live-test:jar        # plugin + live driver
# live: Paper 26.3 + plugins/ {JailReloaded, LuckPerms, AuraCommandFilter, AuraPunishments, APLiveTest};
# write plugins/APLiveTest/phase.txt = 1..5; start the server; the driver runs, writes results-N.txt and stops the server.
#   phase 1  full functional run          phase 2  after a restart (persistence)
#   phase 3  JailReloaded absent          phase 4  JailReloaded 1.0.0 (too old)
#   phase 5  real EssentialsX + Towny     (command alias coverage, console dispatch)
```

## 4. Results

### 4.1 Automated suite

| Class | Tests | Failed | Covers |
|---|---|---|---|
| `CaseAndCustodyTest` | 31 | 0 | Case lifecycle, evidence/notes, custody sagas, crash recovery, deadlines (fake JailReloaded, real SQLite) |
| `ConfigAndModelTest` | 12 | 0 | Config/offense/message validation, durations, text cleaning, state-machine invariants |
| `DiscordServiceTest` | 6 | 0 | Webhook delivery, retries, rate limits, permanent failure, non-blocking, mention safety |
| `JudicialAndPunishmentTest` | 42 | 0 | Verdicts, sentencing + time-served credit, appeals/overturn, direct/emergency, quick punishments, expiry/persistence, audit chain, DB failure, concurrency |
| `SmokeMockTest` | 1 | 0 | Plugin loads under MockBukkit |
| `gui.BukkitLayerTest` | 30 | 0 | /punish targeting, permissions, locked/hidden actions, GUI integrity, prompts, mutes (chat + commands), legacy-command redirect, custody guard, login bans, warnings, tab completion, reload |
| `gui.GuiFlowTest` | 5 | 0 | Click-and-chat driven flows: case wizard, verdict, sentence, direct-punishment guard, every screen renders, free text never interpreted |
| **Total** | **127** | **0** | |

### 4.2 Live server (evidence files in `docs/evidence/`)

| Phase | What it proves | Checks | Failed |
|---|---|---|---|
| 1 | Full run against the real JailReloaded + LuckPerms: custody, release, jail-sentence conversion (ms units), time-served credit, acquittal release, ban sentence, login denial, wrong-record refusal, external release event, reconciliation, direct/emergency, real-LuckPerms hierarchy for offline players, config reload validation, audit chain | 54 | 0 |
| 2 | After a real server **restart**: bans/mutes persisted, custody record + JailReloaded prisoner persisted (same record id), release still works, audit intact | 9 | 0 |
| 3 | JailReloaded **absent**: clear failure, no fabricated custody, cases/mutes/bans/non-jail sentences still work, jail sentence refused | 8 | 0 |
| 4 | JailReloaded **1.0.0 (too old)**: same safe degradation with a precise reason | 8 | 0 |
| 5 | Real **EssentialsX + Towny**: every real alias of `/ban /kick /mute /msg /r /me ...` covered; `/town` and `/t` not blocked; JailReloaded still answers `/jail`; console runs `/ap` through the real dispatcher | 53 | 0 |

Additional live evidence: `acf-scan-report.txt` (AuraCommandFilter's own scan: no AuraPunishments command is unresolved or inferred - their permissions are explicit) and `server-startup-excerpt.txt`.

### 4.3 Defects found by testing and fixed before release

| # | Defect | Found by | Fix |
|---|---|---|---|
| 1 | `/mail read` was blocked while muted (only `mail send` should be) | MockBukkit suite | Removed `mail` from the blocked list; `mail send` remains |
| 2 | Tab completion revealed **vanished** online players via the known-names cache | MockBukkit suite | Filtered by `canSee` |
| 3 | Alias resolution unioned every alias of a command: a muted player would have lost `/town` because Towny's `/town` owns `/t` | Live phase 5 output review | Resolve the typed label to the command that actually executes; regression tests added (MockBukkit + live) |
| 4 | Periodic reconcile could mark an **in-flight** detention failed (between "recorded" and "JailReloaded confirmed") | Design review | 60 s in-flight grace; immediate resolution only at startup |
| 5 | **`/ap`, `/punish`, `/punishments` did not resolve from the server console**: all nodes default to false (even for ops) | Manual console probe on the live server (MockBukkit's console always has permissions; the live driver called services directly) | `aurapunishments.command` default op, console trusted in `/ap`; live check added |
| 6 | A "kick" could be recorded for an offline player | Review while writing docs | Refused with a clear message |
| 7 | `messages.yml` validation claimed to reject bad MiniMessage but MiniMessage is lenient | Config tests | Validation now checks value types/parse errors; docs corrected |
| 8 | Free text (hearing reference) placed in a MiniMessage lore template unescaped | Security pass | Escaped; test added |
| 9 | Test-harness issues (blocking the main thread in tests, async event on main thread, MockBukkit gaps) | - | Test code only; not product defects |

## 5. The 34 required tests

Legend: **PASS** automated and executed; **LIVE** executed on the real Paper server; **PARTIAL** part verified, remainder needs the pilot; **PILOT** cannot be verified without real clients or a missing jar.

| # | Required test | Status | Evidence |
|---|---|---|---|
| 1 | Startup on the target Paper environment | **LIVE** | `server-startup-excerpt.txt` (Paper 26.3-256, Java 25, 5 other plugins) |
| 2 | `/punish <player>` opens the correct target GUI | **PASS** | `BukkitLayerTest.punishOpensTheTargetGuiForAnOnlinePlayer`, `GuiFlowTest` |
| 3 | Online player targeting | **PASS** | same |
| 4 | Verified offline player targeting | **PASS + LIVE** | `punishOpensTheGuiForAVerifiedOfflinePlayer`; live "verified offline target resolved" |
| 5 | Rejection of unknown targets | **PASS + LIVE** | `unknownTargetIsRejectedWithoutOpeningAnything`; live unknown/ambiguous checks |
| 6 | Permission restrictions on `/punish` | **PASS** | `punishRequiresItsPermission`, `punishPermissionAloneDoesNotAllowTargetingOthers`, `cannotOpenYourOwnPunishmentScreen` |
| 7 | Permission-based GUI actions | **PASS** | `unpermittedActionsAreShownLockedAndPermittedOnesAreLive`, `lockedActionsCanBeHiddenByConfig`, `apHelpAndTabCompletion...` |
| 8 | Case creation | **PASS + LIVE** | `createACaseThroughTheWizard` (clicks + chat), `CaseAndCustodyTest`, live |
| 9 | Evidence management | **PASS** (services) / **PARTIAL** (GUI) | add/validate/void/correct/view-permissions tested at service level; evidence screen renders; void/correct *click* flows not automated |
| 10 | Pretrial custody through JailReloaded | **LIVE** (offline-pending path) / **PILOT** (online player) | phase 1; see Known Limitations 1 |
| 11 | Release through JailReloaded | **LIVE** / **PILOT** (online) | phases 1-2 |
| 12 | Final jail sentencing through JailReloaded | **LIVE** | same record converted; `remaining=7197162 expected~7197162` ms |
| 13 | Discord trial case management | **PASS** (judge, trial time, hearing reference, status, alerts) | `CaseAndCustodyTest`, `GuiFlowTest`, `DiscordServiceTest`; the hearing itself is manual in Discord; a real webhook was not contacted |
| 14 | Guilty verdict processing | **PASS + LIVE** | `GuiFlowTest.verdictAndSentenceThroughTheGui`, `JudicialAndPunishmentTest` |
| 15 | Not-guilty verdict processing (incl. release) | **PASS + LIVE** | acquittal release live |
| 16 | Direct punishment with evidence | **PASS + LIVE** | `directPunishmentSuccessRecordsEverything`, live |
| 17 | Rejection of direct punishment without evidence | **PASS + LIVE** | `directPunishmentRequiresEvidence...`, live |
| 18 | Emergency action restrictions | **PASS + LIVE** | permission, types, duration cap, reason, review, auto-expiry, alerts |
| 19 | Staff hierarchy protection | **PASS + LIVE** | unit, MockBukkit, and real-LuckPerms offline lookups |
| 20 | Temporary and permanent bans | **PASS + LIVE** | login denial incl. IP bans, no IP on the screen |
| 21 | Mutes across supported TownyChat pathways | **PARTIAL** | public chat cancelled (MockBukkit); channel/PM **commands** incl. all real EssentialsX aliases blocked (live); **TownyChat itself untested (jar unavailable)** |
| 22 | Punishment expiration | **PASS** | `muteExpiryRestoresSpeech`, `banAndMuteExpireAndSurviveRestart`, emergency auto-expiry |
| 23 | Punishment reversals | **PASS** | revoke, overturn, appeal upheld/overturned/modified |
| 24 | Offline punishment handling | **PASS** | offline warn queued and delivered at login; offline mutes/bans/IP bans; offline kick refused |
| 25 | Persistence across restarts | **LIVE** | phase 2 + file-database unit test |
| 26 | Database failure handling | **PASS** | closed database -> `DB_ERROR "Nothing was changed"`, automatic reconnect after a dropped connection; startup refusal when the database cannot open is by design but not automated |
| 27 | Discord webhook failure handling | **PASS** | 6 tests: retry on 5xx, rate limit, permanent failure, hanging endpoint (20 sends < 250 ms), disabled |
| 28 | Missing or incompatible JailReloaded | **LIVE** | phases 3 and 4 |
| 29 | EssentialsX command conflict prevention | **LIVE** | phase 5 with real EssentialsX 2.22.0; MockBukkit regression tests |
| 30 | AuraCommandFilter compatibility | **PARTIAL** | live `/acf scan`: commands resolved from explicit permissions; per-player visibility (`/acf debug`) needs a connected player |
| 31 | Duplicate-click and stale-GUI protection | **PASS** | `rapidRepeatedClicksAreDebounced`, `anInFlightActionBlocksRepeats...`, `aStaleWindowCannotBeUsed...`, `itemsCanNeverBeMovedOrTaken` |
| 32 | Audit history integrity | **PASS + LIVE** | tamper detection, pruning anchor, intact after restart |
| 33 | Permission revocation while a GUI is open | **PASS** (service re-check) / **PARTIAL** (LuckPerms close-on-change not automated) | `permissionRevokedWhileTheGuiIsOpenStopsTheAction` |
| 34 | Concurrent punishment and case modification | **PASS** | `concurrentModificationsOnlyOneWins`, `concurrentPunishmentsOfTheSamePlayerOnlyOneSucceeds`, `concurrentCustodyRequestsHoldThePlayerOnce` |

## 6. Limitations requiring a live-server pilot

1. **Online-player jailing/release** (teleport, inventory restore) - JailReloaded's behaviour, not exercised: the headless server has no clients and JailReloaded will not enable under MockBukkit. Steps in `INSTALLATION.md`.
2. **TownyChat 0.122** chat handling.
3. **AuraCommandFilter** per-player visibility.
4. Version differences: LuckPerms 5.5.71 vs your 5.5.91; EssentialsX 2.22.0 vs 2.22.1-dev+28.
5. Real Discord delivery (only a local HTTP server stood in for Discord).
6. GUI void/correct-evidence click flows, and the LuckPerms "close GUIs on permission change" event, are not click-tested.
7. IPv6/proxy/CGNAT behaviour of IP bans; Bedrock (Floodgate) names.

## 7. Readiness assessment

**AuraPunishments 1.0.0 is ready for controlled deployment testing.**

Evidence for: the full judicial and direct-punishment workflows, enforcement, persistence across restarts, safe degradation without
JailReloaded, and the JailReloaded integration (including the unit conversion, same-record sentencing, wrong-record refusal and
external-release detection) were all exercised against the **real JailReloaded on a real Paper 26.3 server**, plus 127 automated tests
covering authorisation, concurrency, failure handling and GUI behaviour. Eight defects were found by testing and fixed; the final
jar is the one that passed every check above.

It is **not yet proven for production** until the pilot items in section 6 are confirmed on your server. The three that matter most:
online-player detention/release, TownyChat muting, and AuraCommandFilter visibility for a normal player. They take about 30 minutes
with two staff accounts and one test player; do them before announcing the system to staff. If any fails, the fixes are expected to be
small and local (the adapter, one listener priority, or a permission declaration).
