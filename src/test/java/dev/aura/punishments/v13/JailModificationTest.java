package dev.aura.punishments.v13;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Appeal;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Records.Review;
import dev.aura.punishments.model.Simple.AppealStatus;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.aura.punishments.v13.V13Support.fx;
import static dev.aura.punishments.v13.V13Support.mod;
import static dev.aura.punishments.v13.V13Support.r;
import static org.junit.jupiter.api.Assertions.*;

/** A jail punishment that is shortened by an appeal or an owner review must also change JailReloaded's remaining time, or the two disagree. */
class JailModificationTest {
    Fx fx;
    Actor mod;
    Actor decider;
    Target steve;

    @BeforeEach
    void setUp() throws Exception {
        fx = fx(false, m -> m.set.put("review.required-types", List.of("JAIL", "BAN")));
        mod = mod("Mod");
        decider = Fx.staff("Reviewer", 60, Perms.HISTORY_VIEW, Perms.APPEAL_DECIDE, Perms.REVOKE_ANY, Perms.REVIEW_VIEW, Perms.REVIEW_REVIEW, Perms.REVIEW_MODIFY);
        steve = Fx.target("Steve");
        fx.online.add(steve.uuid());
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    Punishment jail(long dur) throws Exception {
        return r(fx.punishments.issueQuick(mod, new PunishmentService.Request(steve, PunishType.JAIL, dur, "Griefing the shared farm area.", List.of(Fx.ev())))).orThrow();
    }

    Appeal appeal(Punishment p) throws Exception {
        Actor support = Fx.staff("Support", 20, Perms.HISTORY_VIEW, Perms.APPEAL_FILE);
        return r(fx.appeals.file(support, p.id(), "The player says it was a misunderstanding.", "0042")).orThrow();
    }

    @Test
    void reducingAJailThroughAnAppealMovesJailReloadedsRemainingTimeByTheSameAmount() throws Exception {
        Punishment p = jail(Durations.DAY);
        Appeal a = appeal(p);
        fx.now += Durations.HOUR; // an hour has passed
        fx.jail.prisoners.computeIfPresent(steve.uuid(), (k, v) -> new dev.aura.punishments.integration.JailPort.PrisonerState(k, v.name(), v.jailPrisonerId(), v.jailName(),
                Durations.DAY - Durations.HOUR, false, false, v.reason(), v.jailer())); // JailReloaded counted that hour down
        var res = r(fx.appeals.decide(decider, a.id(), AppealStatus.MODIFIED, "First offence; reducing the jail time to two hours.", 2 * Durations.HOUR));
        assertTrue(res.isOk(), res.message());
        assertNull(res.message(), "nothing to warn about: JailReloaded followed");
        Punishment after = fx.db.txSync(c -> Repo.Punishments.get(c, p.id()));
        assertEquals(p.issuedAt() + 2 * Durations.HOUR, after.expiresAt());
        long remaining = fx.jail.prisoners.get(steve.uuid()).remainingMs();
        assertEquals(Durations.HOUR, remaining, "2h sentence minus the 1h already served");
        assertEquals(PunishStatus.ACTIVE, after.status());
        assertTrue(fx.db.<Long>txSync(c -> dev.aura.punishments.db.Sql.count(c, "SELECT COUNT(*) FROM audit_log WHERE action='jail.time'")) == 1);
    }

    @Test
    void ifJailReloadedRefusesTheNewTimeTheStaffMemberIsToldAndAnAlertIsRaised() throws Exception {
        Punishment p = jail(Durations.DAY);
        Appeal a = appeal(p);
        fx.jail.failNext = "JailReloaded is busy";
        var res = r(fx.appeals.decide(decider, a.id(), AppealStatus.MODIFIED, "First offence; reducing the jail time to two hours.", 2 * Durations.HOUR));
        assertTrue(res.isOk(), "the decision itself is recorded");
        assertNotNull(res.message());
        assertTrue(res.message().contains("did not accept the new time"), res.message());
        assertEquals(Durations.DAY, fx.jail.prisoners.get(steve.uuid()).remainingMs(), "JailReloaded was not changed");
        assertTrue(fx.events.stream().anyMatch(e -> e.type() == DomainEvent.Type.CUSTODY_ALERT && e.summary().contains("did not accept the new time")));
        assertTrue(fx.db.<Punishment>txSync(c -> Repo.Punishments.get(c, p.id())).jailError().contains("Time change not applied"));
    }

    @Test
    void aPermanentJailCanBeReducedToAFiniteTime() throws Exception {
        mod = Fx.staff("Perm", 30, Fx.concat(V13Support.punisher(), Perms.punishPermanent(PunishType.JAIL)));
        Punishment p = jail(Durations.PERMANENT);
        Appeal a = appeal(p);
        assertEquals(-1L, fx.jail.prisoners.get(steve.uuid()).remainingMs());
        fx.now += 10 * Durations.MINUTE;
        var res = r(fx.appeals.decide(decider, a.id(), AppealStatus.MODIFIED, "Reducing the permanent jail to one day.", Durations.DAY));
        assertTrue(res.isOk(), res.message());
        long remaining = fx.jail.prisoners.get(steve.uuid()).remainingMs();
        assertEquals(Durations.DAY - 10 * Durations.MINUTE, remaining);
    }

    @Test
    void overturningAJailPunishmentFreesThePrisoner() throws Exception {
        Punishment p = jail(Durations.DAY);
        Appeal a = appeal(p);
        r(fx.appeals.decide(decider, a.id(), AppealStatus.OVERTURNED, "New evidence shows the wrong player was jailed.", null)).orThrow();
        assertTrue(fx.jail.prisoners.isEmpty(), "the prisoner is out");
        Punishment after = fx.db.txSync(c -> Repo.Punishments.get(c, p.id()));
        assertEquals(PunishStatus.REVOKED, after.status());
        assertEquals(JailService.RELEASED, after.jailState());
    }

    @Test
    void anOwnerReviewModificationOfAJailMovesJailReloadedToo() throws Exception {
        Punishment p = jail(Durations.DAY);
        Review rv = fx.db.txSync(c -> Repo.Reviews.byPunishment(c, p.id()));
        assertNotNull(rv, "JAIL is listed in review.required-types, so it is submitted for owner review");
        assertTrue(fx.punishments.banFor(steve.uuid(), null).isEmpty() && fx.jail.prisoners.containsKey(steve.uuid()), "and the jail is already in force");
        var res = r(fx.reviews.modify(decider, rv.id(), rv.version(), 6 * Durations.HOUR, null, "Too long for a first offence; shortening it."));
        assertTrue(res.isOk(), res.message());
        assertEquals(6 * Durations.HOUR, fx.jail.prisoners.get(steve.uuid()).remainingMs());
        var again = r(fx.reviews.modify(decider, rv.id(), rv.version() + 1, Durations.PERMANENT, null, "Trying to make it permanent."));
        assertFalse(again.isOk(), "a jail punishment cannot be made permanent by a modification");
    }

    @Test
    void aJailThatIsBeingReleasedCannotBeModified() throws Exception {
        Punishment p = jail(Durations.DAY);
        fx.db.txSync(c -> { Repo.Punishments.setJail(c, p.id(), JailService.RELEASING, null, null, null); return null; });
        Appeal a = appeal(p);
        var res = r(fx.appeals.decide(decider, a.id(), AppealStatus.MODIFIED, "Reducing the jail while it is being released.", Durations.HOUR));
        assertEquals(Code.INVALID, res.code());
    }
}
