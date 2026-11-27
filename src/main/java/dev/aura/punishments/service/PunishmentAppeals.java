package dev.aura.punishments.service;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.Records.Appeal;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.AppealStatus;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.model.Simple.Source;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import dev.aura.punishments.util.Text;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Appeals against a punishment, handled through Discord Ticket Tool: the player opens a ticket on Discord; staff record the
 * appeal here against the punishment and reference that ticket. AuraPunishments never reads or writes Discord tickets; the
 * reference is only a label so everyone can find the conversation.
 *
 * <p>An appeal does NOT suspend the punishment. It is decided by uphold, reduce (modify) or overturn (revoke); the decision,
 * reviewer and rationale are recorded and the punishment's own history shows the outcome. Deciding needs
 * {@code aurapunishments.appeal.decide}; while the judicial system is on, {@code judicial.overturn} is accepted as well.
 * The hierarchy rules apply: you cannot decide an appeal for a player above you.
 */
public final class PunishmentAppeals extends Svc {
    private final PunishmentService punishments;

    public PunishmentAppeals(Core core, PunishmentService punishments) {
        super(core);
        this.punishments = punishments;
    }

    private Result<Void> mayDecide(Actor actor) {
        return core.mode.enabled()
                ? core.auth.requireAny(actor, "decide appeals", Perms.APPEAL_DECIDE, Perms.JUDICIAL_OVERTURN)
                : core.auth.require(actor, Perms.APPEAL_DECIDE, "decide appeals");
    }

    /** True when this punishment is appealed through its case (judicial system on, sentenced/direct case) rather than here. */
    public boolean viaCase(Punishment p) {
        return core.mode.enabled() && p.caseId() != null && (p.source() == Source.JUDICIAL || p.source() == Source.DIRECT);
    }

    // ------------------------------------------------------------------ file
    public CompletableFuture<Result<Appeal>> file(Actor actor, long punishmentId, String reason, String ticketRaw) {
        Result<Void> perm = core.auth.require(actor, Perms.APPEAL_FILE, "file appeals");
        if (!perm.isOk()) return done(perm.cast());
        String ticket = ticketRaw == null || ticketRaw.isBlank() ? null : JudicialService.cleanTicket(ticketRaw);
        if (ticketRaw != null && !ticketRaw.isBlank() && ticket == null) {
            return done(Result.fail(Code.INVALID, "That does not look like a ticket number or a Discord ticket link."));
        }
        if (ticket == null && core.settings.appeals.requireTicket()) {
            return done(Result.fail(Code.INVALID, "Link the Discord Ticket Tool ticket for this appeal (the server requires one)."));
        }
        String why = Text.clean(reason);
        int min = Math.max(core.settings.cases.minReason(), core.settings.appeals.minReason());
        if (why.length() < min) return done(Result.fail(Code.INVALID, "State the grounds for the appeal (at least " + min + " characters)."));
        if (why.length() > core.settings.cases.maxDescription()) return done(Result.fail(Code.INVALID, "The grounds are too long."));
        return db(c -> {
            Punishment p = Repo.Punishments.get(c, punishmentId);
            if (p == null) return Result.<Appeal>fail(Code.NOT_FOUND, "Punishment #" + punishmentId + " does not exist.");
            if (viaCase(p)) return Result.<Appeal>fail(Code.STATE, "That punishment came out of a case; record the appeal on the case instead.");
            if (p.status() != PunishStatus.ACTIVE && p.status() != PunishStatus.COMPLETED) {
                return Result.<Appeal>fail(Code.STATE, "Punishment #" + punishmentId + " is " + p.status().name().toLowerCase() + "; only a punishment in force or on record can be appealed.");
            }
            if (Repo.Appeals.pendingForPunishment(c, punishmentId) != null) {
                return Result.<Appeal>fail(Code.DUPLICATE, "There is already an open appeal on this punishment.");
            }
            long now = core.clock.now();
            long id = Repo.Appeals.addForPunishment(c, punishmentId, actor.name(), why, ticket, now);
            audit(c, actor, "appeal.file", p.targetUuid(), p.caseId(), "punishment=" + punishmentId + " appeal=" + id + (ticket == null ? "" : " ticket=" + ticket), false);
            return Result.ok(Repo.Appeals.get(c, id));
        }).thenApply(res -> {
            if (res.isOk()) emit(res.value(), actor, "Appeal filed", "appeal-filed:" + res.value().id(), why);
            return res;
        });
    }

