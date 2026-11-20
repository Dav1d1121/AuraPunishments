# AuraPunishments 1.0.0 - Custom Plugin Integration Report

Method: every supplied jar was unpacked and inspected (`plugin.yml`, manifest, class list, `javap` of the public API and the
relevant bytecode paths). Nothing was assumed from a plugin's name. Where behaviour could be exercised, it was exercised on
a **real Paper 26.3 (`26.3.build.256-stable`) server on Java 25** with the real jars (see [TEST_REPORT.md](TEST_REPORT.md)).

## 1. JailReloaded 1.2.2 (primary integration)

### 1.1 What was inspected

| Item | Finding |
|---|---|
| Jar | `JailReloaded-1.2.2.jar`, `com.rismr1:JailReloaded`, main `com.graywolf336.jail.JailMain`, built with JDK 21 bytecode (class version 65), `api-version: '1.21'`, softdepends WorldEdit/FAWE/Vault/Multiverse-Core/LuckPerms/PlaceholderAPI. "A maintained fork of graywolf336's Jail plugin for Paper servers." |
| Public API entry | `JailsAPI` (static): `getJailManager()`, `getPrisonerManager()`, `getJailStickManager()`, `getHandCuffManager()`, `getJailVoteManager()`, `getBuildNumber()`, `getTimeFromString()`. |
| Jailing | `PrisonerManager.prepareJail(Jail, ICell, Player, Prisoner)` - registers the prisoner, persists **strictly** (`saveJailsStrict`; on failure rolls back and throws, "nothing was changed"), schedules placement for online players, leaves offline players *pending* until their next join. Throws `PrisonerAlreadyJailedException` / `JailRequiredException` / `PrisonerRequiredException`. |
| Release | Online: `unJail(...)` (may finish after a teleport). Offline & already placed: set remaining time 0 + `offlinePending` + `saveJailsStrict` (completes at next login) - exactly what its own `/unjail` does. Offline & never placed: `forceUnJail` removes the record. |
| Prisoner record | `Prisoner(uuid, name, muted, timeMillis, jailer, reason)`; `getRemainingTime()` is **milliseconds** (`-1` = until released, confirmed by `isJailedForever()` and by measurement on the live server); persistent **`ensureJailId()`**; `setRemainingTime(ms)`. |
| Events | `PrePrisonerJailedEvent` (cancellable; fired by `/jail` before jailing), `PrisonerJailedEvent`, `OfflinePrisonerJailedEvent`, `PrePrisonerReleasedEvent`, `PrisonerReleasedEvent`, transfer/time-change/guard/death events. |
| Commands | `/jail` (with many sub-commands), `/unjail`, `/unjailforce`, `/handcuff`, `/unhandcuff`, `/togglejaildebug`; permission `jail.cantbejailed` blocks jailing. |
| Storage | Its own SQLite/flatfile/MySQL, prisoner ledger, audit log. AuraPunishments never reads or writes it directly. |

### 1.2 Integration method

* **Official programmatic API** wherever one exists; a single isolated adapter (`JailReloadedAdapter`) behind a `JailPort`
  interface; a bootstrap class (`JailBootstrap`) that references no JailReloaded class directly, so a missing or incompatible
  JailReloaded yields an `UnavailableJailPort` with a precise reason instead of a `NoClassDefFoundError`.
* **No commands are dispatched and no undocumented behaviour is relied upon**: the adapter replicates the sequence of
  public calls JailReloaded's own `/jail` and `/unjail` commands perform (construct `Prisoner`, fire `PrePrisonerJailedEvent`
  so other plugins can veto, `prepareJail`; `unJail` / `forceUnJail` / the offline-pending release).
* **No replacement jail mechanics** exist anywhere in AuraPunishments.
* **Case association**: AuraPunishments stores JailReloaded's persistent `jailId` per custody and tags the JailReloaded
  reason with `[AP-0012]`. Release / time changes are only performed on the record whose id matches; otherwise the adapter
  returns `NOT_OURS` and leaves it untouched (**unrelated jail sentences cannot be released**).
