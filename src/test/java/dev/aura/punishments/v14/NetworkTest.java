package dev.aura.punishments.v14;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two (or three) servers of one network, each with its own services and its own in-memory index, sharing ONE real MariaDB database:
 * what a punishment on one server does on the other. Skipped when no MariaDB is reachable.
 */
class NetworkTest {
    Fx a;   // survival
    Fx b;   // towny
    Actor mod;
    Target steve;

    static boolean reachable() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(System.getProperty("ap.test.db.host", "127.0.0.1"), Integer.getInteger("ap.test.db.port", 3306)), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Actor staff(String name) {
        return Fx.staff(name, 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.HISTORY_VIEW, Perms.EVIDENCE_ADD, Perms.EVIDENCE_VIEW, Perms.NOTE_ADD, Perms.NOTE_VIEW,
                Perms.punish(PunishType.WARN), Perms.punish(PunishType.KICK), Perms.punish(PunishType.MUTE), Perms.punish(PunishType.BAN), Perms.punish(PunishType.IPBAN),
                Perms.limit(PunishType.MUTE, "extended"), Perms.limit(PunishType.BAN, "extended"), Perms.punishPermanent(PunishType.BAN), Perms.limit(PunishType.IPBAN, "long"), Perms.REVOKE, Perms.REVOKE_ANY,
                Perms.CASE_CREATE, Perms.CASE_VIEW_ALL);
    }

    static Fx server(String name, String database) throws Exception {
        Fx.Mutator m = new Fx.Mutator();
        m.shared = true;
        m.serverName = name;
        m.joinNetwork = true;
        m.sharedDatabase = database;
        return new Fx(null, m);
    }

    @BeforeEach
    void start() throws Exception {
        Assumptions.assumeTrue(reachable(), "no MariaDB server: the network tests are skipped");
        a = server("survival", null);
        b = server("towny", a.sharedDatabaseName());
        b.now = a.now;
        mod = staff("Mod");
        steve = Fx.target("Steve");
    }

    @AfterEach
    void stop() {
        if (b != null) b.close();
        if (a != null) a.close();
    }

    static <T> Result<T> r(CompletableFuture<Result<T>> f) throws Exception {
        return f.get(20, TimeUnit.SECONDS);
    }

    Punishment ban(Fx on, Target t, long dur) throws Exception {
        return r(on.punishments.issueQuick(mod, new PunishmentService.Request(t, PunishType.BAN, dur, "Repeated griefing of builds.", List.of(Fx.ev())))).orThrow();
    }

    // ------------------------------------------------------------------ enforcement across servers
    @Test
    void aBanIssuedOnOneServerIsRefusedAtLoginOnTheOtherWithoutWaitingForTheFeed() throws Exception {
        assertTrue(b.punishments.banForLogin(steve.uuid(), null).isEmpty());
        Punishment p = ban(a, steve, Durations.DAY);
        assertTrue(b.punishments.banForLogin(steve.uuid(), null).isPresent(), "the very next connection to ANY server asks the database, not a stale index");
        assertEquals(p.id(), b.punishments.banForLogin(steve.uuid(), null).get().id());
        assertEquals(1, b.network.poll().get(), "the change feed carries one event");
        assertTrue(b.punishments.banFor(steve.uuid(), null).isPresent(), "and the in-memory index is current after the poll");
    }

    @Test
    void aBanDisconnectsThePlayerAlreadyConnectedToTheOtherServer() throws Exception {
        b.online.add(steve.uuid());
        b.network.playerJoined(steve.uuid(), "Steve").get();
        ban(a, steve, Durations.DAY);
        assertTrue(b.disconnected.isEmpty(), "nothing happens before the feed is read");
        b.network.poll().get();
        assertEquals(List.of(steve.uuid()), b.disconnected);
        int issuerCalls = a.disconnected.size(); // the issuing server already acted locally when it issued the ban
        a.network.poll().get();
        assertEquals(issuerCalls, a.disconnected.size(), "the server that issued it is not told about its own change again");
    }

