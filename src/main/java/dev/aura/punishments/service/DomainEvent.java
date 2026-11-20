package dev.aura.punishments.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Something noteworthy happened. Routed to in-game staff alerts and Discord; never contains IPs. */
public record DomainEvent(Type type, Long caseId, String player, String actor, String summary,
                          Map<String, String> fields) {

    public enum Type {
        CASE_CREATED("case-created", "case-created", 0x3498DB, "New disciplinary case"),
        CUSTODY_STARTED("custody-started", "custody-started", 0xE67E22, "Pretrial custody started"),
        CUSTODY_RELEASED("custody-released", "custody-started", 0x2ECC71, "Custody released"),
        TRIAL_SCHEDULED("trial-scheduled", "case-created", 0x9B59B6, "Trial scheduled"),
        VERDICT("verdict", "verdicts", 0xF1C40F, "Verdict entered"),
        SENTENCE("sentence", "verdicts", 0xE74C3C, "Sentence imposed"),
        DIRECT("direct-punishment", "direct-punishments", 0xC0392B, "Direct punishment issued"),
        EMERGENCY("emergency-action", "emergency", 0xFF0000, "Emergency protective action"),
        APPEAL("appeal", "verdicts", 0x1ABC9C, "Appeal"),
        REVERSAL("reversal", "verdicts", 0x95A5A6, "Punishment reversed"),
        CUSTODY_ALERT("custody-alert", "custody-alerts", 0xFF9800, "Custody alert");

        public final String discordKey;
        public final String notifyKey;
        public final int color;
        public final String title;

        Type(String discordKey, String notifyKey, int color, String title) {
            this.discordKey = discordKey;
            this.notifyKey = notifyKey;
            this.color = color;
            this.title = title;
        }
    }

    public static Builder of(Type type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final Type type;
        private Long caseId;
        private String player = "";
        private String actor = "";
        private String summary = "";
        private final Map<String, String> fields = new LinkedHashMap<>();

        private Builder(Type type) {
            this.type = type;
        }

        public Builder caseId(Long id) {
            this.caseId = id;
            return this;
        }

        public Builder player(String p) {
            this.player = p == null ? "" : p;
            return this;
        }

        public Builder actor(String a) {
            this.actor = a == null ? "" : a;
            return this;
        }

        public Builder summary(String s) {
            this.summary = s == null ? "" : s;
            return this;
        }

        public Builder field(String k, String v) {
            if (v != null && !v.isBlank()) fields.put(k, v);
            return this;
        }

        public DomainEvent build() {
            return new DomainEvent(type, caseId, player, actor, summary, Map.copyOf(fields));
        }
    }

    @FunctionalInterface
    public interface Sink {
        void emit(DomainEvent e);
    }
}
