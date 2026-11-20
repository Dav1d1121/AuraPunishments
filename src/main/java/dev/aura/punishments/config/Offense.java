package dev.aura.punishments.config;

import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.util.Durations;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record Offense(String key, String display, String description, String icon, Severity severity,
                      boolean directEligible, String permission, int minEvidenceCase, int minEvidenceDirect,
                      Set<PunishType> allowed, long maxDuration, List<Recommendation> recommendations,
                      Map<PunishType, List<Long>> presets) {

    /** @param duration 0 for instant types, {@link Durations#PERMANENT} for permanent. */
    public record Recommendation(int priors, PunishType type, long duration) {
        public String describe() {
            if (type.instant() || !type.canBeTimed()) return type.display();
            return type.display() + " (" + Durations.format(duration) + ")";
        }
    }

    /** Recommendation for a player with the given number of prior guilty findings. */
    public Recommendation recommend(long priors) {
        Recommendation best = null;
        for (Recommendation r : recommendations) {
            if (r.priors() <= priors && (best == null || r.priors() >= best.priors())) best = r;
        }
        return best;
    }

    public boolean allows(PunishType t) {
        return allowed.contains(t);
    }

    /** True when the duration exceeds this offense's cap. */
    public boolean exceedsCap(long duration) {
        return maxDuration != Durations.PERMANENT && duration > maxDuration;
    }
}
