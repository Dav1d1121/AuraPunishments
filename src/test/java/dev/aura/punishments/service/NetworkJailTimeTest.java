package dev.aura.punishments.service;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.v14.NetworkJailTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A jail shortened or lengthened on a backend without JailReloaded is carried out on the jail backend (this lives in the service package to reach the modification internals). */
class NetworkJailTimeTest {
    Fx survival;
    Fx jail;
    Actor mod = Fx.staff("Mod", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.HISTORY_VIEW, Perms.EVIDENCE_ADD, Perms.punish(PunishType.JAIL),
            Perms.limit(PunishType.JAIL, "extended"), Perms.REVOKE, Perms.REVOKE_ANY);

    @BeforeEach
    void start() throws Exception {
        Assumptions.assumeTrue(dev.aura.punishments.v14.NetworkTest.reachable(), "no MariaDB server");
        survival = NetworkJailTest.server("survival", null, false);
        jail = NetworkJailTest.server("jail", survival.sharedDatabaseName(), false);
        jail.now = survival.now;
        survival.netJail.refresh().get();
        jail.netJail.refresh().get();
    }

    @AfterEach
    void stop() {
        if (jail != null) jail.close();
        if (survival != null) survival.close();
    }

    Punishment row(long id) throws Exception {
        return survival.db.txSync(c -> Repo.Punishments.get(c, id));
    }

    void sync() throws Exception {
        jail.jails.networkWork().get(20, TimeUnit.SECONDS);
    }

    Punishment order(dev.aura.punishments.service.PlayerResolver.Target steve) throws Exception {
        return survival.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.JAIL, Durations.HOUR, "Repeated griefing of builds.", List.of(Fx.ev())))
                .get(20, TimeUnit.SECONDS).orThrow();
    }

    @Test
    void aTimeChangeOrderedElsewhereMovesJailReloadedsRemainingTimeByTheSameDifference() throws Exception {
        var steve = Fx.target("Steve");
        Punishment p = order(steve);
        sync();
        long before = jail.jail.find(steve.uuid()).orElseThrow().remainingMs();
        Punishment was = row(p.id());
        var changed = survival.db.txSync(c -> survival.punishments.modifyTx(c, mod, p.id(), was.expiresAt() + 30 * Durations.MINUTE, null)).orThrow();
        var res = survival.punishments.afterModify(mod, was, changed).get(20, TimeUnit.SECONDS);
        assertTrue(res.isOk(), res.message());
        assertTrue(jail.jail.calls.stream().noneMatch(c -> c.startsWith("setRemaining")), "not before the jail backend gets to it");
        sync();
        assertEquals(before + 30 * Durations.MINUTE, jail.jail.find(steve.uuid()).orElseThrow().remainingMs());
        long again = jail.jail.find(steve.uuid()).orElseThrow().remainingMs();
        sync();
        assertEquals(again, jail.jail.find(steve.uuid()).orElseThrow().remainingMs(), "applied once, never twice");
    }

    @Test
    void aQueuedJailTakesItsNewEndFromThePunishmentItself() throws Exception {
        var steve = Fx.target("Steve");
        Punishment p = order(steve);
        Punishment was = row(p.id());
        var changed = survival.db.txSync(c -> survival.punishments.modifyTx(c, mod, p.id(), was.expiresAt() + 30 * Durations.MINUTE, null)).orThrow();
        survival.punishments.afterModify(mod, was, changed).get(20, TimeUnit.SECONDS);
        sync();
        long left = jail.jail.find(steve.uuid()).orElseThrow().remainingMs();
        assertTrue(Math.abs(left - 90 * Durations.MINUTE) < 5000, "placed with the lengthened sentence: " + left);
    }
}
