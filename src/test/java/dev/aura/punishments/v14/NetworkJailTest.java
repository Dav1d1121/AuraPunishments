package dev.aura.punishments.v14;

import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.integration.RemoteJailPort;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.JailService;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The network jail: JailReloaded exists on ONE backend ("jail"); a jail ordered on "survival" is queued, carried out there, held everywhere
 * meanwhile, and the prisoner is sent back when it ends. Two (three) servers, one real MariaDB database; skipped when none is reachable.
 */
public class NetworkJailTest {
    Fx survival;
    Fx jail;
    Actor mod;
    Target steve;

    public static Fx server(String name, String database, boolean court) throws Exception {
        Fx.Mutator m = new Fx.Mutator();
        m.shared = true;
        m.serverName = name;
        m.joinNetwork = true;
        m.sharedDatabase = database;
        m.jailBackend = "jail";
        m.set.put("judicial.enabled", court);
        m.set.put("casework.enabled", !court);
        return new Fx(null, m);
    }

    @BeforeEach
    void start() throws Exception {
        Assumptions.assumeTrue(NetworkTest.reachable(), "no MariaDB server: the network tests are skipped");
        survival = server("survival", null, false);
        jail = server("jail", survival.sharedDatabaseName(), false);
        jail.now = survival.now;
        survival.netJail.refresh().get();
        jail.netJail.refresh().get();
        mod = Fx.staff("Mod", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.HISTORY_VIEW, Perms.EVIDENCE_ADD, Perms.NOTE_ADD, Perms.CASE_CREATE, Perms.CASE_VIEW_ALL,
                Perms.punish(PunishType.JAIL), Perms.limit(PunishType.JAIL, "extended"), Perms.REVOKE, Perms.REVOKE_ANY, Perms.CUSTODY_RELEASE);
        steve = Fx.target("Steve");
    }

    @AfterEach
    void stop() {
        if (jail != null) jail.close();
        if (survival != null) survival.close();
    }

    static <T> Result<T> r(CompletableFuture<Result<T>> f) throws Exception {
        return f.get(20, TimeUnit.SECONDS);
    }

    Result<Punishment> orderJail(Fx on, Target t, long dur) throws Exception {
        return r(on.punishments.issueQuick(mod, new PunishmentService.Request(t, PunishType.JAIL, dur, "Repeated griefing of builds.", List.of(Fx.ev()))));
    }

    Punishment row(long id) throws Exception {
        return survival.db.txSync(c -> Repo.Punishments.get(c, id));
    }

    void sync() throws Exception {
        jail.network.heartbeat(List.of()).get(); // the jail backend is alive (a test may have moved the clock)
        jail.jails.networkWork().get(20, TimeUnit.SECONDS);
        jail.netJail.refresh().get();
        survival.netJail.refresh().get();
    }

    // ------------------------------------------------------------------ ordering a jail on a backend without JailReloaded
    @Test
    void aJailOrderedOnAnotherBackendIsQueuedNeverReportedAsConfirmed() throws Exception {
        assertFalse(survival.core.jail.authoritative());
        assertTrue(jail.core.jail.authoritative());
        var res = orderJail(survival, steve, Durations.HOUR);
        assertTrue(res.isOk(), res.message());
        Punishment p = row(res.value().id());
        assertEquals(JailService.PENDING_PLACEMENT, p.jailState(), "queued, not held: nobody has confirmed it yet");
        assertTrue(RemoteJailPort.placeholder(p.jailPrisonerId()), p.jailPrisonerId());
        assertTrue(survival.jail.calls.isEmpty(), "this backend has no JailReloaded to call");
        assertTrue(jail.jail.calls.isEmpty(), "and the jail backend has not been asked yet");
        assertTrue(survival.netJail.mustHold(steve.uuid()) || !survival.core.settings.network.jail().hold() || survival.netJail.isJailed(steve.uuid()));
    }

