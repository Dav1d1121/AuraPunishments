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
 *   <li>{@link State#CASEWORK}: judicial system off, {@code casework.enabled: true} (1.3.1). Staff still open cases (offense, severity,
 *       description, evidence, notes) and punish from them, but there is no court: no pretrial detention, hearing, verdict, sentencing or
 *       direct-punishment bypass. Punishments are final and immediate. Leftover judicial work from before is reported, never resolved for you.</li>
 *   <li>{@link State#DISABLED}: switched off and nothing judicial is open. Punishments are final and immediately
 *       enforceable; no judicial option is offered anywhere. Historical records stay viewable.</li>
 *   <li>{@link State#WIND_DOWN}: switched off but cases are still open or a player is still held in pretrial
 *       detention. <b>Nothing is resolved, released or deleted automatically.</b> New judicial work is refused, but
 *       the existing items can still be finished with the normal judicial tools, and administrators are warned
 *       (console, on join, in {@code /ap status}) until the last one is resolved.</li>
 * </ul>
 */
public final class JudicialMode {
    public enum State { ENABLED, CASEWORK, WIND_DOWN, DISABLED }

    /** Open judicial work. {@code known} is false until the first successful count (treated as "maybe open"). */
    public record Report(boolean known, int openCases, int awaitingVerdict, int awaitingSentencing, int pretrialDetainees,
                         int caseAppeals, int openEmergencyCases, long judicialPunishmentsInForce) {
        public static final Report UNKNOWN = new Report(false, 0, 0, 0, 0, 0, 0, 0);

        /** Items that block a clean switch-off (a detainee or an unresolved case/appeal). */
        public int blocking() {
            return openCases + pretrialDetainees + caseAppeals;
        }

        /** Court work left over from when the judicial system was on (not ordinary open cases): the case workflow never starts any, never resolves any for you. */
        public int courtLeftover() {
            return pretrialDetainees + awaitingVerdict + awaitingSentencing + caseAppeals;
        }

        public List<String> courtLines() {
            List<String> l = new ArrayList<>();
            if (pretrialDetainees > 0) l.add(pretrialDetainees + " player(s) still held in pretrial detention - release or sentence them (/ap custody)");
            if (awaitingVerdict > 0 || awaitingSentencing > 0) l.add((awaitingVerdict + awaitingSentencing) + " case(s) still awaiting a verdict or sentencing from the judicial system - "
                    + "finish them with /ap judge, or move them to Under Review and close them");
            if (caseAppeals > 0) l.add(caseAppeals + " case appeal(s) not yet decided (/ap cases appeals)");
            return l;
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

    /** The case workflow without the court is on (judicial system off, {@code casework.enabled}). */
    public boolean casework() {
        return !enabled() && core.settings.casework.enabled();
    }

    /** New cases may be opened (full judicial system, or the case workflow). */
    public boolean casesAvailable() {
        return enabled() || casework();
    }

    /** Case screens, case lists and case history are shown (everything except plain DISABLED). */
    public boolean casesVisible() {
        return state() != State.DISABLED;
    }

    public State state() {
        if (enabled()) return State.ENABLED;
        if (casework()) return State.CASEWORK;
        Report r = report;
        return !r.known() || r.blocking() > 0 ? State.WIND_DOWN : State.DISABLED;
    }

    /** True while the COURT (judge dashboard, trials, custody sweeps, verdicts) should be offered: switch on, or open work still to finish. */
    public boolean judicialVisible() {
        return switch (state()) {
            case ENABLED, WIND_DOWN -> true;
            case CASEWORK -> !report.known() || report.courtLeftover() > 0; // only to finish what the court left behind
            case DISABLED -> false;
        };
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

    /** Guard for opening a case: allowed with the full judicial system and with the case workflow. {@code null} = go ahead. */
    public <T> Result<T> refuseNewCase() {
        if (casesAvailable()) return null;
        return Result.fail(Code.UNAVAILABLE, "The judicial system is switched off on this server and the case workflow (casework.enabled) is off too, "
                + "so opening a case is not available. Punishments are issued and final immediately (judicial.enabled / casework.enabled in config.yml).");
    }

    /** Guard for the COURT (hearings, trials, verdicts, sentencing, case appeals): refused in the case workflow. {@code null} = go ahead. */
    public <T> Result<T> refuseCourt(String what) {
        if (!casework()) return null;
        return Result.fail(Code.UNAVAILABLE, what + " is not part of the case workflow: cases are opened, investigated, punished from and closed - there is no court. "
                + "Close the case, or punish from it.");
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
