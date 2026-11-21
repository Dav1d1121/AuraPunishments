package dev.aura.punishments;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.integration.DiscordService;
import dev.aura.punishments.service.DomainEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Discord is best-effort: failures, slowness and rate limits must never block or break moderation. */
class DiscordServiceTest {
    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile IntSupplier statusFor = () -> 204;
    private volatile long delayMs = 0;
    private DiscordService discord;
    private String url;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/webhooks/1/abc", ex -> {
            requests.incrementAndGet();
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                // test shutdown
            }
            int code = statusFor.getAsInt();
            byte[] resp = code == 429 ? "{\"retry_after\":0.2}".getBytes() : new byte[0];
            ex.sendResponseHeaders(code, resp.length == 0 ? -1 : resp.length);
            if (resp.length > 0) ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/abc";
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(DiscordServiceTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        y.set("discord.enabled", true);
        y.set("discord.webhook-url", "https://discord.com/api/webhooks/1/abc");
        y.set("discord.max-retries", 3);
        y.set("discord.retry-backoff-seconds", 1);
        y.set("discord.timeout-seconds", 1);
        Settings s = Settings.parse(y);
        discord = new DiscordService(() -> s, Logger.getLogger("test")) {
            @Override
            protected String target(String configured) {
                return url; // talk to the local test server instead of discord.com
            }
        };
    }

    @AfterEach
    void stop() {
        discord.shutdown();
        server.stop(0);
    }

    private static DomainEvent event(String summary) {
        return DomainEvent.of(DomainEvent.Type.CASE_CREATED).caseId(12L).player("Steve").actor("Mod").summary(summary).field("Offense", "Harassment").build();
    }

    private void await(java.util.function.BooleanSupplier c) throws Exception {
        long end = System.currentTimeMillis() + 15000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(25);
    }

    @Test
    void successfulDeliveryHasNoMentionsAndNoSensitiveData() throws Exception {
        discord.send(event("Spam <@&123> @everyone check https://x.example"));
        await(() -> discord.sentCount() == 1);
        assertEquals(1, discord.sentCount());
        JsonObject body = JsonParser.parseString(bodies.get(0)).getAsJsonObject();
        assertEquals(0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size(), "pings are disabled in every message");
        String json = bodies.get(0);
        assertFalse(json.contains("@everyone"), "@everyone is neutralised");
        assertTrue(json.contains("AP-0012"));
        assertFalse(json.matches("(?s).*\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b.*"), "no IP addresses");
    }

    @Test
    void serverErrorsAreRetriedThenSucceed() throws Exception {
        AtomicInteger n = new AtomicInteger();
        statusFor = () -> n.incrementAndGet() < 3 ? 500 : 204;
        discord.send(event("retry me"));
        await(() -> discord.sentCount() == 1);
        assertEquals(1, discord.sentCount());
        assertEquals(3, requests.get(), "two failures then success");
    }

    @Test
    void rateLimitsAreHonouredThenDelivered() throws Exception {
        AtomicInteger n = new AtomicInteger();
        statusFor = () -> n.incrementAndGet() == 1 ? 429 : 204;
        discord.send(event("rate limited"));
        await(() -> discord.sentCount() == 1);
        assertEquals(1, discord.sentCount());
    }

    @Test
    void permanentFailureIsDroppedQuietlyAndNeverThrows() throws Exception {
        statusFor = () -> 404;
        assertDoesNotThrow(() -> discord.send(event("gone")));
        await(() -> discord.failedCount() == 1);
        assertEquals(1, discord.failedCount());
        assertEquals(0, discord.sentCount());
        assertEquals(1, requests.get(), "a 404 is not retried");
        assertTrue(discord.status().contains("dead=1"));
    }

    @Test
    void aHangingOrDeadDiscordNeverBlocksTheCaller() throws Exception {
        delayMs = 5000; // far longer than the 1s request timeout
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) discord.send(event("event " + i));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 250, "20 sends returned in " + ms + "ms (the caller is never blocked)");
        server.stop(0);   // and a completely dead endpoint
        assertDoesNotThrow(() -> discord.send(event("dead")));
    }

    @Test
    void disabledOrEventDisabledSendsNothing() throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(DiscordServiceTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        y.set("discord.enabled", false);
        Settings off = Settings.parse(y);
        DiscordService d2 = new DiscordService(() -> off, Logger.getLogger("test"));
        d2.send(event("x"));
        Thread.sleep(300);
        assertEquals(0, requests.get());
        d2.shutdown();
    }

    // ------------------------------------------------------------------ 1.1.0 routing / outbox
    private static YamlConfiguration cfg() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(DiscordServiceTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        y.set("discord.enabled", true);
        return y;
    }

    private static final String A = "https://discord.com/api/webhooks/1/aaa";
    private static final String B = "https://discord.com/api/webhooks/2/bbb";
    private static final String C = "https://discord.com/api/webhooks/3/ccc";

    private static DomainEvent ev(DomainEvent.Type t) {
        return DomainEvent.of(t).caseId(5L).player("Steve").actor("Mod").summary("x").build();
    }

    @Test
    void eventsRouteToTheirCategoryWithFallbackToTheDefault() throws Exception {
        YamlConfiguration y = cfg();
        y.set("discord.webhook-url", A);
        y.set("discord.channels.custody.webhooks", List.of(B));
        y.set("discord.channels.appeal.fallback", "custody");
        Settings s = Settings.parse(y);
        assertEquals(List.of("cat:custody#0"), DiscordService.route(s, ev(DomainEvent.Type.CUSTODY_STARTED)));
        assertEquals(List.of("default#0"), DiscordService.route(s, ev(DomainEvent.Type.VERDICT)), "judicial has no webhook: default");
        assertEquals(List.of("cat:custody#0"), DiscordService.route(s, ev(DomainEvent.Type.APPEAL)), "appeal falls back to custody");
    }

    @Test
    void multipleDestinationsOverridesAndDisabledChannels() throws Exception {
        YamlConfiguration y = cfg();
        y.set("discord.webhook-url", A);
        y.set("discord.channels.punishment.webhooks", List.of(B, C));
        y.set("discord.channels.judicial.enabled", false);
        y.set("discord.events.warning.webhooks", List.of(C));
        y.set("discord.events.reversal.enabled", false);
        y.set("discord.events.quick-punishment.category", "owner-review");
        Settings s = Settings.parse(y);
        assertEquals(List.of("cat:punishment#0", "cat:punishment#1"), DiscordService.route(s, ev(DomainEvent.Type.PUNISHMENT_MODIFIED)));
        assertEquals(List.of("ev:warning#0"), DiscordService.route(s, ev(DomainEvent.Type.WARNING)), "per-event webhook wins");
        assertTrue(DiscordService.route(s, ev(DomainEvent.Type.VERDICT)).isEmpty(), "disabled channel is silent");
        assertTrue(DiscordService.route(s, ev(DomainEvent.Type.REVERSAL)).isEmpty(), "disabled event is silent");
        assertEquals(List.of("default#0"), DiscordService.route(s, ev(DomainEvent.Type.QUICK_PUNISHMENT)), "moved to owner-review, which falls back to default");
    }

    @Test
    void legacyOneWebhookConfigStillWorks() throws Exception {
        YamlConfiguration y = cfg();
        y.set("discord.webhook-url", A);
        Settings s = Settings.parse(y);
        for (DomainEvent.Type t : DomainEvent.Type.values()) assertEquals(List.of("default#0"), DiscordService.route(s, ev(t)), t.name());
    }

    @Test
    void invalidWebhookFallbackLoopAndMissingWebhookAreConfigErrors() {
        YamlConfiguration y = cfg();
        y.set("discord.webhook-url", "http://evil.example/hook");
        assertThrows(dev.aura.punishments.config.ConfigException.class, () -> Settings.parse(y));
        YamlConfiguration loop = cfg();
        loop.set("discord.webhook-url", A);
        loop.set("discord.channels.appeal.fallback", "judicial");
        loop.set("discord.channels.judicial.fallback", "appeal");
        assertThrows(dev.aura.punishments.config.ConfigException.class, () -> Settings.parse(loop));
        YamlConfiguration none = cfg();
        assertThrows(dev.aura.punishments.config.ConfigException.class, () -> Settings.parse(none), "enabled but nothing configured");
    }

    @Test
    void envVarAndSecretsFileKeepUrlsOutOfConfigYml() throws Exception {
        YamlConfiguration y = cfg();
        y.set("discord.webhook-url", "${env:AP_HOOK}");
        YamlConfiguration secrets = new YamlConfiguration();
        secrets.set("custody", List.of(B));
        Settings s = Settings.parse(y, secrets, n -> n.equals("AP_HOOK") ? A : null);
        assertEquals(A, s.discord.webhook());
        assertEquals(List.of(B), s.discord.channels().get("custody").webhooks());
        assertThrows(dev.aura.punishments.config.ConfigException.class, () -> Settings.parse(y, null, n -> null), "unset variable = no webhook configured");
    }

    @Test
    void duplicateEventKeysAreStoredOnceAndIpsAreScrubbed() throws Exception {
        YamlConfiguration y = cfg();
        y.set("discord.webhook-url", A);
        Settings s = Settings.parse(y);
        DiscordService d = new DiscordService(() -> s, Logger.getLogger("test"), u -> false); // nothing is ever sent
        DomainEvent e = DomainEvent.of(DomainEvent.Type.WARNING).key("warning:9").player("Steve").summary("from 203.0.113.9 and 2001:db8:0:0:0:0:0:1").build();
        d.send(e);
        d.send(e);
        assertEquals(1, d.store().due(Long.MAX_VALUE, 10).size() + d.store().count(dev.aura.punishments.model.Simple.OutboxState.DEAD), "same key = one message");
        String body = DiscordService.body(s, e);
        assertFalse(body.contains("203.0.113.9"));
        assertFalse(body.contains("2001:db8"));
        d.shutdown();
    }

    @Test
    void roleMentionsOnlyForConfiguredEventsAndNeverEveryone() throws Exception {
        YamlConfiguration y = cfg();
        y.set("discord.webhook-url", A);
        y.set("discord.channels.owner-review.mention-roles", List.of("123456789012345678"));
        y.set("discord.channels.owner-review.mention-events", List.of("review-overdue"));
        Settings s = Settings.parse(y);
        JsonObject over = JsonParser.parseString(DiscordService.body(s, ev(DomainEvent.Type.REVIEW_OVERDUE))).getAsJsonObject();
        assertEquals("<@&123456789012345678>", over.get("content").getAsString());
        assertEquals(1, over.getAsJsonObject("allowed_mentions").getAsJsonArray("roles").size());
        assertEquals(0, over.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        JsonObject created = JsonParser.parseString(DiscordService.body(s, ev(DomainEvent.Type.REVIEW_CREATED))).getAsJsonObject();
        assertFalse(created.has("content"));
        assertFalse(created.getAsJsonObject("allowed_mentions").has("roles"));
    }

    @Test
    void outboxSurvivesARestartAndIsDeliveredLater() throws Exception {
        var db = new dev.aura.punishments.db.Database(null, Logger.getLogger("test"));
        db.open();
        YamlConfiguration y = cfg();
        y.set("discord.webhook-url", A);
        y.set("discord.retry-backoff-seconds", 1);
        Settings s = Settings.parse(y);
        DiscordService first = new DiscordService(() -> s, Logger.getLogger("test"), u -> true, new DiscordService.DbStore(db), System::currentTimeMillis) {
            @Override protected String target(String c) { return "http://127.0.0.1:1/unreachable"; } // Discord "down"
        };
        first.send(DomainEvent.of(DomainEvent.Type.REVIEW_CREATED).key("review:1").caseId(3L).player("Alex").build());
        await(() -> { try { return first.store().count(dev.aura.punishments.model.Simple.OutboxState.PENDING) == 1; } catch (Exception ex) { return false; } });
        first.shutdown(); // "restart"
        DiscordService second = new DiscordService(() -> s, Logger.getLogger("test"), u -> true, new DiscordService.DbStore(db), System::currentTimeMillis) {
            @Override protected String target(String c) { return url; }
        };
        await(() -> second.sentCount() >= 1);
        assertEquals(1, second.sentCount(), "the queued event was recovered and delivered after the restart");
        assertEquals(1, bodies.size());
        second.shutdown();
        db.close();
    }

    @Test
    void deadRowsCanBeRequeuedAndSucceedAfterTheWebhookIsFixed() throws Exception {
        statusFor = () -> 404;
        discord.send(event("gone"));
        await(() -> discord.failedCount() == 1);
        assertEquals(1, discord.failedCount());
        statusFor = () -> 204;
        assertEquals(1, discord.requeueDead());
        await(() -> discord.sentCount() == 1);
        assertEquals(1, discord.sentCount());
    }
}
