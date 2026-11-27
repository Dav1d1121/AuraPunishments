# AuraPunishments 1.3.0 - Discord webhook setup

AuraPunishments logs to Discord **only through webhooks you create**. It never sends direct messages, never creates channels, and a Discord outage can never delay or block a punishment: messages are saved in the plugin's database first and sent in the background, with retries.

## 1. Create the webhooks (in Discord)

For each log channel you want: *Channel settings -> Integrations -> Webhooks -> New Webhook -> Copy Webhook URL*. A typical layout:

| Category | What it logs | Suggested channel |
|---|---|---|
| `punishment` | Routine warnings, quick punishments, reversals, modifications | `#punishment-log` |
| `judicial` | Cases opened, status changes, trials scheduled/postponed, verdicts, sentences | `#judicial-log` |
| `custody` | Custody **requested** (attempt), **confirmed**, **failed**, released, review-deadline alerts | `#custody-log` |
| `owner-review` | Quick-punishment reviews: requested, decisions, evidence updates, reminders, overdue | `#owner-review` |
| `direct-emergency` | Direct punishments and emergency protective actions | `#senior-staff` |
| `appeal` | Appeals filed/decided, Ticket Tool ticket references | `#appeal-log` |
| `administrative` | Reloads, exports, integration problems, refused elevated actions | `#admin-log` |

## 2. Put the URLs somewhere safe

Never paste a webhook URL into a screenshot or a public repository. Three options (combine freely):

1. `plugins/AuraPunishments/webhooks.yml` (not shipped; create it, give it tight file permissions):
   ```yaml
   default: "https://discord.com/api/webhooks/..."
   judicial: ["https://discord.com/api/webhooks/..."]
   custody:  ["https://discord.com/api/webhooks/...", "https://discord.com/api/webhooks/..."]   # several = same events to several channels
   owner-review: "https://discord.com/api/webhooks/..."
   ```
2. In `config.yml`: `webhooks: ["${env:AP_HOOK_JUDICIAL}"]` - the URL comes from an environment variable.
3. In `config.yml` directly (only if the file is private).

Webhook URLs are checked (must be `https://discord.com/api/webhooks/...`), are never printed to the console, GUIs or Discord, and are not stored in the outbox - only a destination name is, and the URL is looked up when the message is sent.

## 3. Turn it on

```yaml
discord:
  enabled: true
  channels:
    custody: { enabled: true, webhooks: [], fallback: default, mention-roles: ["123456789012345678"], mention-events: [custody-alert, custody-failed] }
  events:
    warning: { enabled: true }                 # per-event: enable/disable, move to another category, or give it its own webhooks
    export:  { enabled: false }
```
`/ap reload` validates everything first (bad URLs, unknown categories, fallback loops, malformed role ids are all listed); the old configuration stays active if anything is wrong.

* **Fallbacks:** a category with no webhook uses its `fallback` (another category or `default`). A disabled category stays silent.
* **Mentions:** `@everyone`/`@here`/user mentions are always off. Only the numeric role ids you list in `mention-roles` are pinged, and only for the events in `mention-events`.
* **Privacy:** embeds never contain IP addresses (they are scrubbed even from free text), private notes, or evidence contents.
* **Upgraded servers:** with a 1.0.0-style `discord:` block, only the 1.0.0 events are posted until you add `discord.channels` or list an event under `discord.events`.

## 4. Operating it

* `/ap discord` - delivery status (sent / undeliverable / pending). `/ap discord retry` - re-queue messages that were given up on (for example after fixing a deleted webhook).
* Failures are retried with growing delays (`discord.max-retries`, `discord.retry-backoff-seconds`); Discord's rate limits (HTTP 429) are honoured; messages that can never be delivered (revoked webhook, HTTP 4xx) are marked undeliverable and online admins are told, at most once a minute.
* The same event is never posted twice to the same destination (each message has a stable key), including across restarts. Review events that could not be sent are kept and sent after Discord recovers or the server restarts.
* Delivered/undeliverable rows are pruned after `discord.outbox-retention-days`.

## 5. Testing your webhooks

Check each channel after configuring: issue a warning to a test account, create and detain-then-acquit a test case, and look for the embeds. Test results from this development environment, which cannot reach discord.com, are in [TEST_REPORT.md](TEST_REPORT.md): routing, retries, rate limits, outages and de-duplication were verified against a local stand-in; **real Discord delivery must be confirmed on your server.**

## 1.3.0 - punishment notifications: private staff log and optional public announcements

Every punishment event can carry structured facts (type, expiry, issuing server, evidence count...). Two optional blocks in `config.yml` decide what is posted:

```yaml
discord:
  enabled: true
  punishment-log:                      # PRIVATE staff log (on by default - it is what the "punishment" channel already shows)
    enabled: true
    webhooks: []                       # empty = use channels.punishment routing; or list its own webhook(s)
    types: [WARN, KICK, MUTE, BAN, IPBAN, JAIL]
    events: [issued, modified, revoked, expired, appeal, judicial]
    fields: [player, type, reason, duration, expires, issuer, punishment-id, server, evidence, source]   # also: network-status, case
  public-log:                          # OPTIONAL public announcements (OFF by default)
    enabled: false
    webhooks: ["${env:AP_PUBLIC_HOOK}"]  # required when enabled; never falls back to the private webhooks
    types: [BAN, IPBAN]
    events: [issued]
    fields: [player, type, duration]    # only these can ever appear: player, type, reason, duration, expires, server
    show-reason: false
```

* `events`: `issued` (any punishment began: quick/standard, direct, emergency, sentence, jail), `modified` (owner review / appeal changed it), `revoked`, `expired` (the time ran out; *new in 1.3.0 - upgraded installs that still use the 1.0.0 layout do not receive it until opted in under `discord.events`*), `appeal` (filed / ticket linked / decided), `judicial` (cases, hearings, verdicts, sentences, custody; also governed by `channels.judicial` / `channels.custody`).
* `fields` of the staff log: `player`, `type`, `reason`, `duration`, `expires` (a Discord timestamp), `issuer`, `punishment-id`, `server` (the originating server name), `evidence` (count), `source` (standard / quick / judicial / direct / emergency), `network-status` ("this server only" in 1.3.0; the network release reports enforcement status here), `case`. Fields the event carries beyond these (classification, justification, reasoning) are kept.
* **The public log is sanitized by construction**: the renderer reads only player, type, duration/expiry, server and (if `show-reason`) the reason. It cannot show the issuing staff member, evidence, notes, internal ids, case numbers or addresses, and the config refuses `issuer`/`evidence`/`punishment-id`/... in its `fields`. No pings (`allowed_mentions` empty). IP addresses are scrubbed from every message anyway.
* No duplicates: each fact is queued once per destination (a persisted key), whatever is retried or re-emitted; failed deliveries retry with back-off and honour Discord rate limits; a Discord outage never delays or blocks a punishment.
* Judicial events are posted only while there are any (nothing is generated when the judicial system is off).