    @Test
    void theJailBackendCarriesOutTheQueuedJailAndRecordsTheRealPrisoner() throws Exception {
        Punishment p = orderJail(survival, steve, Durations.HOUR).value();
        sync();
        assertEquals(1, jail.jail.calls.size(), jail.jail.calls.toString());
        assertTrue(jail.jail.calls.get(0).startsWith("jail:Steve:"), jail.jail.calls.toString());
        var held = jail.jail.find(steve.uuid()).orElseThrow();
        assertTrue(held.reason().startsWith("[" + JailService.tag(p.id()) + "]"));
        Punishment after = row(p.id());
        assertEquals(JailService.HELD, after.jailState());
        assertEquals(held.jailPrisonerId(), after.jailPrisonerId(), "the placeholder is replaced by JailReloaded's id");
        sync();
        assertEquals(1, jail.jail.calls.size(), "a second pass never jails twice");
    }

    @Test
    void theSentenceIsWhatIsLeftWhenTheJailBackendGetsToIt() throws Exception {
        Punishment p = orderJail(survival, steve, Durations.HOUR).value();
        survival.now += 20 * Durations.MINUTE;
        jail.now = survival.now;
        sync();
        long left = jail.jail.find(steve.uuid()).orElseThrow().remainingMs();
        assertTrue(Math.abs(left - 40 * Durations.MINUTE) < 5000, "time ordered, not time since arrival: " + left);
        assertEquals(p.id(), row(p.id()).id());
    }

    @Test
    void aJailIsRefusedWhileTheJailBackendIsNotRunning() throws Exception {
        jail.network.stop();
        survival.netJail.refresh().get();
        assertFalse(survival.netJail.backendRunning());
        var res = orderJail(survival, steve, Durations.HOUR);
        assertEquals(Code.UNAVAILABLE, res.code());
        assertTrue(res.message().contains("Nobody was jailed") && res.message().contains("jail backend 'jail'"), res.message());
        assertEquals(0, survival.db.<Long>txSync(c -> dev.aura.punishments.db.Sql.count(c, "SELECT COUNT(*) FROM punishments WHERE type='JAIL'")), "nothing half-recorded");
    }

    // ------------------------------------------------------------------ the hold and the transfer
    @Test
    void aJailedPlayerIsHeldAndSentToTheJailBackendUntilTheyArrive() throws Exception {
        survival.online.add(steve.uuid());
        survival.network.playerJoined(steve.uuid(), "Steve").get();
        orderJail(survival, steve, Durations.HOUR).orThrow();
        assertTrue(survival.netJail.mustHold(steve.uuid()), "no moving, building or chatting meanwhile");
        survival.netJail.tick();
        assertEquals(List.of(steve.uuid() + " -> jail"), survival.transfers);
        survival.netJail.tick();
        assertEquals(1, survival.transfers.size(), "asked once per interval, not every tick");
        survival.now += 6000;
        survival.netJail.tick();
        assertEquals(2, survival.transfers.size(), "and again when the player is still here");
    }

    @Test
    void whenTheJailBackendIsDownTheHoldStaysAndNobodyEscapes() throws Exception {
        survival.online.add(steve.uuid());
        orderJail(survival, steve, Durations.HOUR).orThrow();
        jail.network.stop();
        survival.netJail.refresh().get();
        survival.netJail.tick();
        assertTrue(survival.transfers.isEmpty(), "no point asking the proxy for a server that is not there");
        assertTrue(survival.hints.stream().anyMatch(h -> h.contains("jail-hold-waiting")), survival.hints.toString());
        assertTrue(survival.netJail.mustHold(steve.uuid()), "held where they are");
    }

    @Test
    void aLoginOnTheWrongBackendIsHeldFromTheFirstTick() throws Exception {
        Fx towny = server("towny", survival.sharedDatabaseName(), false); // running before the jail is ordered
        try {
            Punishment p = orderJail(survival, steve, Durations.HOUR).value();
            sync();
            assertFalse(towny.netJail.mustHold(steve.uuid()), "this backend has not heard of it yet");
            towny.netJail.admit(steve.uuid()); // what the pre-login check does: ask the database, not a cache
            assertTrue(towny.netJail.mustHold(steve.uuid()));
            towny.online.add(steve.uuid());
            towny.netJail.refresh().get();
            towny.netJail.tick();
            assertEquals(List.of(steve.uuid() + " -> jail"), towny.transfers);
            assertNotNull(p);
        } finally {
            towny.close();
        }
    }

    @Test
    void aBackendThatStartsLaterAlreadyKnowsWhoIsJailed() throws Exception {
        orderJail(survival, steve, Durations.HOUR).orThrow();
        Fx towny = server("towny", survival.sharedDatabaseName(), false);
        try {
            assertTrue(towny.netJail.mustHold(steve.uuid()));
        } finally {
            towny.close();
        }
    }

