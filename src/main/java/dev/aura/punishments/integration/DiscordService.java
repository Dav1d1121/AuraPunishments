package dev.aura.punishments.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.service.DomainEvent;
import dev.aura.punishments.util.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Discord webhook notifications. Strictly best-effort and fully asynchronous: a slow, failing or
 * rate-limited Discord can never delay or block a moderation action. Mentions are disabled in every
 * message so staff-typed text cannot ping @everyone. Embeds never contain IP addresses or private notes.
 */
public class DiscordService {
    private record Job(String url, String json, int attempt) {}

    private final Supplier<Settings> settings;
    private final java.util.function.Predicate<String> urlCheck;
    private final Logger log;
    private final BlockingQueue<Job> queue = new LinkedBlockingQueue<>(500);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile boolean running = true;
    private volatile long lastWarn;
    private volatile String lastError;
    private volatile int sent;
    private volatile int failed;
    private final Thread worker;

    public DiscordService(Supplier<Settings> settings, Logger log) {
        this(settings, log, Settings::validWebhook);
    }

    /** @param urlCheck decides which webhook URLs may be used (production: https Discord webhooks only) */
    public DiscordService(Supplier<Settings> settings, Logger log, java.util.function.Predicate<String> urlCheck) {
        this.settings = settings;
        this.urlCheck = urlCheck;
        this.log = log;
        this.worker = new Thread(this::loop, "AuraPunishments-Discord");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public String status() {
        Settings s = settings.get();
        if (!s.discord.enabled()) return "disabled";
        return "enabled, sent=" + sent + " failed=" + failed + (lastError == null ? "" : " lastError=" + lastError) + " queued=" + queue.size();
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

    public void send(DomainEvent e) {
        Settings s = settings.get();
        if (!s.discord.enabled()) return;
        Settings.DiscordEvent ev = s.discord.events().get(e.type().discordKey);
        if (ev != null && !ev.enabled()) return;
        String url = ev != null && ev.webhook() != null && !ev.webhook().isBlank() ? ev.webhook() : s.discord.webhook();
        if (url == null || !urlCheck.test(url)) return;
        String json = body(s, e);
        if (!queue.offer(new Job(url, json, 0))) warn("Discord queue is full; dropping a notification");
    }

    static String body(Settings s, DomainEvent e) {
        JsonObject root = new JsonObject();
        if (!s.discord.username().isBlank()) root.addProperty("username", Text.truncate(s.discord.username(), 80));
        if (!s.discord.avatar().isBlank() && s.discord.avatar().startsWith("https://")) root.addProperty("avatar_url", s.discord.avatar());
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray()); // no @everyone / role / user pings, ever
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
        footer.addProperty("text", "AuraPunishments");
        embed.add("footer", footer);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        root.add("embeds", embeds);
        return root.toString();
    }

    private static String plain(String s) {
        return s.replace("@everyone", "@​everyone").replace("@here", "@​here");
    }

    private static void field(JsonArray fields, String name, String value, boolean inline) {
        if (value == null || value.isBlank() || fields.size() >= 20) return;
        JsonObject f = new JsonObject();
        f.addProperty("name", Text.truncate(name, 200));
        f.addProperty("value", Text.truncate(plain(value), 1000));
        f.addProperty("inline", inline);
        fields.add(f);
    }

    private void loop() {
        while (running) {
            try {
                Job job = queue.poll(1, TimeUnit.SECONDS);
                if (job == null) continue;
                deliver(job);
                Thread.sleep(450); // stay well inside Discord's webhook rate limit
            } catch (InterruptedException ie) {
                return;
            } catch (RuntimeException ex) {
                warn("Discord worker error: " + ex);
            }
        }
    }

    private void deliver(Job job) throws InterruptedException {
        Settings s = settings.get();
        int max = Math.max(0, s.discord.maxRetries());
        int attempt = job.attempt();
        while (running) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(target(job.url())))
                        .timeout(Duration.ofSeconds(s.discord.timeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(job.json())).build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code >= 200 && code < 300) {
                    sent++;
                    lastError = null;
                    return;
                }
                if (code == 429 && attempt < max + 3) {
                    long waitMs = 2000;
                    try {
                        waitMs = (long) (JsonParser.parseString(res.body()).getAsJsonObject().get("retry_after").getAsDouble() * 1000) + 100;
                    } catch (RuntimeException ignored) {
                        // fall back to the default wait
                    }
                    Thread.sleep(Math.min(waitMs, 30_000));
                    attempt++;
                    continue;
                }
                if (code >= 500 && attempt < max) {
                    Thread.sleep(1000L << Math.min(attempt, 5));
                    attempt++;
                    continue;
                }
                failed++;
                lastError = "HTTP " + code;
                warn("Discord webhook returned HTTP " + code + "; notification dropped (moderation is unaffected)");
                return;
            } catch (java.io.IOException | RuntimeException ex) {
                if (attempt < max) {
                    Thread.sleep(1000L << Math.min(attempt, 5));
                    attempt++;
                    continue;
                }
                failed++;
                lastError = ex.getClass().getSimpleName();
                warn("Discord webhook failed (" + ex.getClass().getSimpleName() + "); notification dropped (moderation is unaffected)");
                return;
            }
        }
    }

    private void warn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 60_000) {
            lastWarn = now;
            log.warning(msg);
        }
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
    }
}