    /** Attach or replace the Discord Ticket Tool ticket for an appeal. */
    public CompletableFuture<Result<Appeal>> linkTicket(Actor actor, long appealId, String ticketRaw) {
        Result<Void> perm = core.auth.requireAny(actor, "link appeal tickets", Perms.APPEAL_TICKET, Perms.APPEAL_DECIDE, Perms.JUDICIAL_OVERTURN);
        if (!perm.isOk()) return done(perm.cast());
        String ticket = JudicialService.cleanTicket(ticketRaw);
        if (ticket == null) {
            return done(Result.fail(Code.INVALID, "Enter the Ticket Tool ticket number (for example 0042 or appeal-0042) or the ticket channel link (https://discord.com/channels/...)."));
        }
        return db(c -> {
            Appeal a = Repo.Appeals.get(c, appealId);
            if (a == null || a.punishmentId() == null) return Result.<Appeal>fail(Code.NOT_FOUND, "Appeal " + appealId + " does not exist.");
            Repo.Appeals.setTicket(c, appealId, ticket);
            Punishment p = Repo.Punishments.get(c, a.punishmentId());
            audit(c, actor, "appeal.ticket", p == null ? null : p.targetUuid(), p == null ? null : p.caseId(), "appeal=" + appealId + " ticket=" + ticket, false);
            return Result.ok(Repo.Appeals.get(c, appealId));
        }).thenApply(res -> {
            if (res.isOk()) emit(res.value(), actor, "Appeal ticket linked", "appeal-ticket:" + appealId + ":" + ticket, null);
            return res;
        });
    }

    /** A reviewer takes the appeal (status "Under Review"). */
    public CompletableFuture<Result<Appeal>> take(Actor actor, long appealId) {
        Result<Void> perm = mayDecide(actor);
        if (!perm.isOk()) return done(perm.cast());
        return db(c -> {
            Appeal a = Repo.Appeals.get(c, appealId);
            if (a == null || a.punishmentId() == null) return Result.<Appeal>fail(Code.NOT_FOUND, "Appeal " + appealId + " does not exist.");
            if (!a.status().open()) return Result.<Appeal>fail(Code.STATE, "That appeal has already been decided.");
            if (a.status() == AppealStatus.UNDER_REVIEW) return Result.<Appeal>fail(Code.STATE, "That appeal is already under review by " + a.assignedName() + ".");
            Repo.Appeals.setStatus(c, appealId, AppealStatus.UNDER_REVIEW, actor.name());
            Punishment p = Repo.Punishments.get(c, a.punishmentId());
            audit(c, actor, "appeal.take", p == null ? null : p.targetUuid(), p == null ? null : p.caseId(), "appeal=" + appealId, false);
            return Result.ok(Repo.Appeals.get(c, appealId));
        }).thenApply(res -> {
            if (res.isOk()) emit(res.value(), actor, "Appeal is now under review", "appeal-taken:" + appealId, null);
            return res;
        });
    }

    // ------------------------------------------------------------------ decide
    /**
     * @param decision      UPHELD, OVERTURNED (the punishment is revoked) or MODIFIED (reduced)
     * @param newDurationMs for MODIFIED: the new total length measured from when the punishment was issued; must be shorter
     *                      than the current one
     */
    public CompletableFuture<Result<Appeal>> decide(Actor actor, long appealId, AppealStatus decision, String reasoning, Long newDurationMs) {
        Result<Void> perm = mayDecide(actor);
        if (!perm.isOk()) return done(perm.cast());
        if (decision != AppealStatus.UPHELD && decision != AppealStatus.OVERTURNED && decision != AppealStatus.MODIFIED) {
            return done(Result.fail(Code.INVALID, "Choose upheld, overturned or reduced."));
        }
        String why = Text.clean(reasoning);
        int min = Math.max(5, core.settings.judicial.minVerdictReason());
        if (why.length() < min) return done(Result.fail(Code.INVALID, "Explain the decision (at least " + min + " characters)."));
        if (why.length() > 1000) return done(Result.fail(Code.INVALID, "The explanation is too long."));
        if (decision == AppealStatus.MODIFIED && (newDurationMs == null || newDurationMs <= 0)) {
            return done(Result.fail(Code.INVALID, "Enter the reduced duration."));
        }
        // hierarchy is judged against the appealing player, so look the target up first
        return core.db.tx(c -> {
            Appeal a = Repo.Appeals.get(c, appealId);
            return a == null || a.punishmentId() == null ? null : Repo.Punishments.get(c, a.punishmentId());
        }).handle((p, ex) -> ex == null ? p : null).thenCompose(p0 -> {
            if (p0 == null) return done(Result.<Appeal>fail(Code.NOT_FOUND, "Appeal " + appealId + " does not exist."));
            return checkTarget(actor, p0.targetUuid()).thenCompose(tc -> {
                if (!tc.isOk()) return done(tc.<Appeal>cast());
                return decideNow(actor, appealId, decision, why, newDurationMs);
            });
        });
    }

