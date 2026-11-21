# AuraPunishments 1.1.0 - Discord webhook setup

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
