# AuraPunishments changelog

## 1.1.1 - chat input hotfix
**Fixed**
* Text typed in answer to a GUI prompt (punishment/warning reasons, evidence references, case descriptions and notes, verdict and sentencing reasoning, review notes, durations, player names, ...) could be seen by other plugins as ordinary chat - and therefore broadcast, relayed to Discord or logged - because AuraPunishments only captured Paper's *modern* chat event, which Paper fires **after** the legacy one. Prompt answers are now captured at the first event of the line, silenced for every later listener, and processed exactly once. See [ROOT_CAUSE_ANALYSIS_1.1.1.md](ROOT_CAUSE_ANALYSIS_1.1.1.md).
* A muted player's chat line reached legacy-event plugins before the mute cancelled it. Mutes now stop the line at the first event; one notice per line.
* A late event of an earlier chat line could cancel or answer the *next* prompt (found by an intermittent test failure; 14 of 80 runs). Twin matching now works in both event orders.
* Plugin disable/shutdown now tells staff with an open prompt that nothing was changed.

**Changed**
* `plugin.yml`: AuraPunishments loads **before** common chat plugins (`loadbefore`) and no longer soft-depends on Essentials, TownyChat or DiscordSRV, so its chat handlers run first at equal priority.
* New startup / `/ap status` warning when CoreProtect is configured to log cancelled chat.

**Not changed** (verified): database schema (still v2), `config.yml`, `messages.yml`, permission nodes and defaults, stored punishment records, judicial workflows, Discord routing.

**Tests:** 238 automated tests (13 new chat-input tests, 7 of which fail on 1.1.0); live protocol-client run of every prompt workflow 82/82; live regression phases 1, 2, 3, 6 pass.

## 1.1.0
See [RELEASE_NOTES.md](RELEASE_NOTES.md).
