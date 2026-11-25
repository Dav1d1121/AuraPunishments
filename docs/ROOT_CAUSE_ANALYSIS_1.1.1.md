> **Update (1.2.0):** the chat-capture fix described below (1.1.1) did not satisfy the server owner in live use, so chat input was removed altogether: all text entry is now a dialog window and the plugin never reads chat for input. This document is kept as the analysis of why chat capture is fragile.

# AuraPunishments 1.1.1 - Root-cause analysis: chat input (prompt answers) not private

## Symptom
After a staff member clicked a GUI button that asks for text (a reason, a note, evidence, a case description, ...), AuraPunishments told them to "type your answer in chat". The reported behaviour was that what they typed was treated as ordinary chat. That is a functional defect (the input is not used) and a confidentiality defect (private reasoning and evidence references are exposed).

## What the prompt system did (1.0.0 - 1.1.0)
`PromptManager` kept one session per staff member and captured the next chat line from that player, but it only listened to Paper's **modern** `AsyncChatEvent` at `LOWEST` priority.

## Root cause (proven on a live server)
Paper fires **two** events for one chat line whenever any plugin listens to the legacy `AsyncPlayerChatEvent` - and TownyChat, EssentialsX Chat, DiscordSRV, CoreProtect and most chat managers do. The order is:

1. the legacy `AsyncPlayerChatEvent`, at every priority (`LOWEST` ... `MONITOR`), **then**
2. the modern `AsyncChatEvent`, at every priority.

Measured on Paper 26.3 with TownyChat 0.122 present (probe listeners at every priority; `docs/evidence/live-results-1.1.1-chat-input-phase-7-BEFORE-fix-on-1.1.0.txt`):

```
legacy@LOWEST cancelled=false ... legacy@MONITOR cancelled=false   <- every legacy listener saw the line, uncancelled
modern@LOWEST cancelled=true  ... modern@MONITOR cancelled=true    <- AuraPunishments cancelled it here, too late
```

So by the time AuraPunishments captured and cancelled the answer, every plugin that works on the legacy event - chat channel plugins that format and deliver, chat relays such as DiscordSRV (legacy `MONITOR`, `ignoreCancelled`), chat loggers - had already received it as an ordinary, uncancelled chat line. Whether that ends up as visible public chat, a Discord relay line or a log entry depends on the chat plugin stack; on the production stack it appeared as normal chat.

Additional contributing causes found while investigating:

* **Mute enforcement had the same hole.** `ChatListener` also listened only to the modern event, so a muted player's line reached legacy listeners (Discord relays, channel plugins that act on the legacy event) before it was cancelled.
* **Listener order at equal priority.** Among listeners with the same priority, the plugin registered first runs first. AuraPunishments listed TownyChat, EssentialsX and DiscordSRV as soft dependencies, which made it enable *after* them, so their `LOWEST` handlers ran before ours.
* **CoreProtect logs cancelled chat by default** (`log-cancelled-chat: true`), so cancelling is not enough to keep an answer out of its chat log. (Reproduced: the answer appeared in `chatLookup`.)
* **Not a cause (checked):** registration (the listener was registered), Java 25 / Paper 26.3 compatibility, event priority naming, and the prompt/session logic itself (sessions were created and keyed per player correctly). On a stack without a legacy-event consumer the 1.1.0 code worked, which is why automated tests (MockBukkit fires one event) and the earlier live runs did not catch it.

## Why testing missed it
MockBukkit's `Player.chat()` fires a single event, the earlier live runs used server-side test players calling `Player.chat()` with no chat relay/logger installed, and TownyChat's *general* channel hands delivery back to the server (so a cancelled modern event hid the problem). The live run that exposed it used real protocol clients, a real Towny town channel, and monitoring listeners standing in for DiscordSRV/loggers.

## The fix (1.1.1)
1. **Capture the legacy event first.** `PromptManager` now listens to `AsyncPlayerChatEvent` at `LOWEST` (it is the first event of the line), cancels it and clears its recipients, and starts the input handling there.
2. **Recognise the twin.** The modern event of the same line is matched as its twin (opposite event type, same text; a single outstanding line of the other type is also accepted if a chat plugin rewrote the text) and is only silenced - never processed twice. Matching works in either order, so servers that fire the events in another order or thread (MockBukkit does, and exposed a race in my first version) behave the same. If no legacy listener exists the modern event captures on its own.
3. **Last word at `MONITOR`.** A captured line is forced cancelled and given no recipients/viewers again at `MONITOR`, in case a plugin un-cancelled it in between.
4. **Mute enforcement** gets the identical treatment (legacy first, one notice per line, `MONITOR` guard).
5. **Load order.** `plugin.yml` now declares `loadbefore: [Essentials, EssentialsChat, EssentialsXChat, TownyChat, DiscordSRV, ChatControlRed, CarbonChat, Chatty, VentureChat, HeroChat, LPC, UltimateChat]` and no longer lists Essentials, TownyChat or DiscordSRV as soft dependencies, so our `LOWEST` handlers run before theirs.
6. **Session hygiene.** Sessions are removed on disconnect together with their twin bookkeeping; `cancelAll()` (plugin disable) now tells each affected player that nothing was changed.
7. **CoreProtect advisory.** At startup and in `/ap status`, AuraPunishments warns when CoreProtect's `log-cancelled-chat` is `true` and tells the administrator how to turn it off (see below). AuraPunishments cannot change another plugin's configuration.

Unchanged and re-verified: sessions are per staff member; services re-check permissions when the action is committed (a permission revoked after typing prevents the action); cancel word, timeout, "run a command to cancel", validation messages and return-to-GUI behaviour.

## Residual limits (disclosed)
* A plugin that handles a chat line **before** AuraPunishments at `LOWEST` (it would have to register first despite `loadbefore`, or use a packet-level hook such as ProtocolLib) can still see it. Packet-level interception was considered and rejected: dropping signed chat packets desynchronises the client's chat-session acknowledgements and risks kicking players.
* **CoreProtect** records cancelled chat unless `log-cancelled-chat: false` is set in its own config.
* DiscordSRV was installed in the live stack but was not connected to Discord (no bot token, no network); forwarding was verified with a `MONITOR` probe that behaves like a relay, not with real Discord traffic.
* I could not reproduce *public* broadcast on the available stack (TownyChat general and town channels, EssentialsX Chat); I reproduced delivery of every answer to legacy listeners, which is the mechanism by which any legacy-driven chat plugin would broadcast it. If a specific chat plugin on the production server still shows an answer after 1.1.1, send its name and version.
