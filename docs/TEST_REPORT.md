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
