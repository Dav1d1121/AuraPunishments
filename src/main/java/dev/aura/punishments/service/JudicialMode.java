package dev.aura.punishments.service;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.Records.Custody;
import dev.aura.punishments.model.Simple.CustodyStatus;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Whether the judicial system (cases, pretrial detention, hearings, verdicts, sentencing) is on, and - when it has
 * been switched off while judicial work is still open - what is left to resolve.
 *
 * <p>States:
 * <ul>
 *   <li>{@link State#ENABLED}: {@code judicial.enabled: true}. Everything behaves exactly as in 1.2.x.</li>
 *   <li>{@link State#DISABLED}: switched off and nothing judicial is open. Punishments are final and immediately
 *       enforceable; no judicial option is offered anywhere. Historical records stay viewable.</li>
 *   <li>{@link State#WIND_DOWN}: switched off but cases are still open or a player is still held in pretrial
 *       detention. <b>Nothing is resolved, released or deleted automatically.</b> New judicial work is refused, but
 *       the existing items can still be finished with the normal judicial tools, and administrators are warned
 *       (console, on join, in {@code /ap status}) until the last one is resolved.</li>
 * </ul>
 */
public final class JudicialMode {
    public enum State { ENABLED, WIND_DOWN, DISABLED }

    /** Open judicial work. {@code known} is false until the first successful count (treated as "maybe open"). */
    public record Report(boolean known, int openCases, int awaitingVerdict, int awaitingSentencing, int pretrialDetainees,
                         int caseAppeals, int openEmergencyCases, long judicialPunishmentsInForce) {
        public static final Report UNKNOWN = new Report(false, 0, 0, 0, 0, 0, 0, 0);

        /** Items that block a clean switch-off (a detainee or an unresolved case/appeal). */
        public int blocking() {
            return openCases + pretrialDetainees + caseAppeals;
        }

        public List<String> lines() {
            List<String> l = new ArrayList<>();
            if (pretrialDetainees > 0) l.add(pretrialDetainees + " player(s) still held in pretrial detention - release or sentence them (/ap custody)");
            if (openCases > 0) l.add(openCases + " open case(s) (" + awaitingVerdict + " awaiting a verdict, " + awaitingSentencing
                    + " awaiting sentencing, " + openEmergencyCases + " emergency) - close, dismiss or sentence them (/ap cases open)");
            if (caseAppeals > 0) l.add(caseAppeals + " case appeal(s) not yet decided (/ap cases appeals)");
            return l;
        }
    }

    private final Core core;
    private volatile Report report = Report.UNKNOWN;

    JudicialMode(Core core) {
        this.core = core;
    }

    /** The configured switch. */
    public boolean enabled() {
        return core.settings.judicial.enabled();
    }

    public State state() {
        if (enabled()) return State.ENABLED;
        Report r = report;
        return !r.known() || r.blocking() > 0 ? State.WIND_DOWN : State.DISABLED;
    }

    /** True while judicial screens, queues and notices should be offered (switch on, or open work still to finish). */
    public boolean judicialVisible() {
        return state() != State.DISABLED;
    }

    public Report report() {
        return report;
    }

    /** Recount open judicial work. Safe to call from any thread; failures keep the previous report. */
    public CompletableFuture<Report> refresh() {
        return core.db.tx(c -> {
            List<CaseStatus> open = new ArrayList<>();
            for (CaseStatus s : CaseStatus.values()) if (s.isOpenForWork()) open.add(s);
            long cases = Repo.Cases.countByStatuses(c, open);
            long verdict = Repo.Cases.countByStatuses(c, List.of(CaseStatus.AWAITING_VERDICT, CaseStatus.IN_TRIAL));
            long sentencing = Repo.Cases.countByStatuses(c, List.of(CaseStatus.GUILTY, CaseStatus.AWAITING_SENTENCING));
            long emergencyOpen = Repo.Cases.countOpenEmergency(c);
            int detained = 0;
            for (Custody cu : Repo.Custodies.allLive(c)) if (cu.status() != CustodyStatus.SERVING) detained++;
            return new Report(true, (int) cases, (int) verdict, (int) sentencing, detained, (int) Repo.Appeals.countPendingForCases(c),
                    (int) emergencyOpen, Repo.Punishments.countJudicialInForce(c));
        }).handle((r, ex) -> {
            if (ex == null) report = r;
            else core.log.warning("Could not count open judicial work: " + ex.getMessage());
            return report;
        });
    }

    /**
     * Guard for every entry point that would START judicial work (a new case, new pretrial detention, a direct
     * punishment that bypasses a case, a case reopened). Finishing existing work is never refused by this.
     *
     * @return {@code null} when the judicial system is on (go ahead), otherwise the failure to return to the caller
     */
    public <T> Result<T> refuseNew(String what) {
        if (enabled()) return null;
        return Result.fail(Code.UNAVAILABLE, "The judicial system is switched off on this server, so " + what
                + " is not available. Punishments are issued and final immediately (judicial.enabled in config.yml).");
    }
}