    private CompletableFuture<Result<Appeal>> decideNow(Actor actor, long appealId, AppealStatus decision, String why, Long newDurationMs) {
        Result<Punishment>[] effect = new Result[1];
        return db(c -> {
            Appeal a = Repo.Appeals.get(c, appealId);
            if (a == null || a.punishmentId() == null) return Result.<Appeal>fail(Code.NOT_FOUND, "Appeal " + appealId + " does not exist.");
            if (!a.status().open()) return Result.<Appeal>fail(Code.STATE, "That appeal has already been decided.");
            Punishment p = Repo.Punishments.get(c, a.punishmentId());
            if (p == null) return Result.<Appeal>fail(Code.NOT_FOUND, "The punishment for that appeal is missing.");
            long now = core.clock.now();
            Long newExpires = null;
            switch (decision) {
                case OVERTURNED -> {
                    if (p.status() == PunishStatus.ACTIVE || p.status() == PunishStatus.COMPLETED) {
                        Result<Punishment> r = punishments.revokeTx(c, actor, p.id(), "Appeal overturned: " + Text.truncate(why, 150), true);
                        if (!r.isOk()) return r.<Appeal>cast();
                        effect[0] = r;
                    }
                }
                case MODIFIED -> {
                    if (!p.type().canBeTimed()) return Result.<Appeal>fail(Code.INVALID, "A " + p.type().display().toLowerCase() + " has no duration to reduce. Uphold or overturn the appeal.");
                    if (p.status() != PunishStatus.ACTIVE) return Result.<Appeal>fail(Code.STATE, "That punishment is no longer in force, so it cannot be reduced. Uphold or overturn the appeal.");
                    long current = p.expiresAt() == null ? Long.MAX_VALUE : p.expiresAt() - p.issuedAt();
                    if (newDurationMs >= current) return Result.<Appeal>fail(Code.INVALID, "An appeal can only reduce the punishment; the new duration must be shorter than the current one.");
                    newExpires = p.issuedAt() + newDurationMs;
                    Result<Punishment> r = punishments.modifyTx(c, actor, p.id(), newExpires, null);
                    if (!r.isOk()) return r.<Appeal>cast();
                    effect[0] = r;
                }
                default -> { }
            }
            Repo.Appeals.decide(c, appealId, decision, actor.name(), why, now, newExpires);
            audit(c, actor, "appeal.decide", p.targetUuid(), p.caseId(), "punishment=" + p.id() + " appeal=" + appealId + " " + decision
                    + (newExpires == null ? "" : " new-duration=" + Durations.format(newDurationMs)) + " " + Text.truncate(why, 100), true);
            return Result.ok(Repo.Appeals.get(c, appealId));
        }).thenCompose(res -> {
            if (!res.isOk()) return done(res);
            CompletableFuture<?> after = CompletableFuture.completedFuture(null);
            if (effect[0] != null && effect[0].isOk()) {
                Punishment changed = effect[0].value();
                after = decision == AppealStatus.OVERTURNED ? punishments.afterRevoke(actor, effect[0], "Appeal overturned: " + Text.truncate(why, 150)).thenApply(x -> null)
                        : CompletableFuture.runAsync(() -> punishments.afterModify(changed));
            }
            return after.handle((x, ex) -> null).thenApply(v -> {
                emit(res.value(), actor, "Appeal " + decision.name().toLowerCase().replace("modified", "reduced"), "appeal-decided:" + appealId, why);
                return res;
            });
        });
    }

    // ------------------------------------------------------------------ queue
    public CompletableFuture<Result<CaseService.Page<Appeal>>> open(Actor actor, int page, int pageSize) {
        Result<Void> perm = core.auth.requireAny(actor, "view appeals", Perms.APPEAL_DECIDE, Perms.JUDICIAL_OVERTURN, Perms.APPEAL_FILE, Perms.APPEAL_TICKET, Perms.HISTORY_VIEW);
        if (!perm.isOk()) return done(perm.cast());
        return db(c -> Result.ok(new CaseService.Page<>(Repo.Appeals.openForPunishments(c, pageSize, page * pageSize),
                Repo.Appeals.countPendingForPunishments(c), page, pageSize)));
    }

    public CompletableFuture<Result<List<Appeal>>> forPunishment(Actor actor, long punishmentId) {
        if (!core.auth.has(actor, Perms.HISTORY_VIEW)) return done(Result.fail(Code.NO_PERMISSION, "You do not have permission to view punishment history."));
        return db(c -> Result.ok(Repo.Appeals.forPunishment(c, punishmentId)));
    }

    private void emit(Appeal a, Actor actor, String summary, String key, String reasoning) {
        core.db.tx(c -> a.punishmentId() == null ? null : Repo.Punishments.get(c, a.punishmentId())).thenAccept(p -> {
            DomainEvent.Builder b = DomainEvent.of(summary.contains("ticket") ? DomainEvent.Type.APPEAL_TICKET : DomainEvent.Type.APPEAL)
                    .key(key + ":" + a.status()).actor(actor.name()).summary(summary + (p == null ? "" : " (punishment #" + p.id() + ")"))
                    .field("Ticket", a.ticket()).field("Reasoning", reasoning == null ? null : Text.truncate(reasoning, 300))
                    .field("Decision", a.status().open() ? null : a.status().name());
            if (p != null) b.caseId(p.caseId()).player(p.targetName()).punishment(p, "appeal", 0);
            core.emit(b.build());
        }).exceptionally(t -> null);
    }
}
