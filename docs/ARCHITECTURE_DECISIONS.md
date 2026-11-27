# 1.3.0 - architectural decisions, assumptions, deviations

## Decisions

1. **One jar, one switch, evaluated per operation.** `judicial.enabled` is read from the live settings snapshot for every operation (`JudicialMode`). A `/ap reload` that flips it takes effect immediately without a restart. Only operations that *start* judicial work consult it; operations that *finish* existing work never do, which is what makes wind-down safe.
2. **Missing key means ON.** A config file without `judicial.enabled` is by definition an upgrade from 1.0-1.2, so the safe reading is "behave as before". Fresh installs get the key written explicitly as `false` by the shipped file. This needs no filesystem or database "detection" that could mis-fire, and an explicit value can never be overridden. The one corner case (config deleted on a server with open cases) is covered by wind-down, which looks at the data, not the file.
3. **Wind-down instead of auto-resolution.** The plugin never decides what happens to a detainee or an open case. It counts open work (cases still being worked, live pretrial custody, undecided case appeals), warns administrators in five places, refuses *new* judicial work, and lets existing work be finished with the existing, fully tested tools.
4. **Evidence/notes/appeals belong to a punishment, not only to a case.** `evidence`/`appeals` got a nullable `case_id` plus `punishment_id` (CHECK: at least one). Existing rows are untouched and stay on their cases; the punishment screen shows both. With the judicial system **on**, behaviour is unchanged (hidden QUICK cases still carry evidence given at issuance), so the whole 1.2.x test-suite passes unmodified apart from the fixture default.
5. **Jail punishment = a normal punishment row with jail columns**, not a case-less custody row. Pretrial custody keeps its own table and meaning; a jail punishment has a verdict-free life cycle (PLACING -> HELD/PENDING_PLACEMENT -> RELEASING -> RELEASED, or FAILED/ENDED_EXTERNALLY). The three-step "record intent / ask JailReloaded / record verified result" pattern and the reconciliation rules mirror the existing custody saga, so the same failure modes are covered the same way.
6. **A revoke is a decision; the release is a fact.** Revoking a jail punishment is recorded immediately; the physical release is verified and retried. The staff member is told when the two have not converged yet. The record keeps `RELEASING` until JailReloaded confirms.
7. **Appeals attach to punishments.** The ticket reference is a label (number or Discord link) validated by the same rule the case flow used. Decision effects run in the same transaction as the decision (overturn = authorised revoke, reduce = modification) so an appeal can never say "overturned" while the punishment is still active.
8. **Owner review stays a queue.** `review.permanent-only-types` and `review.include-emergency` only decide what is *submitted*; nothing waits for review. A case-free emergency action is reviewed in one place; the owner-review record and the emergency review settle each other.
9. **Discord: filter and render, don't re-route.** The punishment log and public log are additional filters/renderers on the existing outbox, so de-duplication, retries, rate limits and fallbacks are the ones already tested. The public renderer reads only a whitelist of fields instead of filtering a general one, so it cannot leak a field somebody adds later.
10. **Network foundation, no network behaviour.** Dialect interface, UUID record ids assigned by a database trigger (so no code path can omit one), origin stamped by the same trigger from `meta`, one operation id per transaction. The audit hash chain is untouched so older chains still verify. Nothing in 1.3.0 reads another server's data.

## Assumptions

* JailReloaded 1.2.x semantics as verified in [INTEGRATION_REPORT.md](INTEGRATION_REPORT.md): jail time counts down only while the prisoner is online unless configured otherwise, so a jail punishment's stored expiry is nominal and the end is decided by JailReloaded's event (plus reconciliation).
* `aurapunishments.punish.jail` is the node for jail punishments (the plugin already evaluated it; it is now declared in `plugin.yml`).
* An appeal decider need not outrank the *issuer*, only the *target* (the existing hierarchy rule for acting on a player).
* Staff who issue a punishment may always see and add evidence on it, even without `case.evidence.*` (they must be able to support what they did); everyone else needs the nodes.

## Deviations from the request (and why)

| Request | What was built | Why |
|---|---|---|
| Evidence "attach directly to the punishment" in every mode | In judicial-ON mode evidence given at issuance still goes to the hidden case the 1.2.x flow created; evidence added later goes to the punishment (or the open case while it is being worked) | "When enabled: all existing judicial functionality unchanged" - changing where evidence lives in ON mode would have altered exports, review screens and case files. The punishment screen shows both. |
| Discord "network enforcement status" field | Present (`network-status`), always "this server only" | There is no network enforcement until the network release; the field exists so the format does not change then. |
| "Scheduled tasks hidden" when off | Custody deadline sweeps and custody reconcile are skipped while the system is DISABLED and run while ENABLED/WIND_DOWN; the emergency-review sweep, owner-review reminders, jail reconciliation and Discord/notification housekeeping always run | Those serve features that exist in both modes. |
| `/ap judge` etc. "hidden" | Hidden from help/tab-complete and refused with "no permission" while DISABLED; `case`, `cases`, `export` stay for historical access | "Historical judicial records remain accessible to authorized staff." |
| "Pretrial vs punitive jail distinct" | Separate records, labels, commands and screens; the JailReloaded prisoner reason carries `[AP-0012]` (case) vs `[AP-J0012]` (jail punishment) | See [JAIL.md](JAIL.md). |

## Known limitations (also in TEST_REPORT.md)

* **No network-wide behaviour** (by design for this release): bans, mutes, jail and appeals apply to the server that issued them. Running 1.3.0 on several servers with separate databases works, but they do not see each other yet.
* **Real Discord delivery** was exercised against a local HTTP stand-in (no outbound access to discord.com from the build environment). Webhook URL shape and the embed JSON follow Discord's documented format and the same code path as 1.1.0 - 1.2.1 in production use.
* **Wind-down counts** include open cases of every kind; a case you have no intention of finishing must be closed (or the switch kept on). Closing a case does not alter punishments already imposed.
* **Offline jail release** completes at the player's next login (JailReloaded behaviour); the record stays RELEASING until then and is not reported as released.
* Jail time is JailReloaded's clock; AuraPunishments cannot shorten it other than by releasing.
* Appeals cannot be recorded against punishments that are already revoked, expired or served.
* `messages.yml` files from 1.2.x do not contain the new `command.jail-guard`, `alert.punishment-expired` texts; the built-in text is used (the file is never rewritten).
* Text in custom `messages.yml` that still says "case <ref>" for alerts will print "case -" for events that have no case (use the new `<where>` placeholder).
