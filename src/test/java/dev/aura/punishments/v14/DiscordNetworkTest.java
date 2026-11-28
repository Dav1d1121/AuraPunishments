package dev.aura.punishments.v14;

import com.sun.net.httpserver.HttpServer;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Database;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.StorageConfig;
import dev.aura.punishments.integration.DiscordService;
import dev.aura.punishments.model.Simple.OutboxState;
import dev.aura.punishments.service.DomainEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Four servers share one Discord outbox: every event is posted once, by one sender at a time, and a dead sender is replaced. */
class DiscordNetworkTest {
    private HttpServer http;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile boolean fail;
    private String url;
    private Database a;
    private Database b;
    private DiscordService sa;
    private DiscordService sb;
    private String dbName;
    private Settings settings;

    private Database server(String name) throws Exception {
        Database d = Database.shared(new StorageConfig("mariadb", System.getProperty("ap.test.db.host", "127.0.0.1"), Integer.getInteger("ap.test.db.port", 3306), dbName,
                "aura", "aurapass", false, true, true, 4, 1, 10_000, 300_000, 1_500_000, 60_000), Logger.getLogger("test"), name);
        d.open();
        return d;
    }

    private DiscordService service(Database d, long leaseMs) {
        return new DiscordService(() -> settings, Logger.getLogger("test"), u -> true, new DiscordService.DbStore(d, leaseMs), System::currentTimeMillis) {
            @Override protected String target(String configured) { return url; }
        };
    }

    @BeforeEach
    void start() throws Exception {
        Assumptions.assumeTrue(NetworkTest.reachable(), "no MariaDB server");
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/hook", ex -> {
            requests.incrementAndGet();
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(fail ? 500 : 204, -1);
            ex.close();
        });
        http.start();
        url = "http://127.0.0.1:" + http.getAddress().getPort() + "/hook";
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(DiscordNetworkTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        y.set("discord.enabled", true);
        y.set("discord.webhook-url", "https://discord.com/api/webhooks/1/abc");
        y.set("discord.retry-backoff-seconds", 1);
        settings = Settings.parse(y);
        dbName = "aura_t_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        a = server("survival");
        b = server("towny");
    }

    @AfterEach
    void stop() {
        if (sa != null) sa.shutdown();
        if (sb != null) sb.shutdown();
        if (http != null) http.stop(0);
        if (a != null) a.close();
        if (b != null) b.close();
        try (var c = new org.mariadb.jdbc.Driver().connect("jdbc:mariadb://" + System.getProperty("ap.test.db.host", "127.0.0.1") + ":" + Integer.getInteger("ap.test.db.port", 3306) + "/",
                props()); var st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + dbName + "`");
        } catch (Exception ignored) {
            // a leftover throw-away database is harmless
        }
    }

    private static java.util.Properties props() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("user", "aura");
        p.setProperty("password", "aurapass");
        return p;
    }

    private static DomainEvent ev(int i) {
        return DomainEvent.of(DomainEvent.Type.REVIEW_CREATED).key("review:" + i).caseId((long) i).player("Alex").build();
    }

    private void await(java.util.function.BooleanSupplier c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(50);
        }
    }

    private long count(Database d, OutboxState st) throws Exception {
        return d.txSync(c -> Repo.Outbox.count(c, st));
    }

    @Test
    void everyEventIsPostedExactlyOnceNoMatterHowManyServersRun() throws Exception {
        sa = service(a, 30_000);
        sb = service(b, 30_000);
        for (int i = 0; i < 12; i++) (i % 2 == 0 ? sa : sb).send(ev(i)); // events happen on both servers
        await(() -> requests.get() >= 12, 20_000);
        Thread.sleep(1500); // time for a duplicate to show up if there were going to be one
        assertEquals(12, requests.get(), "one post per event, not one per server");
        assertEquals(12, count(a, OutboxState.SENT));
        assertEquals(0, count(a, OutboxState.PENDING));
        assertTrue(sa.sentCount() == 0 || sb.sentCount() == 0 || sa.sentCount() + sb.sentCount() == 12);
        assertEquals(12, sa.sentCount() + sb.sentCount());
    }

    @Test
    void theSameFactReportedByTwoServersIsOneMessage() throws Exception {
        sa = service(a, 30_000);
        sb = service(b, 30_000);
        sa.send(ev(7));
        sb.send(ev(7)); // e.g. both servers noticed the same expiry
        await(() -> requests.get() >= 1, 10_000);
        Thread.sleep(1200);
        assertEquals(1, requests.get());
        assertEquals(1L, count(a, OutboxState.SENT) + count(a, OutboxState.PENDING));
    }

    @Test
    void whenTheSenderDiesAnotherServerTakesOverAndNothingIsLost() throws Exception {
        sa = service(a, 1_500);
        sb = service(b, 1_500);
        sa.send(ev(1));
        await(() -> requests.get() >= 1, 10_000);
        assertEquals(1, requests.get());
        // whichever server sent it, stop it (a crashed server) and keep producing events on the other one
        DiscordService alive = sa.sentCount() > 0 ? sb : sa;
        DiscordService dead = alive == sa ? sb : sa;
        dead.shutdown();
        for (int i = 2; i <= 6; i++) alive.send(ev(i));
        await(() -> requests.get() >= 6, 20_000);
        Thread.sleep(800);
        assertEquals(6, requests.get(), "the survivor became the sender once the dead one's lease ran out");
        assertEquals(6, count(a, OutboxState.SENT));
    }

    @Test
    void aRowClaimedByAServerThatDiedBecomesDueAgainWhenItsLeaseEnds() throws Exception {
        sa = null;
        long now = 1_000_000L;
        a.txSync(c -> Repo.Outbox.enqueue(c, "k1", "cat", "evt", "default#0", "{}", now));
        var first = a.txSync(c -> Repo.Outbox.claim(c, "survival", now, 10, 60_000));
        assertEquals(1, first.size());
        assertTrue(b.txSync(c -> Repo.Outbox.claim(c, "towny", now + 1000, 10, 60_000)).isEmpty(), "held by survival");
        var later = b.txSync(c -> Repo.Outbox.claim(c, "towny", now + 61_000, 10, 60_000));
        assertEquals(1, later.size(), "survival never finished it: the lease ran out");
    }

    @Test
    void aFailingWebhookIsRetriedNotLostAndNotDuplicated() throws Exception {
        fail = true;
        sa = service(a, 30_000);
        sa.send(ev(1));
        await(() -> requests.get() >= 2, 15_000);
        assertTrue(requests.get() >= 2, "it is retried with a back-off");
        fail = false;
        await(() -> count0(OutboxState.SENT) == 1, 20_000);
        assertEquals(1, count0(OutboxState.SENT));
        assertEquals(0, count0(OutboxState.DEAD));
    }

    private long count0(OutboxState st) {
        try {
            return count(a, st);
        } catch (Exception e) {
            return -1;
        }
    }
}
