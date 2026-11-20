package dev.aura.punishments.model;

import java.util.Locale;

/** Small enums that do not warrant their own file each. */
public final class Simple {
    private Simple() {}

    public enum Verdict {
        GUILTY, NOT_GUILTY, DISMISSED, CONTINUED;

        public String display() {
            return switch (this) {
                case GUILTY -> "Guilty";
                case NOT_GUILTY -> "Not Guilty";
                case DISMISSED -> "Dismissed";
                case CONTINUED -> "Continued / Pending Review";
            };
        }
    }

    public enum Severity {
        MINOR, MODERATE, MAJOR, SEVERE;

        public static Severity parse(String s) {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum PunishStatus {
        ACTIVE, COMPLETED, EXPIRED, REVOKED, SERVED
    }

    /** How a punishment was reached. */
    public enum Source {
        JUDICIAL, DIRECT, EMERGENCY, QUICK
    }

    /** What kind of case record this is. */
    public enum CaseKind {
        STANDARD, DIRECT, EMERGENCY, REPORT, QUICK
    }

    public enum CustodyStatus {
        /** Intent recorded, JailReloaded not yet confirmed. */
        REQUESTED,
        /** JailReloaded confirmed the prisoner is held for this case. */
        ACTIVE,
        /** Release requested; JailReloaded will finish it (e.g. offline prisoner on next login). */
        RELEASING,
        RELEASED,
        /** The same JailReloaded record now backs a final jail sentence (no review deadline). */
        SERVING,
        /** JailReloaded refused or failed; no custody exists. */
        FAILED,
        /** The custody was ended outside AuraPunishments and has been reconciled. */
        ENDED_EXTERNALLY;

        public boolean isLive() {
            return this == REQUESTED || this == ACTIVE || this == RELEASING || this == SERVING;
        }
    }

    public enum EvidenceType {
        SCREENSHOT, VIDEO, CHAT_LOG, COREPROTECT, SERVER_LOG, INCIDENT_REPORT, ANTICHEAT, EXTERNAL_LINK, OTHER;

        public boolean isLink() {
            return this == SCREENSHOT || this == VIDEO || this == EXTERNAL_LINK;
        }

        public static EvidenceType parse(String s) {
            return valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    public enum AppealStatus {
        PENDING, UPHELD, OVERTURNED, MODIFIED
    }

    public enum OpState {
        PENDING, EXTERNAL_DONE, DONE, FAILED
    }
}
