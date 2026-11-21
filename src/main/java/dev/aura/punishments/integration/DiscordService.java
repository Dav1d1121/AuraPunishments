package dev.aura.punishments.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Database;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.Records.OutboxRow;
import dev.aura.punishments.model.Simple.OutboxState;
import dev.aura.punishments.service.DomainEvent;
import dev.aura.punishments.util.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Discord webhook logging (webhooks only: no DMs, no channel creation).
 *
 * <p>Events are routed to a <em>category</em> (punishment, judicial, custody, owner-review, direct-emergency,
 * appeal, administrative), each with its own webhook list, an enable switch and a fallback chain ending at the
 * default webhook. Per-event overrides may disable an event, re-categorise it or give it dedicated webhooks.
 *
 * <p>Every message is first written to a persisted outbox (one row per destination, deduplicated by event key)
 * and sent by a background worker, so a Discord outage, restart or rate limit never blocks a moderation action
 * and unsent review/custody events are recovered after a restart. Webhook URLs are never stored in the outbox
 * (only a destination token) and are resolved from the current configuration at send time. Mentions are
 * disabled except for explicitly configured role ids; embeds never carry IP addresses.
 */
public class DiscordService {
    /** Persistence used by the outbox. Production: SQLite; tests may use the in-memory variant. */
    public interface Store {
        /** @return false if a row with this dedupe key already exists. May complete asynchronously. */
        boolean enqueue(String dedupeKey, String category, String event, String dest, String body, long now) throws Exception;

        List<OutboxRow> due(long now, int limit) throws Exception;

        void markSent(long id, long now) throws Exception;

        void retryLater(long id, long next, String error) throws Exception;

        void markDead(long id, String error) throws Exception;

        long count(OutboxState st) throws Exception;

        void prune(long olderThan) throws Exception;

        int requeueDead(long now) throws Exception;

        List<OutboxRow> recentDead(int limit) throws Exception;
    }

    private static final java.util.Set<String> LEGACY_EVENTS = java.util.Set.of("case-created", "custody-started", "custody-released", "trial-scheduled", "verdict",
            "sentence", "direct-punishment", "emergency-action", "appeal", "reversal", "custody-alert");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");
    private static final Pattern IPV6 = Pattern.compile("(?<![0-9A-Za-z:])(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?![0-9A-Za-z:])");

    private final Supplier<Settings> settings;
    private final java.util.function.Predicate<String> urlCheck;
    private final Logger log;
    private final Store store;
    private final java.util.function.LongSupplier clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile boolean running = true;
    private volatile long lastWarn;
    private volatile String lastError;
    private volatile int sent;
    private volatile int failed;
    private volatile int unrouted;
    private volatile long pausedUntil;
    private volatile Consumer<String> adminAlert = m -> { };
    private final Thread worker;
    private long lastPrune;

    public DiscordService(Supplier<Settings> settings, Logger log) {
        this(settings, log, Settings::validWebhook, new MemoryStore(), System::currentTimeMillis);
    }

    public DiscordService(Supplier<Settings> settings, Logger log, Database db) {
        this(settings, log, Settings::validWebhook, new DbStore(db), System::currentTimeMillis);
    }

    /** @param urlCheck decides which webhook URLs may be used (production: https Discord webhooks only) */
    public DiscordService(Supplier<Settings> settings, Logger log, java.util.function.Predicate<String> urlCheck) {
        this(settings, log, urlCheck, new MemoryStore(), System::currentTimeMillis);
    }