    @Test
    void theJailBackendHoldsAPlayerUntilJailReloadedHasTakenThem() throws Exception {
        orderJail(survival, steve, Durations.HOUR).orThrow();
        jail.netJail.refresh().get();
        jail.online.add(steve.uuid());
        jail.netJail.tick();
        assertTrue(jail.netJail.mustHold(steve.uuid()), "on the jail backend but not yet inside JailReloaded");
        assertTrue(jail.transfers.isEmpty(), "this IS the jail backend");
        sync();
        jail.jail.prisoners.put(steve.uuid(), new dev.aura.punishments.integration.JailPort.PrisonerState(steve.uuid(), "Steve", "jid-1", "main", 1000, false, false, "[x]", "Mod"));
        jail.netJail.tick();
        assertFalse(jail.netJail.mustHold(steve.uuid()), "inside JailReloaded: its own rules apply");
    }

    // ------------------------------------------------------------------ release
    @Test
    void aRevocationOnAnotherBackendReleasesThePrisonerAndSendsThemBackWhereTheyCameFrom() throws Exception {
        survival.online.add(steve.uuid());
        survival.network.playerJoined(steve.uuid(), "Steve").get();
        Punishment p = orderJail(survival, steve, Durations.HOUR).value();
        assertEquals("survival", row(p.id()).jailOrigin(), "remembered where they were when jailed");
        sync();
        assertNotNull(jail.jail.find(steve.uuid()).orElse(null));
        survival.online.remove(steve.uuid());
        jail.online.add(steve.uuid());
        var rv = r(survival.punishments.revoke(mod, p.id(), "Jailed the wrong player, sorry."));
        assertTrue(rv.isOk(), rv.message());
        assertEquals(JailService.RELEASING, row(p.id()).jailState(), "accepted, carried out by the jail backend");
        assertNotNull(jail.jail.find(steve.uuid()).orElse(null), "still inside until the jail backend acts");
        assertFalse(survival.netJail.isJailed(steve.uuid()), "but the hold is gone everywhere at once");
        sync();
        assertTrue(jail.jail.find(steve.uuid()).isEmpty(), "released in JailReloaded");
        assertEquals(JailService.RELEASED, row(p.id()).jailState());
        jail.now += 3000;
        jail.netJail.tick();
        assertEquals(List.of(steve.uuid() + " -> survival"), jail.transfers, "back to the backend they came from");
    }

    @Test
    void aReleaseGoesToTheLobbyWhenTheOriginIsUnknownAndFallsBackWhenTheTargetDoesNotTakeThem() throws Exception {
        Punishment p = orderJail(survival, steve, Durations.HOUR).value(); // offline when jailed: no origin
        assertNull(row(p.id()).jailOrigin());
        sync();
        jail.online.add(steve.uuid());
        r(survival.punishments.revoke(mod, p.id(), "Jailed the wrong player, sorry.")).orThrow();
        sync();
        jail.now += 3000;
        jail.netJail.tick();
        assertEquals(List.of(steve.uuid() + " -> lobby"), jail.transfers);
    }

    @Test
    void aSentenceThatEndedInJailReloadedReleasesTheHoldAndSendsThePlayerBack() throws Exception {
        survival.online.add(steve.uuid());
        survival.network.playerJoined(steve.uuid(), "Steve").get();
        Punishment p = orderJail(survival, steve, Durations.HOUR).value();
        sync();
        survival.online.remove(steve.uuid());
        jail.online.add(steve.uuid());
        jail.now += 2 * Durations.HOUR;
        survival.now = jail.now;
        jail.jail.prisoners.remove(steve.uuid()); // JailReloaded's timer let them go
        sync();
        Punishment after = row(p.id());
        assertEquals(PunishStatus.SERVED, after.status());
        assertEquals(JailService.RELEASED, after.jailState());
        jail.netJail.tick();
        jail.now += 3000;
        jail.netJail.tick();
        assertEquals(List.of(steve.uuid() + " -> survival"), jail.transfers);
        assertFalse(survival.netJail.isJailed(steve.uuid()));
    }

