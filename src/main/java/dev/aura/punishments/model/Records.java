package dev.aura.punishments.model;

import dev.aura.punishments.model.Simple.*;
import dev.aura.punishments.model.PunishType;

import java.util.UUID;

/** Immutable row snapshots. Rows are only changed through the services. */
public final class Records {
    private Records() {}

    public record Profile(UUID uuid, String name, long firstSeen, long lastSeen, String lastIp, int level) {}

    public record Case(long id, CaseStatus status, CaseKind kind, UUID accusedUuid, String accusedName,
                       String offenseKey, Severity severity, String description, String context,
                       UUID prosecutorUuid, String prosecutorName, UUID judgeUuid, String judgeName,
                       Long trialAt, String hearingRef, Verdict verdict, String verdictReason,
                       String verdictByName, Long verdictAt, boolean overturned,
                       long createdAt, long updatedAt, Long closedAt, long version, String appealTicket,
                       String uid, String origin) {
        public String ref() {
            return "AP-" + String.format("%04d", id);
        }
    }

    public record CaseEvent(long id, long caseId, long ts, String actorName, String type,
                            String fromStatus, String toStatus, String detail) {}

    /** Evidence belongs to a case or, when the judicial system is off, directly to a punishment (exactly one of the two). */
    public record Evidence(long id, Long caseId, Long punishmentId, EvidenceType type, String reference, String description,
                           UUID submittedBy, String submittedName, long submittedAt,
                           boolean voided, String voidReason, String voidedName, Long voidedAt, Long correctsId,
                           String uid, String origin) {}

    public record Note(long id, Long caseId, UUID profileUuid, UUID authorUuid, String authorName,
                       String text, long createdAt, boolean sensitive, boolean voided, String voidReason, Long punishmentId) {}

    public record Custody(long id, long caseId, UUID uuid, String name, CustodyStatus status,
                          String requestedName, long requestedAt, Long startedAt, Long deadline,
                          int extensions, String jailName, String jailPrisonerId,
                          Long endedAt, String endedBy, String endReason,
                          String alertsSent, String lastError, long updatedAt) {
        public boolean overdue(long now) {
            return deadline != null && status == CustodyStatus.ACTIVE && now > deadline;
        }
    }

    public record Punishment(long id, PunishType type, PunishStatus status, UUID targetUuid, String targetName,
                             String ip, String reason, UUID issuerUuid, String issuerName,
                             long issuedAt, Long expiresAt, Long endedAt, String endedBy, String endReason,
                             Long caseId, Source source, String offenseKey, boolean delivered,
                             Long reviewDeadline, boolean reviewed, Long custodyId,
                             Long modifiedAt, String modifiedBy, Long originalExpiresAt,
                             String uid, String origin, String opId,
                             String jailPrisonerId, String jailName, String jailState, String jailError) {
        public boolean permanent() {
            return expiresAt == null;
        }

        /** How this punishment came about; shown identically in the GUI, audit detail and Discord. */
        public String classification() {
            return switch (source) {
                case JUDICIAL -> "Judicial Sentence";
                case DIRECT -> "Direct Punishment";
                case EMERGENCY -> "Emergency Protective Action";
                case QUICK -> type == PunishType.WARN ? "Routine Warning" : type == PunishType.KICK ? "Quick Kick" : "Emergency Quick Punishment";
                case STANDARD -> type == PunishType.WARN ? "Warning" : type == PunishType.KICK ? "Kick" : "Punishment";
            };
        }

        public boolean activeAt(long now) {
            return status == PunishStatus.ACTIVE && (expiresAt == null || expiresAt > now);
        }
    }

    /** An appeal against a case outcome (judicial system on) or directly against a punishment. */
    public record Appeal(long id, Long caseId, Long punishmentId, String filedByName, long filedAt, String reason,
                         AppealStatus status, String decidedByName, String decisionReason, Long decidedAt, String assignedName,
                         String ticket, Long newExpiresAt, String uid, String origin) {}

    public record Audit(long id, long ts, UUID actorUuid, String actorName, String action,
                        UUID targetUuid, Long caseId, String detail, boolean elevated,
                        String prevHash, String hash, String origin, String opId) {}

    public record PendingOp(long id, String kind, OpState state, Long caseId, Long custodyId, UUID uuid,
                            String payload, int attempts, long createdAt, long updatedAt, String lastError, Long punishmentId) {}

    public record Review(long id, Long punishmentId, Long caseId, UUID targetUuid, String targetName, UUID issuerUuid,
                         String issuerName, PunishType ptype, Long durationMs, String reason, long issuedAt,
                         ReviewStatus status, Long evidenceDue, Long reviewDue, String reviewerName, Long decidedAt,
                         String decisionNote, boolean migrated, int evidenceReminders, Long lastEvidenceReminder,
                         int reviewReminders, Long lastReviewReminder, long version, long updatedAt) {
        public String ref() {
            return "RV-" + String.format("%04d", id);
        }
    }

    public record ReviewEvent(long id, long reviewId, long ts, String actorName, String type, String detail) {}

    public record Notification(long id, String dedupeKey, UUID uuid, String kind, String refType, Long refId,
                               String payload, NotificationState state, long createdAt, Long deliveredAt, int attempts) {}

    public record OutboxRow(long id, String dedupeKey, String category, String event, String dest, String body,
                            OutboxState state, int attempts, long nextAttempt, long createdAt, Long sentAt, String lastError) {}
}
