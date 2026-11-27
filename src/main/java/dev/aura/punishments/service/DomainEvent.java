package dev.aura.punishments.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Something noteworthy happened. Routed to in-game staff alerts and Discord; never contains IPs. */
public record DomainEvent(Type type, Long caseId, String player, String actor, String summary,
                          Map<String, String> fields, String key, Info info) {

    /**
     * Structured facts about the punishment an event is about (null for events that are not about one punishment). The Discord
     * punishment log uses it to filter by type/event and to choose which fields to show; the sanitized public log uses only the
     * public subset. Never contains IP addresses or staff notes.
     *
     * @param event issued | modified | revoked | expired | appeal
     */
    public record Info(String type, long id, String reason, Long durationMs, boolean timed, Long expiresAt, String issuer, String origin,
                       String source, int evidence, String event, Long caseId) {}

    public DomainEvent(Type type, Long caseId, String player, String actor, String summary, Map<String, String> fields, String key) {
        this(type, caseId, player, actor, summary, fields, key, null);
    }

    /** Discord log destinations; see {@code discord.channels} in config.yml. */
    public static final class Cat {
        public static final String PUNISHMENT = "punishment";
        public static final String JUDICIAL = "judicial";
        public static final String CUSTODY = "custody";
        public static final String OWNER_REVIEW = "owner-review";
        public static final String DIRECT = "direct-emergency";
        public static final String APPEAL = "appeal";
        public static final String ADMIN = "administrative";

        private Cat() {}
    }

    public enum Type {
        CASE_CREATED("case-created", "case-created", Cat.JUDICIAL, 0x3498DB, "New disciplinary case"),
        CASE_STATUS("case-status", "case-created", Cat.JUDICIAL, 0x5DADE2, "Case status changed"),
        CUSTODY_ATTEMPT("custody-attempt", "custody-started", Cat.CUSTODY, 0xF5B041, "Pretrial custody requested"),
        CUSTODY_STARTED("custody-started", "custody-started", Cat.CUSTODY, 0xE67E22, "Pretrial custody confirmed"),
        CUSTODY_FAILED("custody-failed", "custody-alerts", Cat.CUSTODY, 0xC0392B, "Custody operation failed"),
        CUSTODY_RELEASED("custody-released", "custody-started", Cat.CUSTODY, 0x2ECC71, "Custody released"),
        CUSTODY_ALERT("custody-alert", "custody-alerts", Cat.CUSTODY, 0xFF9800, "Custody alert"),
        TRIAL_SCHEDULED("trial-scheduled", "case-created", Cat.JUDICIAL, 0x9B59B6, "Trial scheduled"),
        TRIAL_POSTPONED("trial-postponed", "case-created", Cat.JUDICIAL, 0x8E44AD, "Trial postponed"),
        VERDICT("verdict", "verdicts", Cat.JUDICIAL, 0xF1C40F, "Verdict entered"),
        SENTENCE("sentence", "verdicts", Cat.JUDICIAL, 0xE74C3C, "Sentence imposed"),
        DIRECT("direct-punishment", "direct-punishments", Cat.DIRECT, 0xC0392B, "Direct punishment issued"),
        EMERGENCY("emergency-action", "emergency", Cat.DIRECT, 0xFF0000, "Emergency protective action"),
        WARNING("warning", "warnings", Cat.PUNISHMENT, 0xF39C12, "Official warning issued"),
        QUICK_PUNISHMENT("quick-punishment", "quick-punishments", Cat.PUNISHMENT, 0xD35400, "Quick punishment issued"),
        REVERSAL("reversal", "verdicts", Cat.PUNISHMENT, 0x95A5A6, "Punishment reversed"),
        PUNISHMENT_MODIFIED("punishment-modified", "verdicts", Cat.PUNISHMENT, 0x7F8C8D, "Punishment modified"),
        REVIEW_CREATED("review-created", "reviews", Cat.OWNER_REVIEW, 0x2980B9, "Owner review requested"),
        REVIEW_DECISION("review-decision", "reviews", Cat.OWNER_REVIEW, 0x27AE60, "Owner review decision"),
        REVIEW_EVIDENCE("review-evidence", "reviews", Cat.OWNER_REVIEW, 0x16A085, "Review evidence update"),
        REVIEW_REMINDER("review-reminder", "reviews", Cat.OWNER_REVIEW, 0xF1C40F, "Review reminder"),
        REVIEW_OVERDUE("review-overdue", "reviews", Cat.OWNER_REVIEW, 0xE74C3C, "Review overdue"),
        APPEAL("appeal", "verdicts", Cat.APPEAL, 0x1ABC9C, "Appeal"),
        APPEAL_TICKET("appeal-ticket", "verdicts", Cat.APPEAL, 0x48C9B0, "Appeal ticket linked"),
        PUNISHMENT_EXPIRED("punishment-expired", "verdicts", Cat.PUNISHMENT, 0x7FB3D5, "Punishment ended"),
        ADMIN_ACTION("admin-action", "admin", Cat.ADMIN, 0x34495E, "Administrative action"),
        EXPORT("export", "admin", Cat.ADMIN, 0x566573, "Case summary exported"),
        INTEGRATION_ERROR("integration-error", "admin", Cat.ADMIN, 0xB03A2E, "Integration problem"),
        SECURITY("security", "admin", Cat.ADMIN, 0x922B21, "Security event");

        public final String discordKey;
        public final String notifyKey;
        public final String category;
        public final int color;
        public final String title;

        Type(String discordKey, String notifyKey, String category, int color, String title) {
            this.discordKey = discordKey;
            this.notifyKey = notifyKey;
            this.category = category;
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
        private String key;
        private Info info;

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

        /** Stable identity of the underlying fact (e.g. "warning:123"); the same key is never posted to the same destination twice. */
        public Builder key(String k) {
            this.key = k;
            return this;
        }

        /** Attach structured punishment facts (see {@link Info}). */
        public Builder punishment(dev.aura.punishments.model.Records.Punishment p, String event, int evidenceCount) {
            if (p == null) return this;
            Long dur = p.type().canBeTimed() ? (p.expiresAt() == null ? null : p.expiresAt() - p.issuedAt()) : null;
            this.info = new Info(p.type().name(), p.id(), p.reason(), dur, p.type().canBeTimed(), p.expiresAt(), p.issuerName(), p.origin(),
                    p.source().name(), evidenceCount, event, p.caseId());
            if (this.caseId == null) this.caseId = p.caseId();
            return this;
        }

        public DomainEvent build() {
            return new DomainEvent(type, caseId, player, actor, summary, Map.copyOf(fields), key, info);
        }
    }

    @FunctionalInterface
    public interface Sink {
        void emit(DomainEvent e);
    }
}