    public DiscordService(Supplier<Settings> settings, Logger log, java.util.function.Predicate<String> urlCheck, Store store,
                          java.util.function.LongSupplier clock) {
        this.settings = settings;
        this.urlCheck = urlCheck;
        this.log = log;
        this.store = store;
        this.clock = clock;
        this.worker = new Thread(this::loop, "AuraPunishments-Discord");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Called (rate-limited) when a message is permanently undeliverable, e.g. a revoked webhook. */
    public void onAdminAlert(Consumer<String> sink) {
        this.adminAlert = sink == null ? m -> { } : sink;
    }

    public String status() {
        Settings s = settings.get();
        if (!s.discord.enabled()) return "disabled";
        String pending;
        try {
            pending = store.count(OutboxState.PENDING) + " pending, " + store.count(OutboxState.DEAD) + " undeliverable";
        } catch (Exception e) {
            pending = "outbox unavailable";
        }
        return "enabled, sent=" + sent + " dead=" + failed + " unrouted=" + unrouted + " (" + pending + ")" + (lastError == null ? "" : " lastError=" + lastError);
    }

    /** Transport hook (tests redirect to a local server). Production sends to the configured URL unchanged. */
    protected String target(String configuredUrl) {
        return configuredUrl;
    }

    public int sentCount() {
        return sent;
    }

    public int failedCount() {
        return failed;
    }

    public int unroutedCount() {
        return unrouted;
    }

    public Store store() {
        return store;
    }

    // ------------------------------------------------------------------ routing
    /** The destination tokens an event is delivered to under the given settings (empty = not delivered). */
    public static List<String> route(Settings s, DomainEvent e) {
        List<String> out = new ArrayList<>();
        if (!s.discord.enabled()) return out;
        Settings.DiscordEvent ev = s.discord.events().get(e.type().discordKey);
        if (ev != null && !ev.enabled()) return out;
        // Upgraded install (no discord.channels section): events that did not exist in 1.0.0 stay off until the admin lists them under discord.events.
        if (s.discord.legacyLayout() && ev == null && !LEGACY_EVENTS.contains(e.type().discordKey)) return out;
        String cat = ev != null && !ev.category().isEmpty() ? ev.category() : e.type().category;
        if (ev != null && !ev.webhooks().isEmpty()) {
            for (int i = 0; i < ev.webhooks().size(); i++) out.add("ev:" + e.type().discordKey + "#" + i);
            return out;
        }
        String cur = cat;
        int hops = 0;
        while (!cur.equals("default") && hops++ <= Settings.DISCORD_CATEGORIES.size()) {
            Settings.DiscordChannel ch = s.discord.channels().get(cur);
            if (ch == null) break;
            if (!ch.enabled()) return out;
            if (!ch.webhooks().isEmpty()) {
                for (int i = 0; i < ch.webhooks().size(); i++) out.add("cat:" + cur + "#" + i);
                return out;
            }
            cur = ch.fallback();
        }
        if (s.discord.webhook() != null && !s.discord.webhook().isBlank()) out.add("default#0");
        return out;
    }

    static String resolve(Settings s, String dest) {
        try {
            int hash = dest.lastIndexOf('#');
            int idx = Integer.parseInt(dest.substring(hash + 1));
            String head = dest.substring(0, hash);
            List<String> urls;
            if (head.equals("default")) urls = List.of(s.discord.webhook());
            else if (head.startsWith("cat:")) urls = s.discord.channels().get(head.substring(4)).webhooks();
            else urls = s.discord.events().get(head.substring(3)).webhooks();
            return idx < urls.size() ? urls.get(idx) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public void send(DomainEvent e) {
        Settings s = settings.get();
        if (!s.discord.enabled()) return;
        List<String> dests = route(s, e);
        if (dests.isEmpty()) {
            Settings.DiscordEvent ev = s.discord.events().get(e.type().discordKey);
            boolean intentionallyOff = ev != null && !ev.enabled();
            if (!intentionallyOff && !channelOff(s, e)) unrouted++;
            return;
        }
        String body = body(s, e);
        String base = e.key() != null ? e.key() : e.type().discordKey + ":" + UUID.randomUUID();
        long now = clock.getAsLong();
        for (String d : dests) {
            try {
                store.enqueue(base + "@" + d, e.type().category, e.type().discordKey, d, body, now);
            } catch (Exception ex) {
                warn("Discord outbox could not store a notification (" + ex.getClass().getSimpleName() + "); moderation is unaffected");
            }
        }
    }

    private static boolean channelOff(Settings s, DomainEvent e) {
        Settings.DiscordEvent ev = s.discord.events().get(e.type().discordKey);
        String cat = ev != null && !ev.category().isEmpty() ? ev.category() : e.type().category;
        Settings.DiscordChannel ch = s.discord.channels().get(cat);
        return ch != null && !ch.enabled();
    }

    // ------------------------------------------------------------------ rendering
    public static String body(Settings s, DomainEvent e) {
        JsonObject root = new JsonObject();
        if (!s.discord.username().isBlank()) root.addProperty("username", Text.truncate(s.discord.username(), 80));
        if (!s.discord.avatar().isBlank() && s.discord.avatar().startsWith("https://")) root.addProperty("avatar_url", s.discord.avatar());
        JsonObject allowed = new JsonObject();
        JsonArray parse = new JsonArray(); // never @everyone / @here / arbitrary users
        JsonArray roles = new JsonArray();
        allowed.add("parse", parse);
        Settings.DiscordEvent ev = s.discord.events().get(e.type().discordKey);
        String cat = ev != null && !ev.category().isEmpty() ? ev.category() : e.type().category;
        Settings.DiscordChannel ch = s.discord.channels().get(cat);
        if (ch != null && !ch.mentionRoles().isEmpty() && ch.mentionEvents().contains(e.type().discordKey)) {
            StringBuilder c = new StringBuilder();
            for (String r : ch.mentionRoles()) {
                roles.add(r);
                c.append("<@&").append(r).append("> ");
            }
            root.addProperty("content", c.toString().trim());
            allowed.add("roles", roles);
        }
        root.add("allowed_mentions", allowed);
        JsonObject embed = new JsonObject();
        String ref = e.caseId() == null ? "" : " - AP-" + String.format("%04d", e.caseId());
        embed.addProperty("title", Text.truncate(e.type().title + ref, 250));
        if (!e.summary().isBlank()) embed.addProperty("description", Text.truncate(plain(e.summary()), 1800));
        embed.addProperty("color", e.type().color);
        embed.addProperty("timestamp", Instant.now().toString());
        JsonArray fields = new JsonArray();
        if (!e.player().isBlank()) field(fields, "Player", e.player(), true);
        if (!e.actor().isBlank()) field(fields, "By", e.actor(), true);
        for (Map.Entry<String, String> f : e.fields().entrySet()) field(fields, f.getKey(), f.getValue(), false);
        embed.add("fields", fields);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "AuraPunishments - " + cat);
        embed.add("footer", footer);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        root.add("embeds", embeds);
        return root.toString();
    }

    /** Defence in depth: IP addresses never leave the server, whatever a caller put into a field. */
    static String plain(String s) {
        String t = IPV4.matcher(s).replaceAll("[ip hidden]");
        t = IPV6.matcher(t).replaceAll("[ip hidden]");
        return t.replace("@everyone", "@​everyone").replace("@here", "@​here");
    }

    private static void field(JsonArray fields, String name, String value, boolean inline) {
        if (value == null || value.isBlank() || fields.size() >= 20) return;
        JsonObject f = new JsonObject();
        f.addProperty("name", Text.truncate(name, 200));
        f.addProperty("value", Text.truncate(plain(value), 1000));
        f.addProperty("inline", inline);
        fields.add(f);
    }

    // ------------------------------------------------------------------ delivery
    private void loop() {
        while (running) {
            try {
                long now = clock.getAsLong();
                if (now < pausedUntil) {
                    Thread.sleep(Math.min(500, pausedUntil - now));
                    continue;
                }
                housekeeping(now);
                List<OutboxRow> rows = store.due(now, 10);
                if (rows.isEmpty()) {
                    Thread.sleep(250);
                    continue;
                }
                for (OutboxRow r : rows) {
                    if (!running) return;
                    deliver(r);
                    Thread.sleep(350); // stay well inside Discord's webhook rate limit
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Exception ex) {
                warn("Discord worker error: " + ex.getClass().getSimpleName());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void housekeeping(long now) throws Exception {
        if (now - lastPrune < 3_600_000L) return;
        lastPrune = now;
        store.prune(now - settings.get().discord.retentionDays() * 86_400_000L);
    }

    private void deliver(OutboxRow row) throws Exception {
        Settings s = settings.get();
        long now = clock.getAsLong();
        String url = resolve(s, row.dest());
        if (!s.discord.enabled()) { // switched off after queueing: keep the row for when it is switched back on
            pausedUntil = now + 60_000; // keep the row untouched (no attempt burned) and look again in a minute
            return;
        }
        if (url == null || url.isBlank() || !urlCheck.test(url)) {
            dead(row, "destination no longer configured");
            return;
        }
        int max = Math.max(0, s.discord.maxRetries());
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(target(url)))
                    .timeout(Duration.ofSeconds(s.discord.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(row.body())).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code >= 200 && code < 300) {
                sent++;
                lastError = null;
                store.markSent(row.id(), now);
                return;
            }
            if (code == 429) { // rate limited: honour retry_after for the whole queue, does not consume an attempt budget
                long waitMs = 2000;
                try {
                    waitMs = (long) (JsonParser.parseString(res.body()).getAsJsonObject().get("retry_after").getAsDouble() * 1000) + 100;
                } catch (RuntimeException ignored) {
                    // default wait
                }
                waitMs = Math.min(waitMs, 60_000);
                pausedUntil = now + waitMs;
                store.retryLater(row.id(), now + waitMs, "rate limited");
                return;
            }
            if (code >= 500) {
                retry(row, max, "HTTP " + code);
                return;
            }
            dead(row, "HTTP " + code + (code == 404 || code == 401 ? " (webhook deleted or invalid)" : ""));
        } catch (java.io.IOException | RuntimeException ex) {
            retry(row, max, ex.getClass().getSimpleName());
        }
    }

    private void retry(OutboxRow row, int max, String error) throws Exception {
        long now = clock.getAsLong();
        lastError = error;
        if (row.attempts() + 1 > max) {
            dead(row, error + " after " + (row.attempts() + 1) + " attempts");
            return;
        }
        long base = settings.get().discord.backoffBaseMs();
        store.retryLater(row.id(), now + Math.min(3_600_000L, base << Math.min(row.attempts(), 10)), error);
    }

    private void dead(OutboxRow row, String error) throws Exception {
        failed++;
        lastError = error;
        store.markDead(row.id(), error);
        warn("Discord notification '" + row.event() + "' could not be delivered: " + error + " (moderation is unaffected; /ap discord retry re-queues it)");
        adminAlert.accept("Discord delivery to '" + row.dest().replaceAll("#\\d+$", "") + "' failed: " + error);
    }

    private void warn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 60_000) {
            lastWarn = now;
            log.warning(msg);
        }
    }

    public int requeueDead() {
        try {
            return store.requeueDead(clock.getAsLong());
        } catch (Exception e) {
            return 0;
        }
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
    }

    // ------------------------------------------------------------------ stores
    /** SQLite-backed outbox: survives restarts, so unsent review/custody events are recovered. */
    public static final class DbStore implements Store {
        private final Database db;

        public DbStore(Database db) {
            this.db = db;
        }

        @Override
        public boolean enqueue(String k, String category, String event, String dest, String body, long now) {
            db.tx(c -> Repo.Outbox.enqueue(c, k, category, event, dest, body, now)); // async: never blocks the caller
            return true;
        }

        @Override
        public List<OutboxRow> due(long now, int limit) throws Exception {
            return db.txSync(c -> Repo.Outbox.due(c, now, limit));
        }

        @Override
        public void markSent(long id, long now) throws Exception {
            db.txSync(c -> {
                Repo.Outbox.markSent(c, id, now);
                return null;
            });
        }

        @Override
        public void retryLater(long id, long next, String error) throws Exception {
            db.txSync(c -> {
                Repo.Outbox.retryLater(c, id, next, error);
                return null;
            });
        }

        @Override
        public void markDead(long id, String error) throws Exception {
            db.txSync(c -> {
                Repo.Outbox.markDead(c, id, error);
                return null;
            });
        }

        @Override
        public long count(OutboxState st) throws Exception {
            return db.txSync(c -> Repo.Outbox.count(c, st));
        }

        @Override
        public void prune(long olderThan) throws Exception {
            db.txSync(c -> Repo.Outbox.prune(c, olderThan));
        }

        @Override
        public int requeueDead(long now) throws Exception {
            return db.txSync(c -> Repo.Outbox.requeueDead(c, now));
        }

        @Override
        public List<OutboxRow> recentDead(int limit) throws Exception {
            return db.txSync(c -> Repo.Outbox.recentDead(c, limit));
        }
    }

    /** Non-persistent store for unit tests and database-less use. */
    public static final class MemoryStore implements Store {
        private final List<OutboxRow> rows = new ArrayList<>();
        private long seq;

        @Override
        public synchronized boolean enqueue(String k, String category, String event, String dest, String body, long now) {
            for (OutboxRow r : rows) if (r.dedupeKey().equals(k)) return false;
            rows.add(new OutboxRow(++seq, k, category, event, dest, body, OutboxState.PENDING, 0, now, now, null, null));
            return true;
        }

        private synchronized void replace(long id, java.util.function.UnaryOperator<OutboxRow> f) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id() == id) rows.set(i, f.apply(rows.get(i)));
        }

        @Override
        public synchronized List<OutboxRow> due(long now, int limit) {
            List<OutboxRow> out = new ArrayList<>();
            for (OutboxRow r : rows) if (r.state() == OutboxState.PENDING && r.nextAttempt() <= now && out.size() < limit) out.add(r);
            return out;
        }

        @Override
        public void markSent(long id, long now) {
            replace(id, r -> new OutboxRow(r.id(), r.dedupeKey(), r.category(), r.event(), r.dest(), r.body(), OutboxState.SENT, r.attempts() + 1, r.nextAttempt(), r.createdAt(), now, null));
        }

        @Override
        public void retryLater(long id, long next, String error) {
            replace(id, r -> new OutboxRow(r.id(), r.dedupeKey(), r.category(), r.event(), r.dest(), r.body(), r.state(), r.attempts() + 1, next, r.createdAt(), r.sentAt(), error));
        }

        @Override
        public void markDead(long id, String error) {
            replace(id, r -> new OutboxRow(r.id(), r.dedupeKey(), r.category(), r.event(), r.dest(), r.body(), OutboxState.DEAD, r.attempts() + 1, r.nextAttempt(), r.createdAt(), r.sentAt(), error));
        }

        @Override
        public synchronized long count(OutboxState st) {
            return rows.stream().filter(r -> r.state() == st).count();
        }

        @Override
        public synchronized void prune(long olderThan) {
            rows.removeIf(r -> r.state() != OutboxState.PENDING && r.createdAt() < olderThan);
        }

        @Override
        public synchronized int requeueDead(long now) {
            int n = 0;
            for (int i = 0; i < rows.size(); i++) {
                OutboxRow r = rows.get(i);
                if (r.state() == OutboxState.DEAD) {
                    rows.set(i, new OutboxRow(r.id(), r.dedupeKey(), r.category(), r.event(), r.dest(), r.body(), OutboxState.PENDING, 0, now, r.createdAt(), null, null));
                    n++;
                }
            }
            return n;
        }

        @Override
        public synchronized List<OutboxRow> recentDead(int limit) {
            List<OutboxRow> out = new ArrayList<>();
            for (OutboxRow r : rows) if (r.state() == OutboxState.DEAD) out.add(r);
            return out.subList(Math.max(0, out.size() - limit), out.size());
        }
    }
}
