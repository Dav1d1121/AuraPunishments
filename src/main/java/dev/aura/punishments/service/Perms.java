package dev.aura.punishments.service;

import dev.aura.punishments.model.PunishType;

/** Every permission node used by the plugin. Default for all of them is deny (see plugin.yml). */
public final class Perms {
    private Perms() {}

    public static final String ROOT = "aurapunishments.";

    // entry points
    public static final String COMMAND = ROOT + "command";
    public static final String PUNISH = ROOT + "punish";
    public static final String PUNISH_OTHERS = ROOT + "punish.others";
    public static final String DASHBOARD = ROOT + "dashboard";
    public static final String HISTORY_VIEW = ROOT + "history.view";

    // cases
    public static final String CASE_VIEW_OWN = ROOT + "case.view.own";
    public static final String CASE_VIEW_ALL = ROOT + "case.view.all";
    public static final String CASE_CREATE = ROOT + "case.create";
    public static final String REPORT = ROOT + "report";
    public static final String CASE_MANAGE = ROOT + "case.manage";
    public static final String CASE_CLOSE = ROOT + "case.close";
    public static final String EVIDENCE_ADD = ROOT + "case.evidence.add";
    public static final String EVIDENCE_VIEW = ROOT + "case.evidence.view";
    public static final String EVIDENCE_VOID = ROOT + "case.evidence.void";
    public static final String NOTE_ADD = ROOT + "case.note.add";
    public static final String NOTE_VIEW = ROOT + "case.note.view";
    public static final String NOTE_VOID = ROOT + "case.note.void";
    public static final String APPEAL_FILE = ROOT + "case.appeal.file";

    // custody
    public static final String CUSTODY_VIEW = ROOT + "custody.view";
    public static final String CUSTODY_INITIATE = ROOT + "custody.initiate";
    public static final String CUSTODY_RELEASE = ROOT + "custody.release";
    public static final String CUSTODY_OVERRIDE = ROOT + "custody.override";

    // quick punishments
    public static final String REVOKE = ROOT + "punish.revoke";
    public static final String REVOKE_ANY = ROOT + "punish.revoke.any";

    // direct / emergency
    public static final String DIRECT = ROOT + "direct";
    public static final String EMERGENCY_USE = ROOT + "emergency.use";
    public static final String EMERGENCY_REVIEW = ROOT + "emergency.review";

    // judicial
    public static final String JUDICIAL_VIEW = ROOT + "judicial.view";
    public static final String JUDICIAL_MANAGE = ROOT + "judicial.manage";
    public static final String JUDICIAL_VERDICT = ROOT + "judicial.verdict";
    public static final String JUDICIAL_VERDICT_ANY = ROOT + "judicial.verdict.any";
    public static final String JUDICIAL_SENTENCE = ROOT + "judicial.sentence";
    public static final String JUDICIAL_RELEASE = ROOT + "judicial.release";
    public static final String JUDICIAL_OVERTURN = ROOT + "judicial.overturn";

    // sensitive / admin
    public static final String SENSITIVE_IP = ROOT + "sensitive.ip";
    public static final String SENSITIVE_NOTES = ROOT + "sensitive.notes";
    public static final String AUDIT_VIEW = ROOT + "audit.view";
    public static final String ADMIN = ROOT + "admin";
    public static final String ADMIN_RELOAD = ROOT + "admin.reload";

    // protection
    public static final String LEVEL_PREFIX = ROOT + "level.";
    public static final String IMMUNITY_BYPASS = ROOT + "immunity.bypass";
    public static final String LEGACY_BYPASS = ROOT + "legacy.bypass";

    public static final String NOTIFY_PREFIX = ROOT + "notify.";
    public static final String NOTIFY_EMERGENCY = NOTIFY_PREFIX + "emergency";

    /** aurapunishments.punish.<type> (quick punishment of that type). */
    public static String punish(PunishType t) {
        return ROOT + "punish." + t.node();
    }

    /** aurapunishments.punish.<type>.permanent */
    public static String punishPermanent(PunishType t) {
        return punish(t) + ".permanent";
    }

    /** aurapunishments.direct.<type> */
    public static String direct(PunishType t) {
        return DIRECT + "." + t.node();
    }

    public static String directPermanent(PunishType t) {
        return direct(t) + ".permanent";
    }

    /** aurapunishments.limit.<type>.<tier> */
    public static String limit(PunishType t, String tier) {
        return ROOT + "limit." + t.node() + "." + tier;
    }

    public static String notify(String event) {
        return NOTIFY_PREFIX + event;
    }
}