* **Verification**: success is only reported after the adapter re-reads JailReloaded's records (`JailManager.getPrisoner`).
* **Recoverability**: intent is recorded (custody row + pending operation) before the call and the verified result after it;
  `reconcile()` compares AuraPunishments' live custody with JailReloaded's real prisoners at startup and every minute and
  repairs differences (adopts an interrupted detention, fails one that never happened, marks one ended behind our back and alerts).
* **External changes**: a `PrisonerReleasedEvent` listener keeps custody truthful when JailReloaded's timer, a player's next
  login or an admin command releases someone. JailReloaded commands that would release/alter a case prisoner are refused unless
  the sender holds `aurapunishments.custody.override`.
* **Version gate**: accepted `>= 1.2.0` and `< 2.0`; tested `1.2.2`; an API probe (`JailsAPI`, `JailMain.getJailIO()`) must pass;
  a different version than the tested one logs a warning.

### 1.3 Limitations found and the smallest maintainable solution

| Finding | Handling | Optional upstream improvement |
|---|---|---|
| `JailsAPI` has no method to save jails; saving needs `JailMain.getJailIO().saveJailsStrict(..)` (public, but outside `JailsAPI`). | Adapter uses `JailMain.getJailIO()`; covered by the startup API probe and version gate. | Expose `saveJail(Jail)` and a "release offline prisoner" helper on `JailsAPI`. |
| Release/jail has no completion callback; offline releases finish at the next login; online release may finish after a teleport. | Result states `RELEASE_PENDING` / `JAILED_PENDING_PLACEMENT`; custody stays `RELEASING` until `PrisonerReleasedEvent`/reconcile confirms. | A `CompletableFuture` result or a "release completed" event carrying a caller token. |
| `PrePrisonerReleasedEvent` is not cancellable. | Prevention is done by guarding JailReloaded's commands; the JailReloaded *timer* cannot be vetoed, so pretrial custody uses open-ended time (`-1`) and never expires on its own. | Make the event cancellable. |
| JailReloaded refuses never-played players and `jail.cantbejailed` holders. | Surfaced to staff as a clear failure; the case is unchanged. | - |
| `allowJaillingOfflinePlayers` (sic) must be true to detain offline players. | Documented in the Admin handbook. | - |

No change to JailReloaded was necessary.

### 1.4 Required configuration changes in JailReloaded

* At least one enabled jail (arrival + release points). Optionally set its default jail, or AuraPunishments'
  `custody.jail-name`.
* Keep `allowJaillingOfflinePlayers: true` for offline detention.
* Consider its `commandWhitelist` (commands prisoners may use); `/ap` and `/punish` are staff tools and need no whitelist.

### 1.5 What stays with JailReloaded

Physical imprisonment, jail/cell management and locations, confinement rules, sentence timers, release mechanics, property
(inventory) handling, guards, jail sticks/votes/payments, and all existing jail commands. AuraPunishments handles case
association, custody records and deadlines, verdicts, sentencing decisions, and *asking* JailReloaded to jail/release.

### 1.6 Verification

| Check | Evidence |
|---|---|
| Detected as compatible on startup | Live phase 1 (real JailReloaded 1.2.2) |
| Pretrial custody via the API; reason tagged; open-ended time; jailer recorded; 48h deadline | Live phase 1 |
| Second custody for the same player refused | Live phase 1 + unit |
| Jail sentence converts the **same record**; remaining time = sentence - time served, in **milliseconds** | Live phase 1 (`remaining=7197162 expected~7197162`) |
| Not-guilty releases; no punishment recorded | Live phase 1 |
| Ban sentence releases custody first | Live phase 1 |
| An unrelated record is **not** released; wrong-id release/time change refused (`NOT_OURS`) | Live phase 1 |
| External `/unjailforce` detected via the release event and reconciled | Live phase 1 |
| Prisoner removed behind our back is detected by reconciliation | Live phase 1 + unit |
| State and JailReloaded prisoner persist across a server restart; release works afterwards | Live phase 2 |
| JailReloaded missing / too old: clear failure, nothing fabricated, everything else works | Live phases 3 and 4 |
| Crash recovery of interrupted detention/sentence | Unit tests (simulated crash points) |
| **Not verified live**: the *online-player* path (a connected player teleported to the jail and released with their inventory restored) | Needs real clients; JailReloaded refuses to enable under MockBukkit. **Run the controlled-deployment test in INSTALLATION.md.** |

