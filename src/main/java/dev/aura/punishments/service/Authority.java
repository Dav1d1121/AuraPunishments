package dev.aura.punishments.service;

import dev.aura.punishments.config.Settings;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The single place where "may this actor do that?" is decided. Having the GUI open or the
 * /punish permission never implies authority: every action asks here again at execution time.
 * Default is deny.
 */
public final class Authority {
    private final Supplier<Settings> settings;

    public Authority(Supplier<Settings> settings) {
        this.settings = settings;
    }

    public boolean has(Actor a, String node) {
        return a != null && a.has(node);
    }

    public Result<Void> require(Actor a, String node, String doWhat) {
        if (has(a, node)) return Result.ok();
        return Result.fail(Code.NO_PERMISSION, "You do not have permission to " + doWhat + ".");
    }

    public Result<Void> requireAny(Actor a, String doWhat, String... nodes) {
        for (String n : nodes) if (has(a, n)) return Result.ok();
        return Result.fail(Code.NO_PERMISSION, "You do not have permission to " + doWhat + ".");
    }

    /**
     * Hierarchy, self-targeting and immunity protection.
     *
     * @param target the player being acted on
     * @param auth   authority of that player (level + immunity)
     */
    public Result<Void> checkTarget(Actor actor, UUID target, LevelResolver.TargetAuth auth) {
        Settings s = settings.get();
        if (actor.isPrivileged()) return Result.ok();
        boolean bypass = actor.has(Perms.IMMUNITY_BYPASS);
        if (s.protections.blockSelf() && actor.uuid() != null && actor.uuid().equals(target) && !bypass) {
            return Result.fail(Code.HIERARCHY, "You cannot take disciplinary action against yourself.");
        }
        if (bypass) return Result.ok();
        if (auth.immune()) {
            return Result.fail(Code.HIERARCHY, "That player is immune to disciplinary action.");
        }
        if (s.protections.enforceHierarchy() && auth.level() >= actor.level() && !actor.uuid().equals(target)) {
            return Result.fail(Code.HIERARCHY, "You cannot act against a player of equal or higher staff authority.");
        }
        return Result.ok();
    }

    /** Longest timed duration the actor may issue for a type via the quick/judicial pathway; 0 = none. */
    public long maxDuration(Actor a, PunishType t) {
        if (a.isPrivileged()) return Durations.PERMANENT;
        if (!t.canBeTimed()) return 0;
        long best = 0;
        for (Map.Entry<String, Long> e : settings.get().tiersFor(t).entrySet()) {
            if (a.has(Perms.limit(t, e.getKey())) && e.getValue() > best) best = e.getValue();
        }
        return best;
    }

    /**
     * Check a requested duration against the actor's limits.
     *
     * @param direct true for direct punishments (uses the direct permission tree for permanence)
     */
    public Result<Void> checkDuration(Actor a, PunishType t, long durationMs, boolean direct) {
        if (!t.canBeTimed()) return Result.ok();
        if (a.isPrivileged()) return Result.ok();
        if (durationMs == Durations.PERMANENT) {
            String node = direct ? Perms.directPermanent(t) : Perms.punishPermanent(t);
            if (a.has(node)) return Result.ok();
            return Result.fail(Code.LIMIT, "You are not authorised to issue permanent " + t.display().toLowerCase() + "s.");
        }
        long max = maxDuration(a, t);
        if (durationMs > max) {
            String allowed = max == 0 ? "none" : Durations.format(max);
            return Result.fail(Code.LIMIT, "That duration exceeds your limit for " + t.display().toLowerCase()
                    + "s (your maximum: " + allowed + ").");
        }
        return Result.ok();
    }

    /** Whether the actor may use a punishment type at all via the quick pathway. */
    public boolean canQuick(Actor a, PunishType t) {
        return has(a, Perms.punish(t));
    }

    public boolean canDirect(Actor a, PunishType t) {
        return has(a, Perms.DIRECT) && has(a, Perms.direct(t));
    }

    public boolean canViewCase(Actor a, dev.aura.punishments.model.Records.Case c) {
        if (a.has(Perms.CASE_VIEW_ALL)) return true;
        if (a.has(Perms.CASE_VIEW_OWN) && a.dbUuid() != null) {
            return a.dbUuid().equals(c.prosecutorUuid()) || a.dbUuid().equals(c.judgeUuid());
        }
        return false;
    }

    public boolean canViewAnyCase(Actor a) {
        return a.has(Perms.CASE_VIEW_ALL) || a.has(Perms.CASE_VIEW_OWN);
    }
}
