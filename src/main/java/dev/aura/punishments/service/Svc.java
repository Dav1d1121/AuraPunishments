package dev.aura.punishments.service;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.Sql.SqlFunction;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Shared plumbing for services: DB hop with safe failure mapping, audit helper, target checks. */
abstract class Svc {
    protected final Core core;

    /** The unique id of the service operation (= one database transaction) running on this thread, or null outside one. */
    private static final ThreadLocal<String> OP = new ThreadLocal<>();

    static String opId() {
        return OP.get();
    }

    protected Svc(Core core) {
        this.core = core;
    }

    /** Thrown inside a transaction to roll everything back and surface a specific failure. */
    static final class Abort extends RuntimeException {
        final transient Result<?> result;

        Abort(Result<?> result) {
            super(result.message(), null, false, false);
            this.result = result;
        }
    }

    /**
     * Run a transaction. If the work returns a failed Result the whole transaction is rolled back, so a
     * refused operation can never leave partial writes behind. Database failures become a failed Result,
     * never a silent success.
     */
    protected <T> CompletableFuture<Result<T>> db(SqlFunction<Result<T>> work) {
        return core.db.tx(c -> {
            OP.set(java.util.UUID.randomUUID().toString());
            try {
                Result<T> r = work.apply(c);
                if (!r.isOk()) throw new Abort(r);
                return r;
            } finally {
                OP.remove();
            }
        }).handle((r, ex) -> ex == null ? r : this.<T>failure(ex));
    }

    @SuppressWarnings("unchecked")
    protected <T> Result<T> failure(Throwable ex) {
        Throwable t = ex;
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof Abort a) return (Result<T>) a.result;
        if (t instanceof SQLException) {
            core.log.warning("Database operation failed: " + t.getMessage());
            return Result.fail(Code.DB_ERROR, "The database is unavailable right now. Nothing was changed - try again shortly.");
        }
        core.log.severe("Unexpected error: " + t);
        if (core.settings.debug) t.printStackTrace();
        return Result.fail(Code.DB_ERROR, "An internal error occurred. Nothing was changed; the details are in the console.");
    }

    protected static <T> CompletableFuture<Result<T>> done(Result<T> r) {
        return CompletableFuture.completedFuture(r);
    }

    protected void audit(Connection c, Actor a, String action, UUID target, Long caseId, String detail,
                         boolean elevated) throws SQLException {
        Repo.Audits.append(c, core.clock.now(), a.dbUuid(), a.name(), action, target, caseId,
                detail == null ? "" : detail, elevated, opId());
    }

    /** Resolve the target's authority (online or offline) and apply hierarchy rules. */
    protected CompletableFuture<Result<Void>> checkTarget(Actor actor, UUID target) {
        if (actor.isPrivileged()) return done(Result.ok());
        return core.levels.resolve(target)
                .handle((auth, ex) -> {
                    if (ex != null || auth == null) {
                        // Fail closed: if authority cannot be determined, treat the target as protected.
                        core.log.warning("Could not resolve target authority: " + (ex == null ? "null" : ex.getMessage()));
                        return Result.<Void>fail(Code.HIERARCHY, "Could not verify that player's staff status; action refused for safety.");
                    }
                    return core.auth.checkTarget(actor, target, auth);
                });
    }

    protected Result<Void> targetAccess(Actor actor) {
        if (actor.isPrivileged() || actor.has(Perms.PUNISH_OTHERS)) return Result.ok();
        return Result.fail(Code.NO_PERMISSION, "You do not have permission to manage other players' disciplinary records.");
    }

    protected static String clip(String s, int n) {
        return dev.aura.punishments.util.Text.truncate(s, n);
    }
}
