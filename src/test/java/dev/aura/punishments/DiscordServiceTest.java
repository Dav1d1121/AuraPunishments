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
        assertTrue(discord.status().contains("failed=1"));
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
}
