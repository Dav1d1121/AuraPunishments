# 1.3.0 - defects found and fixed during this release

| # | Defect | Found by | Fix |
|---|---|---|---|
| 1 | `docs/CHANGELOG.md` was an empty file since the 1.2.0 release commit (the release notes pointed at it) | review of the release package | rebuilt from the release notes and commit history for 1.0.0 - 1.3.0 |
| 2 | Warning text told players "Further breaches may lead to a case against you." even on servers where no case can ever exist | review of player-facing wording with the judicial system off | neutral wording in the shipped `messages.yml` (existing files are never rewritten) |
| 3 | In-game alerts for events with no case read "case -" (emergency, appeal, reversal) | self-review of every alert template against case-free events | alerts say `punishment #12` / `case AP-0012` through a new `<where>` placeholder |
| 4 | Emergency GUI success message would have formatted a missing case id | self-review of case-dependent GUI strings | message adapted; test added |
| 5 | A second jail request for an already-jailed player first created a punishment row, then let JailReloaded refuse it | unit test written for the jail saga | refused up front (`DUPLICATE`) without leaving a failed record |
| 6 | `aurapunishments.punish.jail` was evaluated by the permission checks but not declared in `plugin.yml` (tab-completion/LuckPerms discovery) | permission audit | declared (with `.permanent`); `aurapunishments.appeal.decide` added |
| 7 | A revoked jail punishment whose release did not complete showed a plain success | design review of the release saga | the staff member is told it is pending; retried automatically; recorded as `RELEASING` until JailReloaded confirms |
| 8 | `appeal.decide` holders could not work on case appeals still open when the judicial system was switched off | scenario test (wind-down) | case-appeal take/decide/ticket accept `judicial.overturn` or `appeal.decide` |

Earlier releases: see the sections below.

---

# AuraPunishments 1.1.0 - Bug review and fix summary

## A. Defects fixed from 1.0.0 (and kept as regression tests)

| # | Defect (found in 1.0.0 testing) | Fix | Regression coverage |
|---|---|---|---|
| 1 | `/ap`, `/punish`, `/punishments` unusable from the console | base node default `op`, console trusted | `BukkitLayerTest`, live check |
| 2 | `/mail read` blocked while muted | `mail` removed from blocked list | `BukkitLayerTest` |
| 3 | Vanished players leaked through name suggestions | visibility filter | `playerNameSuggestionsRespectPermissionAndVanish` |
| 4 | A muted player lost `/town` because an alias of another command (`/t`) was matched | resolve the label to the command that actually executes | `aLabelOwnedByAnotherCommandIsJudgedByThatCommand`, live phase 5 |
| 5 | Custody reconcile raced with an in-flight operation | 60 s in-flight grace + startup-immediate pass | `CaseAndCustodyTest` |
| 6 | Offline kick recorded as if applied | refused | `kickNeedsAConnectedPlayer` |
| 7 | Unescaped hearing reference in item lore | `escapeTags` | `freeTextIsNeverInterpretedAsFormattingInMenus` |
| 8 | `messages.yml` validation overclaimed | type/parse validation only (documented) | `ConfigAndModelTest` |

## B. Defects found and fixed during 1.1.0 development

| # | Defect | Found by | Fix | Regression test |
|---|---|---|---|---|
| 9 | Export / CoreProtect / appeal-ticket buttons only appeared on *emergency* cases (misplaced brace) | independent review | buttons moved out of the emergency block | `ReviewGuiTest`, live GUI check |
| 10 | Reviewers with only `review.modify` could not *shorten* a punishment (issue-tier limit applied to reductions) | independent review | limit applies only when lengthening | `shorteningNeedsOnlyReviewModifyNotIssueTierNodes` |
| 11 | *Refer to judicial case* skipped hierarchy/immunity, could be repeated, did not emit the case event | independent review | `checkTarget`, duplicate guard, event | `referChecksHierarchyAndCannotBeRepeated` |
| 12 | No separation of duties: issuer could uphold/reverse/refer their own punishment | independent review | issuer cannot take/decide/refer (console excepted) | `issuerCannotTakeDecideOrReferTheirOwnPunishment` |
| 13 | Lost wake-up: a warning committed while delivery was finishing waited for next login | independent review | request-then-claim protocol | `warningsQueuedWhileDeliveryRunsAreNeverMissed` |
| 14 | Warning could be marked delivered when nothing was shown (chat off + title deferred) | independent review | plain chat fallback; returns false if nothing shown | none dedicated (live delivery checks cover the normal paths) |
| 15 | One failing notification blocked a player's whole queue | independent review | per-row failure handling, bounded attempts | none dedicated |
| 16 | `include-reasoning: false` bypassed by the release message | independent review | fixed neutral text | `releaseMessageHonoursIncludeReasoningOff` |
| 17 | Brief window with no cached mute/ban while a review modification refreshed the cache | independent review | atomic replace | `modifyingShortensTheDuration...` |
| 18 | New notify keys undeclared in `plugin.yml`; `review.notify-in-game` ignored; login delivery gated by an unrelated 1.0.0 flag | independent review | nodes declared, flag honoured, gate removed | `UpgradeAndConfigTest`, MockBukkit plugin load |
| 19 | `exports.folder` accepted `..`/`.` | independent review | must not start with a dot | `legacyDiscordLayout...ExportFolderCannotEscape` |
| 20 | Compressed IPv6 addresses not scrubbed from Discord text | independent review | stricter pattern | `ipv6InAnyFormAndCoreProtectSecretsAreScrubbed` |
| 21 | Upgraded 1.0.0 servers would start posting routine warnings etc. to Discord; `max-retries` default changed | independent review | legacy layout posts only 1.0.0 events; default kept at 3 | `legacyDiscordLayoutDoesNotStartPostingNewEvents...` |
| 22 | CoreProtect snapshots could store `/login <password>` or IPs | independent review | redaction before storing | same test |
| 23 | Disabled Discord burned retry attempts; same-category warning count always 0; kicks allowed in `review.required-types`; delivered notifications never pruned; reason-only edit refused on an elapsed-but-unswept punishment | independent review | each fixed | `reasonOnlyChangeAndSameCategoryCounting`, config tests |
| 24 | `plugin.yml` notify-key edit produced invalid YAML (my own edit) | full test suite (`pluginLoadsUnderMockBukkit` + 40 dependent tests) | corrected before any commit of a release | suite |

## C. Not fixed / accepted (disclosed)

* Reversing through the review screen is two steps (revoke, then record the decision). If another reviewer changes the review in between, the punishment is already lifted and the review must be reopened to record the decision; the message says so. No data is lost.
* `warnings.repeat.recommend-case` is parsed but only the indicator is shown in the GUI.
* Markdown export escapes line breaks and mass mentions but does not neutralise `[text](url)` links typed by staff.
* Free-text fields (reasons, notes) are not scanned for IP addresses when exported; only Discord messages and CoreProtect snapshots are scrubbed.

No unresolved CRITICAL or HIGH defect is known at release.
