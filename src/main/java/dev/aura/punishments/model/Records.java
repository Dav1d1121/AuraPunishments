package dev.aura.punishments.model;

import dev.aura.punishments.model.Simple.*;

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
                       long createdAt, long updatedAt, Long closedAt, long version) {
        public String ref() {
            return "AP-" + String.format("%04d", id);
        }
    }

    public record CaseEvent(long id, long caseId, long ts, String actorName, String type,
                            String fromStatus, String toStatus, String detail) {}

    public record Evidence(long id, long caseId, EvidenceType type, String reference, String description,
                           UUID submittedBy, String submittedName, long submittedAt,
                           boolean voided, String voidReason, String voidedName, Long voidedAt, Long correctsId) {}

    public record Note(long id, Long caseId, UUID profileUuid, UUID authorUuid, String authorName,
                       String text, long createdAt, boolean sensitive, boolean voided, String voidReason) {}

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
                             Long reviewDeadline, boolean reviewed, Long custodyId) {
        public boolean permanent() {
            return expiresAt == null;
        }

        public boolean activeAt(long now) {
            return status == PunishStatus.ACTIVE && (expiresAt == null || expiresAt > now);
        }
    }

    public record Appeal(long id, long caseId, String filedByName, long filedAt, String reason,
                         AppealStatus status, String decidedByName, String decisionReason, Long decidedAt) {}

    public record Audit(long id, long ts, UUID actorUuid, String actorName, String action,
                        UUID targetUuid, Long caseId, String detail, boolean elevated,
                        String prevHash, String hash) {}

    public record PendingOp(long id, String kind, OpState state, Long caseId, Long custodyId, UUID uuid,
                            String payload, int attempts, long createdAt, long updatedAt, String lastError) {}
}
