package dev.aura.punishments.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a disciplinary case. The transition table below is the single source of truth:
 * {@link #canMoveTo(CaseStatus)} is consulted for every status change, so an invalid action
 * (for example sentencing an acquitted case) is rejected no matter which GUI or command asked.
 */
public enum CaseStatus {
    OPEN("Open"),
    UNDER_INVESTIGATION("Under Investigation"),
    AWAITING_TRIAL("Awaiting Trial"),
    IN_TRIAL("In Trial"),
    AWAITING_VERDICT("Awaiting Verdict"),
    GUILTY("Guilty (Awaiting Sentence)"),
    AWAITING_SENTENCING("Awaiting Sentencing"),
    NOT_GUILTY("Not Guilty"),
    DISMISSED("Dismissed"),
    SENTENCED("Sentenced"),
    DIRECT_PUNISHMENT_ISSUED("Direct Punishment Issued"),
    UNDER_REVIEW("Under Review"),
    APPEALED("Appealed"),
    CLOSED("Closed");

    private final String display;

    CaseStatus(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    public Set<CaseStatus> next() {
        return switch (this) {
            case OPEN -> EnumSet.of(UNDER_INVESTIGATION, AWAITING_TRIAL, UNDER_REVIEW, DISMISSED, CLOSED);
            case UNDER_INVESTIGATION -> EnumSet.of(OPEN, AWAITING_TRIAL, UNDER_REVIEW, DISMISSED, CLOSED);
            case AWAITING_TRIAL -> EnumSet.of(UNDER_INVESTIGATION, IN_TRIAL, UNDER_REVIEW, DISMISSED, CLOSED);
            case IN_TRIAL -> EnumSet.of(AWAITING_TRIAL, AWAITING_VERDICT, GUILTY, NOT_GUILTY, DISMISSED, UNDER_REVIEW);
            case AWAITING_VERDICT -> EnumSet.of(AWAITING_TRIAL, IN_TRIAL, GUILTY, NOT_GUILTY, DISMISSED, UNDER_REVIEW);
            case GUILTY -> EnumSet.of(AWAITING_SENTENCING, SENTENCED, UNDER_REVIEW);
            case AWAITING_SENTENCING -> EnumSet.of(SENTENCED, UNDER_REVIEW);
            case SENTENCED -> EnumSet.of(APPEALED, UNDER_REVIEW, NOT_GUILTY, CLOSED);
            case DIRECT_PUNISHMENT_ISSUED -> EnumSet.of(APPEALED, UNDER_REVIEW, NOT_GUILTY, CLOSED);
            case NOT_GUILTY -> EnumSet.of(UNDER_REVIEW, CLOSED);
            case DISMISSED -> EnumSet.of(UNDER_REVIEW, CLOSED);
            case UNDER_REVIEW -> EnumSet.of(OPEN, UNDER_INVESTIGATION, AWAITING_TRIAL, SENTENCED, NOT_GUILTY,
                    DISMISSED, DIRECT_PUNISHMENT_ISSUED, CLOSED);
            case APPEALED -> EnumSet.of(UNDER_REVIEW, SENTENCED, DIRECT_PUNISHMENT_ISSUED, NOT_GUILTY, DISMISSED, CLOSED);
            case CLOSED -> EnumSet.of(UNDER_REVIEW);
        };
    }

    public boolean canMoveTo(CaseStatus target) {
        return next().contains(target);
    }

    /** Statuses in which pretrial custody may still be initiated or kept running. */
    public boolean custodyAllowed() {
        return switch (this) {
            case OPEN, UNDER_INVESTIGATION, AWAITING_TRIAL, IN_TRIAL, AWAITING_VERDICT, UNDER_REVIEW,
                 GUILTY, AWAITING_SENTENCING -> true;
            default -> false;
        };
    }

    /** True while the case is still being worked (no final disposition yet). */
    public boolean isOpenForWork() {
        return switch (this) {
            case OPEN, UNDER_INVESTIGATION, AWAITING_TRIAL, IN_TRIAL, AWAITING_VERDICT, GUILTY,
                 AWAITING_SENTENCING, UNDER_REVIEW, APPEALED -> true;
            default -> false;
        };
    }

    /** Statuses in which evidence and notes may still be added. */
    public boolean acceptsEvidence() {
        return this != CLOSED;
    }

    public static CaseStatus parse(String s) {
        return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
