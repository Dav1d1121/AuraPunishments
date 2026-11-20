package dev.aura.punishments.service;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.Records.Audit;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Read-mostly administration: audit log, integrity check, dashboard counts, review queues. */
public final class AdminService extends Svc {

    public record Counts(Map<CaseStatus, Long> byStatus, long liveCustody, long overdueCustody, long pendingAppeals,
                         long unreviewedEmergencies, long unfinishedOps, long failedOps, long auditEntries, int cachedPunishments) {}

    private final PunishmentService punishments;

    public AdminService(Core core, PunishmentService punishments) {
        super(core);
        this.punishments = punishments;
    }

    public CompletableFuture<Result<List<Audit>>> audit(Actor actor, int limit, int offset) {
        Result<Void> p = core.auth.require(actor, Perms.AUDIT_VIEW, "view the audit log");
        if (!p.isOk()) return done(p.cast());
        return db(c -> Result.ok(Repo.Audits.recent(c, limit, offset)));
    }

    /** @return -1 when the audit chain is intact, otherwise the id of the first damaged entry. */
    public CompletableFuture<Result<Long>> verifyAudit(Actor actor) {
        Result<Void> p = core.auth.require(actor, Perms.ADMIN, "verify the audit log");
        if (!p.isOk()) return done(p.cast());
        return db(c -> Result.ok(Repo.Audits.verify(c)));
    }

    public CompletableFuture<Result<Counts>> counts(Actor actor) {
        Result<Void> p = core.auth.requireAny(actor, "view the dashboard", Perms.DASHBOARD, Perms.ADMIN, Perms.JUDICIAL_VIEW);
        if (!p.isOk()) return done(p.cast());
        return db(c -> {
            Map<CaseStatus, Long> by = new EnumMap<>(CaseStatus.class);
            for (CaseStatus s : CaseStatus.values()) by.put(s, Repo.Cases.countByStatuses(c, List.of(s)));
            long now = core.clock.now();
            var live = Repo.Custodies.allLive(c);
            long overdue = live.stream().filter(cu -> cu.overdue(now)).count();
            return Result.ok(new Counts(by, live.size(), overdue, Repo.Appeals.countPending(c),
                    Repo.Punishments.emergencyAwaitingReview(c).size(), Repo.Ops.countUnfinished(c), Repo.Ops.countFailed(c),
                    Repo.Audits.count(c), punishments.cache().size()));
        });
    }

    public CompletableFuture<Result<List<Punishment>>> pendingEmergencyReviews(Actor actor) {
        Result<Void> p = core.auth.require(actor, Perms.EMERGENCY_REVIEW, "review emergency actions");
        if (!p.isOk()) return done(p.cast());
        return db(c -> {
            List<Punishment> out = Repo.Punishments.emergencyAwaitingReview(c).stream().filter(x -> x.status() == PunishStatus.ACTIVE).toList();
            return Result.ok(out);
        });
    }

    public CompletableFuture<Result<Integer>> pruneAudit(Actor actor, long olderThanMillis) {
        Result<Void> p = core.auth.require(actor, Perms.ADMIN, "prune the audit log");
        if (!p.isOk()) return done(p.cast());
        if (olderThanMillis > core.clock.now()) return done(Result.fail(Code.INVALID, "Cutoff must be in the past."));
        return db(c -> {
            int n = Repo.Audits.prune(c, olderThanMillis);
            Repo.Audits.append(c, core.clock.now(), actor.dbUuid(), actor.name(), "audit.prune", null, null, "removed=" + n, true);
            return Result.ok(n);
        });
    }
}