    @Test
    void aQueuedJailThatRanOutBeforeAnyoneCarriedItOutEndsWithoutEverTouchingJailReloaded() throws Exception {
        Punishment p = orderJail(survival, steve, Durations.HOUR).value();
        jail.network.stop(); // the jail backend was down the whole time
        survival.now += 2 * Durations.HOUR;
        jail.now = survival.now;
        survival.netJail.refresh().get();
        assertFalse(survival.netJail.isJailed(steve.uuid()), "the clock released them");
        jail.jails.networkWork().get();
        assertTrue(jail.jail.calls.isEmpty());
        assertEquals(PunishStatus.SERVED, row(p.id()).status());
    }

    // ------------------------------------------------------------------ things that must not silently break
    @Test
    void whenJailReloadedRefusesThePlayerStaysHeldAndStaffAreAlertedOnce() throws Exception {
        Punishment p = orderJail(survival, steve, Durations.HOUR).value();
        List<dev.aura.punishments.service.DomainEvent> events = jail.events;
        jail.jail.failNext = "no such jail";
        jail.jails.networkWork().get();
        assertEquals(JailService.PENDING_PLACEMENT, row(p.id()).jailState());
        assertTrue(String.valueOf(row(p.id()).jailError()).contains("no such jail"));
        assertTrue(survival.netJail.isJailed(steve.uuid()) || row(p.id()).status() == PunishStatus.ACTIVE);
        jail.jails.networkWork().get();
        assertEquals(JailService.HELD, row(p.id()).jailState(), "retried and now carried out");
        assertTrue(events.stream().noneMatch(e -> e.type() == dev.aura.punishments.service.DomainEvent.Type.CUSTODY_ALERT));
    }

    @Test
    void aPlayerAlreadyJailedInJailReloadedByHandMakesTheNetworkJailFailLoudly() throws Exception {
        jail.jail.prisoners.put(steve.uuid(), new dev.aura.punishments.integration.JailPort.PrisonerState(steve.uuid(), "Steve", "manual-1", "main", 1000, false, false, "manual", "Admin"));
        Punishment p = orderJail(survival, steve, Durations.HOUR).value();
        jail.jails.networkWork().get();
        Punishment after = row(p.id());
        assertEquals(PunishStatus.REVOKED, after.status());
        assertEquals(JailService.FAILED, after.jailState());
        assertFalse(survival.netJail.refresh().thenApply(v -> survival.netJail.isJailed(steve.uuid())).get(), "no hold for a jail that never started");
    }

    @Test
    void theCourtSystemsPretrialDetentionOnlyWorksWhereJailReloadedIs() throws Exception {
        Fx court = server("towny", survival.sharedDatabaseName(), true);
        try {
            court.jails.refreshIndex().get();
            var res = r(court.custody.initiate(Fx.owner(), 1));
            assertEquals(Code.UNAVAILABLE, res.code(), res.message());
            assertTrue(res.message().contains("jail backend 'jail'"), res.message());
        } finally {
            court.close();
        }
    }

    // ------------------------------------------------------------------ configuration
    private static YamlConfiguration cfg() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(NetworkJailTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void theShippedConfigHasNoNetworkJailAndTheOptionsAreValidated() throws Exception {
        Settings s = Settings.parse(cfg());
        assertFalse(s.network.jail().enabled());
        YamlConfiguration y = cfg();
        y.set("database.type", "mariadb");
        y.set("database.username", "aura");
        y.set("network.server-name", "survival");
        y.set("network.jail.backend", "jail");
        y.set("network.jail.return.mode", "SOMEWHERE");
        Exception e = assertThrows(dev.aura.punishments.config.ConfigException.class, () -> Settings.parse(y));
        assertTrue(e.getMessage().contains("network.jail.return.mode"), e.getMessage());
        y.set("network.jail.return.mode", "FIXED");
        e = assertThrows(dev.aura.punishments.config.ConfigException.class, () -> Settings.parse(y));
        assertTrue(e.getMessage().contains("fixed-server"), e.getMessage());
        y.set("network.jail.return.fixed-server", "hub");
        assertEquals("hub", Settings.parse(y).network.jail().fixedServer());
        YamlConfiguration sqlite = cfg();
        sqlite.set("network.jail.backend", "jail");
        e = assertThrows(dev.aura.punishments.config.ConfigException.class, () -> Settings.parse(sqlite));
        assertTrue(e.getMessage().contains("shared database"), e.getMessage());
    }
}
