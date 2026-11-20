package dev.aura.punishments.model;

import java.util.Locale;

public enum PunishType {
    WARN("Warning", true, false),
    KICK("Kick", true, false),
    MUTE("Mute", false, true),
    BAN("Ban", false, true),
    IPBAN("IP Ban", false, true),
    JAIL("Jail Sentence", false, true);

    private final String display;
    private final boolean instant;
    private final boolean canBeTimed;

    PunishType(String display, boolean instant, boolean canBeTimed) {
        this.display = display;
        this.instant = instant;
        this.canBeTimed = canBeTimed;
    }

    public String display() {
        return display;
    }

    /** Instant punishments (warn, kick) have no running state; they are recorded as COMPLETED. */
    public boolean instant() {
        return instant;
    }

    public boolean canBeTimed() {
        return canBeTimed;
    }

    /** Lower-case token used in permission nodes, e.g. aurapunishments.punish.mute. */
    public String node() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PunishType parse(String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT).replace("-", ""));
    }
}