## 2. AuraCommandFilter 1.0.0

| Item | Finding |
|---|---|
| Jar | `dev.aura.commandfilter.AuraCommandFilterPlugin`, `api-version: '26.3'`, Java 25 bytecode, **requires LuckPerms** (disables itself without it). |
| Model | Indexes every registered command; visibility comes from the command's **declared permission** (or an inference from the owning plugin's declared nodes, listed under `/acf scan`), evaluated live against the player's LuckPerms permissions; filters the command tree, tab completion and sub-command completion; hides namespaced commands; filtered `/help`. |
| Integration | Nothing to configure and no redundant filtering in AuraPunishments. Every command declares its permission in `plugin.yml`: `/punish` -> `aurapunishments.punish`, `/punishments` -> `aurapunishments.dashboard`, `/aurapunishments` (`/ap`) -> `aurapunishments.command`. Sub-command and player-name tab completion are permission-aware and respect vanish (`canSee`); a sender without `punish.others` gets **no** name suggestions. |
| Verified | On the real server with real LuckPerms: ACF `Enabled. Filtering 225 command keys`; `/acf scan` lists **none** of the AuraPunishments commands as *unresolved* or *inferred* - their permissions are explicit. |
| Finding to act on | ACF's scan reports that **EssentialsX and JailReloaded both claim `/jail` and `/unjail`** (JailReloaded answers them). Keep EssentialsX's jail commands unpermitted. |
| Not verified | Per-player visibility via `/acf debug <player> ...` needs a connected player. Confirm in the deployment test: a player without the nodes must not see `/punish`, `/punishments`, `/ap` in tab completion. |

## 3. Other plugins

| Plugin (tested version) | Integration | Evidence |
|---|---|---|
| **LuckPerms 5.5.71** (yours: 5.5.91) | API for offline level/immunity/judge-permission lookups; `UserDataRecalculateEvent` subscription to close open GUIs; online checks via Bukkit permissions. Falls back gracefully when absent. | Live: offline high-ranked and immune players protected through real LuckPerms lookups. |
| **EssentialsX 2.22.0** (yours: 2.22.1-dev+28) | Punishment commands redirected; private-message/emote commands blocked while muted; every alias resolved through the command map. 2.22.0 prints "unsupported server version" on Paper 26.3 but registers its commands, which is what was tested. | Live phase 5: all real aliases of `/ban /kick /mute /msg /r /me ...` covered; `/spawn` untouched; JailReloaded still answers `/jail`. |
| **Towny 0.103.2.0** (same as yours) | No direct dependency. `/town` and its `/t` alias are **not** blocked while muted (an alias owned by another command is judged as that command). | Live phase 5 + MockBukkit regression test. |
| **TownyChat 0.122** | Public chat cancelled at `LOWEST` priority; channel shortcuts and `town/nation say` blocked while muted. | **Not tested with the real jar** (not obtainable here). See Known Limitations. |
| **CoreProtect 25.1, Grim 2.3.74** | Evidence *types* only (reference text such as `/co l u:Name t:1h`). No API calls. | - |
| **DiscordSRV, TAB, PlaceholderAPI, QuickShop, WorldGuard/WorldEdit, ProtocolLib, Vault** | No integration, no conflict expected. (JailReloaded itself uses WorldEdit/Vault/PlaceholderAPI.) | AuraPunishments + JailReloaded + LuckPerms + ACF + EssentialsX + Towny started together without conflict. |

## 4. Summary of responsibilities

| Concern | Owner |
|---|---|
| Imprisonment, jails, release mechanics | **JailReloaded** |
| Cases, evidence, verdicts, sentencing decisions, custody records/deadlines | **AuraPunishments** |
| Warnings, kicks, mutes, bans, IP bans, expiry, history | **AuraPunishments** |
| Command visibility/tab filtering | **AuraCommandFilter** (AuraPunishments only declares permissions) |
| Permission storage and hierarchy data | **LuckPerms** (AuraPunishments reads it) |
| Private messaging / channels | EssentialsX / TownyChat (AuraPunishments blocks muted players) |