    @Test
    void aMuteAppliesOnTheOtherServerAndItsRevocationLiftsItEverywhere() throws Exception {
        b.online.add(steve.uuid());
        Punishment mute = r(a.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of()))).orThrow();
        b.network.poll().get();
        assertTrue(b.punishments.muteFor(steve.uuid()).isPresent());
        assertEquals(List.of(steve.uuid()), b.muteNotices);
        // lifted on the OTHER server than the one that issued it
        r(b.punishments.revoke(mod, mute.id(), "Overturned after a review of the chat log.")).orThrow();
        assertTrue(b.punishments.muteFor(steve.uuid()).isEmpty());
        a.online.add(steve.uuid());
        a.network.poll().get();
        assertTrue(a.punishments.muteFor(steve.uuid()).isEmpty(), "revoked everywhere");
        assertEquals(List.of(steve.uuid()), a.unmuteNotices);
    }

    @Test
    void aKickReachesAPlayerWhoIsConnectedToAnotherServer() throws Exception {
        var offline = r(a.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.KICK, 0, "Disconnecting for AFK farming.", List.of())));
        assertEquals(Code.INVALID, offline.code(), "nobody to kick anywhere on the network");
        b.online.add(steve.uuid());
        b.network.playerJoined(steve.uuid(), "Steve").get();
        var kick = r(a.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.KICK, 0, "Disconnecting for AFK farming.", List.of())));
        assertTrue(kick.isOk(), kick.isOk() ? "" : kick.message());
        b.network.poll().get();
        assertEquals(List.of(steve.uuid()), b.disconnected, "the server the player is on disconnects them");
    }

    @Test
    void whatAServerDidItselfIsNotAppliedAgainWhenItReadsTheFeed() throws Exception {
        a.online.add(steve.uuid());
        r(a.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.KICK, 0, "Disconnecting for AFK farming.", List.of()))).orThrow();
        int once = a.disconnected.size();
        a.network.poll().get();
        assertEquals(once, a.disconnected.size());
    }

    @Test
    void anOfflinePlayersBanIsEnforcedWhenTheyNextConnectToAnyServer() throws Exception {
        ban(b, steve, Durations.PERMANENT);
        assertTrue(a.punishments.banForLogin(steve.uuid(), null).isPresent());
        assertTrue(a.punishments.banForLogin(Fx.target("Someone Else").uuid(), null).isEmpty());
    }

    @Test
    void anIpBanBlocksEveryAccountOnThatAddressOnEveryServer() throws Exception {
        a.db.txSync(c -> {
            Repo.Profiles.upsert(c, steve.uuid(), "Steve", "10.1.2.3", 0, a.now);
            return null;
        });
        r(a.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.IPBAN, Durations.DAY, "Ban evasion with alternate accounts.", List.of(Fx.ev())))).orThrow();
        Target alt = Fx.target("Steves Alt");
        assertTrue(b.punishments.banForLogin(alt.uuid(), "10.1.2.3").isPresent(), "a different account from the banned address");
        assertTrue(b.punishments.banForLogin(alt.uuid(), "10.9.9.9").isEmpty(), "a different address is fine");
    }

    // ------------------------------------------------------------------ concurrency
    @Test
    void twoServersBanningTheSamePlayerAtTheSameMomentLetExactlyOneWin() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 4; round++) {
                Target victim = Fx.target("Victim" + round);
                CompletableFuture<Result<Punishment>> fa = CompletableFuture.supplyAsync(() -> {
                    try {
                        return r(a.punishments.issueQuick(mod, new PunishmentService.Request(victim, PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev()))));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, pool);
                CompletableFuture<Result<Punishment>> fb = CompletableFuture.supplyAsync(() -> {
                    try {
                        return r(b.punishments.issueQuick(mod, new PunishmentService.Request(victim, PunishType.BAN, Durations.DAY, "Repeated griefing of builds.", List.of(Fx.ev()))));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, pool);
                Result<Punishment> ra = fa.get(30, TimeUnit.SECONDS);
                Result<Punishment> rb = fb.get(30, TimeUnit.SECONDS);
                assertEquals(1, (ra.isOk() ? 1 : 0) + (rb.isOk() ? 1 : 0), "exactly one succeeded: " + ra.code() + " / " + rb.code());
                Result<Punishment> loser = ra.isOk() ? rb : ra;
                assertEquals(Code.DUPLICATE, loser.code());
                assertEquals(1, a.db.<List<Punishment>>txSync(c -> Repo.Punishments.activeFor(c, victim.uuid())).size(), "one active ban in the database");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theAuditChainStaysIntactWhenServersWriteAtTheSameTime() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<Result<Punishment>>> all = new java.util.ArrayList<>();
            for (int i = 0; i < 24; i++) {
                Fx on = i % 2 == 0 ? a : b;
                Target t = Fx.target("Player" + i);
                all.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return r(on.punishments.issueQuick(mod, new PunishmentService.Request(t, PunishType.MUTE, Durations.HOUR, "Spamming the global channel.", List.of())));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, pool));
            }
            for (var f : all) assertTrue(f.get(60, TimeUnit.SECONDS).isOk());
            assertEquals(-1L, (long) a.db.<Long>txSync(Repo.Audits::verify), "one unbroken hash chain across both servers");
            assertTrue(a.db.<Long>txSync(Repo.Audits::count) >= 24);
            List<String> origins = a.db.txSync(c -> dev.aura.punishments.db.Sql.query(c, "SELECT DISTINCT origin FROM audit_log ORDER BY origin", rs -> rs.getString(1)));
            assertEquals(List.of("survival", "towny"), origins, "every entry says which server wrote it");
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ change events
    @Test
    void everyPunishmentChangeIsAnEventStampedWithTheServerThatMadeIt() throws Exception {
        long before = a.db.txSync(Repo.Net::lastEventId);
        Punishment p = ban(a, steve, Durations.DAY);
        r(b.punishments.revoke(mod, p.id(), "Overturned after a review of the evidence."));
        List<Repo.Net.Event> events = a.db.txSync(c -> Repo.Net.eventsAfter(c, before, 100));
        assertEquals(2, events.size());
        assertEquals("survival", events.get(0).origin());
        assertEquals("BAN:ACTIVE", events.get(0).detail());
        assertEquals("towny", events.get(1).origin());
        assertEquals("BAN:REVOKED", events.get(1).detail());
        assertEquals(steve.uuid(), events.get(1).subject());
    }

    @Test
    void oldEventsArePurgedAndANewServerStartsAtNow() throws Exception {
        ban(a, steve, Durations.DAY);
        assertTrue(a.network.purgeOldEvents(-1).get() >= 1, "everything older than 'now + 1ms' goes");
        Fx c = server("lobby", a.sharedDatabaseName());
        try {
            c.now = a.now;
            assertEquals(0, c.network.poll().get(), "a server that joins later does not replay history");
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------------ registry, presence, leases
    @Test
    void aSecondServerWithTheSameNameIsRefusedUntilTheFirstLeavesCleanly() throws Exception {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> server("survival", a.sharedDatabaseName()));
        assertTrue(e.getMessage().contains("already running as 'survival'"), e.getMessage());
        a.network.stop();
        Fx again = server("survival", a.sharedDatabaseName());
        again.close();
    }

    @Test
    void theRegistryListsTheServersAndMarksASilentOneAsSilent() throws Exception {
        a.network.heartbeat(List.of()).get();
        b.network.heartbeat(List.of()).get();
        String text = String.join("\n", a.network.describe());
        assertTrue(text.contains("survival") && text.contains("towny") && text.contains("running"), text);
        a.now += 120_000;
        text = String.join("\n", a.network.describe());
        assertTrue(text.contains("towny") && text.contains("silent"), "a server that stopped answering is shown as silent: " + text);
    }

    @Test
    void presenceTellsWhereAPlayerIsAndForgetsADeadServersPlayers() throws Exception {
        assertTrue(a.network.whereIs(steve.uuid()).get().isEmpty());
        b.network.playerJoined(steve.uuid(), "Steve").get();
        assertEquals("towny", a.network.whereIs(steve.uuid()).get().orElseThrow());
        assertTrue(a.network.isOnlineElsewhere(steve.uuid()).get());
        assertFalse(b.network.isOnlineElsewhere(steve.uuid()).get());
        a.now += 120_000; // nobody refreshed the row: towny crashed
        assertTrue(a.network.whereIs(steve.uuid()).get().isEmpty());
        a.now -= 120_000;
        b.network.playerLeft(steve.uuid()).get();
        assertTrue(a.network.whereIs(steve.uuid()).get().isEmpty());
    }

    @Test
    void aBackgroundJobRunsOnTheServerThatHoldsItsLeaseAndMovesWhenItExpires() throws Exception {
        assertTrue(a.network.lead("housekeeping", 1_000).get());
        assertFalse(b.network.lead("housekeeping", 1_000).get(), "someone else already leads");
        assertTrue(a.network.lead("housekeeping", 1_000).get(), "the leader keeps it by asking again");
        b.now += 5_000;
        assertTrue(b.network.lead("housekeeping", 1_000).get(), "the lease expired, so another server takes over");
        a.now = b.now;
        assertFalse(a.network.lead("housekeeping", 1_000).get());
    }

    @Test
    void aServerWhoseClockDiffersFromTheDatabaseIsRefusedWhenChecked() throws Exception {
        Fx skewed = server("skyblock", a.sharedDatabaseName());
        try {
            skewed.now = System.currentTimeMillis() - 3_600_000L; // an hour behind the database server
            var res = skewed.network.start("test", false, 10_000L, false).get();
            assertEquals(Code.UNAVAILABLE, res.code());
            assertTrue(res.message().contains("clock") && res.message().contains("NTP"), res.message());
        } finally {
            skewed.close();
        }
    }

    @Test
    void casesAndEvidenceAreSharedToo() throws Exception {
        var made = r(a.cases.create(mod, new dev.aura.punishments.service.CaseService.CreateRequest(steve, "other", dev.aura.punishments.model.Simple.Severity.MODERATE,
                "Griefing the spawn area repeatedly", null, List.of(Fx.ev()))));
        assertTrue(made.isOk(), made.isOk() ? "" : made.message());
        var seen = r(b.cases.detail(mod, made.value().id()));
        assertTrue(seen.isOk());
        assertEquals(1, seen.value().evidence().size());
        assertEquals("survival", a.db.<String>txSync(c -> dev.aura.punishments.db.Sql.one(c, "SELECT origin FROM cases WHERE id=?", rs -> rs.getString(1), made.value().id())));
    }

    @Test
    void aRevokedPunishmentStaysRevokedOnEveryServerAndKeepsItsHistory() throws Exception {
        Punishment p = ban(a, steve, Durations.PERMANENT);
        r(b.punishments.revoke(mod, p.id(), "Overturned after a review of the evidence."));
        assertTrue(a.punishments.banForLogin(steve.uuid(), null).isEmpty());
        Punishment row = a.db.txSync(c -> Repo.Punishments.get(c, p.id()));
        assertEquals(PunishStatus.REVOKED, row.status());
        assertEquals("survival", row.origin(), "the original record still says where it came from");
    }
}
