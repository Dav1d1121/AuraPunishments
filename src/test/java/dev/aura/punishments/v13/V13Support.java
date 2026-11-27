package dev.aura.punishments.v13;

import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Result;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Shared helpers for the 1.3.0 tests. */
final class V13Support {
    private V13Support() {}

    /** A fixture with the judicial system switched on or off (the shipped file for a fresh install has it off). */
    static Fx fx(boolean judicial) throws Exception {
        Fx.Mutator m = new Fx.Mutator();
        m.set.put("judicial.enabled", judicial);
        return new Fx(null, m);
    }

    static Fx fx(boolean judicial, java.util.function.Consumer<Fx.Mutator> more) throws Exception {
        Fx.Mutator m = new Fx.Mutator();
        m.set.put("judicial.enabled", judicial);
        more.accept(m);
        return new Fx(null, m);
    }

    static <T> Result<T> r(CompletableFuture<Result<T>> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    /** Every punishment type, with a duration tier for each timed one. */
    static String[] punisher() {
        return new String[]{Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.HISTORY_VIEW, Perms.EVIDENCE_ADD, Perms.EVIDENCE_VIEW, Perms.NOTE_ADD, Perms.NOTE_VIEW,
                Perms.punish(PunishType.WARN), Perms.punish(PunishType.KICK), Perms.punish(PunishType.MUTE), Perms.punish(PunishType.BAN),
                Perms.punish(PunishType.IPBAN), Perms.punish(PunishType.JAIL), Perms.REVOKE,
                Perms.limit(PunishType.MUTE, "extended"), Perms.limit(PunishType.BAN, "extended"), Perms.limit(PunishType.IPBAN, "long"),
                Perms.limit(PunishType.JAIL, "extended"), Perms.APPEAL_FILE};
    }

    static Actor mod(String name) {
        return Fx.staff(name, 20, punisher());
    }
}
