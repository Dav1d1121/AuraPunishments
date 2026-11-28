package dev.aura.punishments.config;

import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.util.Durations;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, validated snapshot of config.yml. A reload builds a new snapshot first; only if it
 * validates is it swapped in, so a typo can never leave the plugin half-configured.
 */
public final class Settings {

    public record Gui(boolean hideLocked, int clickCooldownMs, int promptTimeoutSeconds, String cancelWord,
                      String soundClick, String soundSuccess, String soundError) {}

    public record Cases(int minDescription, int maxDescription, int minReason, int maxReason, int maxNote,
                        boolean requireEvidenceOnCreate, long duplicateWindow, int maxEvidence,
                        int maxEvidenceRef, int minWrittenReport) {}

    public record Custody(boolean enabled, long reviewDeadline, List<Long> alertBefore, long overdueRepeat,
                          boolean releaseAfterGrace, long gracePeriod, int maxExtensions, long extensionLength,
                          String jailName, String cellName, boolean muteWhileHeld, int reconcileSeconds,
                          boolean guardJailCommands, Set<String> guardedJailCommands) {}

    public record TimeServed(boolean enabled, double rate, Set<PunishType> appliesTo, Set<PunishType> defaultCreditFor) {}

    /**
     * @param enabled the judicial system (cases, pretrial detention, hearings, verdicts, sentencing). A config file that
     *                does not contain {@code judicial.enabled} keeps the pre-1.3.0 behaviour (true); the file shipped to a
     *                fresh install sets it to false. The explicit value always wins.
     */
    public record Judicial(boolean enabled, boolean prosecutorMayJudge, boolean requireAssignedJudge, int minVerdictReason,
                           TimeServed timeServed) {}

    /**
     * The case workflow without the court (1.3.1). Only meaningful while {@code judicial.enabled} is false: staff still open a case (offense,
     * severity, description, evidence, notes) and punish from it, but there is no pretrial detention, hearing, verdict, sentencing or
     * time-served credit. A punishment issued from a case is final and immediate.
     *
     * @param requireCaseFor punishment types that may only be issued from a case (the quick buttons and commands refuse them)
     */
    public record Casework(boolean enabled, Set<PunishType> requireCaseFor) {}

    /** Which server this is; stamped on every record so a shared network database can tell them apart. */
    /**
     * @param serverName          this server's unique name in the network (required with a shared database)
     * @param syncIntervalMs      how often the change feed from the other servers is read
     * @param heartbeatSeconds    how often this server proves it is alive and refreshes where its players are
     * @param eventRetentionDays  change events older than this are deleted
     * @param maxClockSkewSeconds refuse to start when this server's clock and the database server's differ by more than this (0 = do not check)
     * @param allowDuplicateName  start even if another live server uses the same name (never for production)
     */
    public record Network(String serverName, int syncIntervalMs, int heartbeatSeconds, int eventRetentionDays, int maxClockSkewSeconds, boolean allowDuplicateName,
                          boolean denyAllDuringOutage, boolean startWithoutDatabase, int outageRetrySeconds, String proxyName, NetJail jail) {}

    /**
     * The network jail: ONE backend hosts JailReloaded and the jail; every other backend sends a jailed player there.
     *
     * @param backend             the {@code network.server-name} of the jail backend; blank = no network jail (each server with JailReloaded jails locally)
     * @param returnMode          where a released prisoner is sent: ORIGINAL (the backend they were on, lobby when that is not possible), LOBBY or FIXED
     * @param lobbyServer         the proxy name of the lobby
     * @param fixedServer         the proxy name used by FIXED
     * @param hold                restrict a jailed player who is not (yet) held by JailReloaded: no moving, building, chat or commands
     * @param holdCommands        commands a held player may still use (without the slash)
     * @param transferRetrySeconds how often the transfer is repeated while the player is still not where they belong
     */
    public record NetJail(String backend, String returnMode, String lobbyServer, String fixedServer, boolean hold, List<String> holdCommands, int transferRetrySeconds) {
        public boolean enabled() {
            return !backend.isBlank();
        }
    }

    /** Appeals filed against punishments (Discord Ticket Tool tickets are referenced, never created, by this plugin). */
    public record Appeals(boolean requireTicket, int minReason) {}

    /** Standalone jail punishment (JailReloaded does the imprisonment). Blank names fall back to custody.jail-name / cell-name. */
    public record JailCfg(String jailName, String cellName, boolean muteWhileJailed) {}

    /**
     * One Discord destination for punishment notifications. {@code webhooks} empty = use the "punishment" channel routing
     * (staff log) or no destination (public log, which then posts nothing).
     */
    public record PunishLog(boolean enabled, List<String> webhooks, Set<PunishType> types, Set<String> events, Set<String> fields,
                            boolean showReason) {}

    public record Escalation(boolean categoryScope, boolean countDirect) {}

    public record Direct(boolean enabled, Severity minSeverity, int minDescription, int minJustification,
                         int minEvidence, Set<PunishType> typeNameToConfirm) {}

    public record Emergency(boolean enabled, Set<PunishType> allowedTypes, long defaultDuration, long maxDuration,
                            long reviewDeadline, long overdueRepeat) {}

    public record Quick(boolean requireReason, Set<PunishType> requireEvidence, boolean typeNameToConfirm) {}

    /** Safeguards for IP bans; see config.yml punishments.ip-bans. */
    public record IpBans(boolean refusePrivate, Set<String> exclude, int maxAccounts, long windowMs) {
        public static final IpBans OFF = new IpBans(false, Set.of(), 0, 0);

        /** True when this address must never carry an IP ban (checked when issuing and again when enforcing). */
        public boolean protectedAddress(String ip) {
            if (ip == null || ip.isBlank()) return false;
            String a = ip.trim();
            if (exclude.contains(a)) return true;
            if (!refusePrivate) return false;
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(a.matches("[0-9a-fA-F:.]+") ? a : "invalid.invalid");
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()) return true;
                byte[] b = addr.getAddress();
                if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return true;                // 100.64.0.0/10 carrier-grade NAT
                return b.length == 16 && (b[0] & 0xFE) == 0xFC;                                               // fc00::/7 unique local
            } catch (java.net.UnknownHostException e) {
                return false;
            }
        }
    }

    public record Protections(boolean enforceHierarchy, boolean blockSelf, String immunityNode) {}

    public record Mute(Set<String> blockedCommands, Set<String> blockedSubcommands, Set<String> allowedCommands) {}

    public record Legacy(boolean block, Set<String> blocked) {}

    /** Per-event override: may switch the event off, move it to another category and/or give it its own webhooks. */
    public record DiscordEvent(boolean enabled, String category, List<String> webhooks) {}

    /** One Discord log destination (category). {@code fallback} names the category (or "default") used when no webhook is set. */
    public record DiscordChannel(boolean enabled, List<String> webhooks, String fallback, List<String> mentionRoles, Set<String> mentionEvents) {}

    public record Discord(boolean enabled, String webhook, String username, String avatar,
                          Map<String, DiscordChannel> channels, Map<String, DiscordEvent> events, int timeoutSeconds, int maxRetries,
                          int retentionDays, long backoffBaseMs, boolean legacyLayout, PunishLog staffLog, PunishLog publicLog) {}

    public record WarnTitle(boolean enabled, String title, String subtitle, boolean showReason, int reasonMaxChars,
                            String subtitleWithReason, int fadeIn, int stay, int fadeOut) {}

    public record WarnSound(boolean enabled, String sound, float volume, float pitch, String source) {}

    public record WarnRepeat(boolean enabled, long window, int threshold, boolean sameCategoryOnly, boolean recommendCase) {}

    public record Warnings(boolean chat, WarnTitle title, WarnSound sound, boolean actionBar, String actionBarText, int spacingTicks,
                           boolean deferTitleWhilePrompting, WarnRepeat repeat) {}

    public record Review(boolean enabled, Set<PunishType> requiredTypes, long minDuration, int exemptLevel, long evidenceDeadline,
                         long reviewDeadline, long reminderBefore, long reminderRepeat, int maxEvidenceReminders, boolean notifyInGame,
                         Set<PunishType> permanentOnlyTypes, boolean includeEmergency) {}

    public record Dashboard(long approachingCustody, Map<String, Integer> priority) {}

    public record Defendant(boolean enabled, String hearingInstructions, boolean includeReasoning, boolean sound, Set<String> statuses) {}

    public record Exports(boolean enabled, String folder, boolean includeTimeline) {}

    public record CoreProtectCfg(boolean enabled, int maxRadius, long maxTime, int sampleRows) {}

    public final boolean debug;
    public final String dbFile;
    /** Where the data lives (1.4.0): the standalone SQLite file, or a shared MariaDB/MySQL database. */
    public final dev.aura.punishments.db.StorageConfig storage;
    public final int dbTimeoutSeconds;
    public final Gui gui;
    public final Cases cases;
    public final Custody custody;
    public final Judicial judicial;
    public final Network network;
    public final Casework casework;
    public final Appeals appeals;
    public final JailCfg jail;
    public final Escalation escalation;
    public final Warnings warnings;
    public final Review review;
    public final Dashboard dashboard;
    public final Defendant defendant;
    public final Exports exports;
    public final CoreProtectCfg coreProtect;
    public final Direct direct;
    public final Emergency emergency;
    public final Quick quick;
    public final IpBans ipBans;
    public final Map<PunishType, LinkedHashMap<String, Long>> limits;
    public final boolean warningDelivery;
    public final int kickDelayTicks;
    public final Protections protections;
    public final Mute mute;
    public final Legacy legacy;
    public final Map<String, Boolean> notifications;
    public final Discord discord;
    public final int auditRetentionDays;
    public final boolean jailEnabled;
    public final boolean closeGuisOnPermissionChange;

    private Settings(YamlConfiguration y, YamlConfiguration secrets, java.util.function.UnaryOperator<String> env, List<String> p) {
        this.debug = y.getBoolean("general.debug", false);
        String dbType = y.getString("database.type", "sqlite").trim().toLowerCase(java.util.Locale.ROOT);
        if (!dbType.equals("sqlite") && !dbType.equals("mariadb") && !dbType.equals("mysql")) {
            p.add("database.type: must be sqlite, mariadb or mysql (got '" + dbType + "')");
            dbType = "sqlite";
        }
        if (dbType.equals("sqlite")) {
            this.storage = dev.aura.punishments.db.StorageConfig.sqlite();
        } else {
            String host = y.getString("database.host", "127.0.0.1").trim();
            String name = y.getString("database.name", "aurapunishments").trim();
            String user = y.getString("database.username", "").trim();
            if (host.isEmpty()) p.add("database.host: required when database.type is " + dbType);
            if (!name.matches("[A-Za-z0-9_$-]{1,64}")) p.add("database.name: 1-64 letters, digits, _ $ - (got '" + name + "')");
            if (user.isEmpty()) p.add("database.username: required when database.type is " + dbType);
            int max = range(y, "database.pool.maximum-size", 4, 1, 32, p);
            int minIdle = Math.min(max, range(y, "database.pool.minimum-idle", 1, 0, 32, p));
            this.storage = new dev.aura.punishments.db.StorageConfig(dbType, host, range(y, "database.port", 3306, 1, 65535, p), name, user,
                    resolveSecret(y.getString("database.password", ""), env), y.getBoolean("database.ssl", false), y.getBoolean("database.create-database", false), y.getBoolean("database.allow-migration", false), max, minIdle,
                    1000L * range(y, "database.pool.connection-timeout-seconds", 5, 1, 120, p), 1000L * range(y, "database.pool.idle-timeout-seconds", 300, 10, 3600, p),
                    1000L * range(y, "database.pool.max-lifetime-seconds", 1500, 30, 86_400, p), 1000L * range(y, "database.pool.keepalive-seconds", 60, 0, 3600, p));
            if (this.storage.shared() && serverNameOf(y).isBlank()) {
                p.add("network.server-name: required when database.type is " + dbType + " - every server of the network needs its own short name (lobby, survival, ...)");
            }
        }
        this.dbFile = safeFile(y.getString("database.file", "punishments.db"), p);
        this.dbTimeoutSeconds = range(y, "database.timeout-seconds", 15, 1, 120, p);

        this.gui = new Gui(y.getBoolean("general.hide-locked-actions", false),
                range(y, "gui.click-cooldown-ms", 250, 0, 5000, p),
                range(y, "gui.prompt-timeout-seconds", 90, 10, 600, p),
                y.getString("gui.prompt-cancel-word", "cancel").toLowerCase(Locale.ROOT),
                y.getString("gui.sounds.click", "ui.button.click"),
                y.getString("gui.sounds.success", "entity.experience_orb.pickup"),
                y.getString("gui.sounds.error", "entity.villager.no"));
        if (gui.cancelWord().isBlank()) p.add("gui.prompt-cancel-word: cannot be empty");

        int minD = range(y, "cases.min-description-length", 10, 1, 500, p);
        int maxD = range(y, "cases.max-description-length", 400, 20, 2000, p);
        if (minD > maxD) p.add("cases: min-description-length cannot exceed max-description-length");
        int minR = range(y, "cases.min-reason-length", 5, 1, 200, p);
        int maxR = range(y, "cases.max-reason-length", 250, 10, 1000, p);
        if (minR > maxR) p.add("cases: min-reason-length cannot exceed max-reason-length");
        this.cases = new Cases(minD, maxD, minR, maxR, range(y, "cases.max-note-length", 400, 20, 2000, p),
                y.getBoolean("cases.require-evidence-on-create", false),
                dur(y, "cases.duplicate-window", "10m", false, p),
                range(y, "cases.max-evidence-per-case", 40, 1, 500, p),
                range(y, "cases.max-evidence-reference-length", 512, 32, 2000, p),
                range(y, "cases.min-written-report-length", 30, 1, 500, p));

        List<Long> alerts = new ArrayList<>();
        for (String a : y.getStringList("custody.alert-before")) {
            long v = Durations.parse(a);
            if (v < 0 || v == Durations.PERMANENT) p.add("custody.alert-before: invalid duration '" + a + "'");
            else alerts.add(v);
        }
        alerts.sort(java.util.Comparator.reverseOrder());
        String onDeadline = y.getString("custody.on-deadline", "ALERT_THEN_RELEASE").toUpperCase(Locale.ROOT);
        if (!onDeadline.equals("ALERT_THEN_RELEASE") && !onDeadline.equals("ALERT_ONLY")) {
            p.add("custody.on-deadline: must be ALERT_THEN_RELEASE or ALERT_ONLY");
        }
        long reviewDeadline = dur(y, "custody.review-deadline", "48h", false, p);
        for (long a : alerts) if (a >= reviewDeadline) p.add("custody.alert-before: every alert must be shorter than review-deadline");
        this.custody = new Custody(y.getBoolean("custody.enabled", true), reviewDeadline, List.copyOf(alerts),
                dur(y, "custody.overdue-repeat", "1h", false, p), onDeadline.equals("ALERT_THEN_RELEASE"),
                dur(y, "custody.grace-period", "12h", false, p), range(y, "custody.max-extensions", 2, 0, 20, p),
                dur(y, "custody.extension-length", "24h", false, p), y.getString("custody.jail-name", ""),
                y.getString("custody.cell-name", ""), y.getBoolean("custody.mute-while-held", false),
                range(y, "custody.reconcile-interval-seconds", 60, 5, 3600, p),
                y.getBoolean("custody.guard-jail-commands", true), lowerSet(y.getStringList("custody.guarded-jail-commands")));

        EnumSet<PunishType> credit = types(y.getStringList("judicial.time-served.applies-to"), "judicial.time-served.applies-to", p);
        // a missing key keeps 1.0.0 behaviour (credit offered on every applicable type); the shipped 1.1.0 file narrows it to JAIL
        double rate = y.getDouble("judicial.time-served.rate", 1.0);
        if (rate < 0 || rate > 1) p.add("judicial.time-served.rate: must be between 0.0 and 1.0");
        this.judicial = new Judicial(y.getBoolean("judicial.enabled", true), y.getBoolean("judicial.prosecutor-may-judge", true),
                y.getBoolean("judicial.require-assigned-judge-for-verdict", true),
                range(y, "judicial.min-verdict-reason-length", 10, 0, 200, p),
                new TimeServed(y.getBoolean("judicial.time-served.enabled", true), rate, credit,
                        y.isList("judicial.time-served.default-credit-for")
                                ? types(y.getStringList("judicial.time-served.default-credit-for"), "judicial.time-served.default-credit-for", p)
                                : EnumSet.copyOf(credit.isEmpty() ? EnumSet.noneOf(PunishType.class) : credit)));

        String srv = y.getString("network.server-name", "").trim();
        if (!srv.isEmpty() && !srv.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) {
            p.add("network.server-name: use 1-40 letters, digits, '.', '_' or '-' (for example smp, towny, skyblock, lobby)");
            srv = "";
        }
        this.network = new Network(srv, range(y, "network.sync-interval-ms", 1000, 250, 30_000, p), range(y, "network.heartbeat-seconds", 15, 5, 120, p),
                range(y, "network.event-retention-days", 7, 1, 365, p), range(y, "network.max-clock-skew-seconds", 10, 0, 3600, p),
                y.getBoolean("network.allow-duplicate-name", false), "DENY_ALL".equalsIgnoreCase(y.getString("network.outage.policy", "ENFORCE_KNOWN")),
                y.getBoolean("network.outage.start-without-database", true), range(y, "network.outage.retry-seconds", 15, 5, 600, p),
                y.getString("network.proxy-name", "").trim(),
                new NetJail(y.getString("network.jail.backend", "").trim(), y.getString("network.jail.return.mode", "ORIGINAL").trim().toUpperCase(java.util.Locale.ROOT),
                        y.getString("network.jail.return.lobby-server", "lobby").trim(), y.getString("network.jail.return.fixed-server", "").trim(),
                        y.getBoolean("network.jail.hold.enabled", true), y.getStringList("network.jail.hold.allowed-commands"),
                        range(y, "network.jail.transfer-retry-seconds", 5, 2, 120, p)));
        for (String key : new String[]{"network.proxy-name", "network.jail.backend", "network.jail.return.lobby-server", "network.jail.return.fixed-server"}) {
            String v = y.getString(key, "").trim();
            if (!v.isEmpty() && !v.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) p.add(key + ": use 1-40 letters, digits, '.', '_' or '-' (the name the proxy uses for that server)");
        }
        if (!network.jail.returnMode().matches("ORIGINAL|LOBBY|FIXED")) p.add("network.jail.return.mode: must be ORIGINAL, LOBBY or FIXED (got '" + network.jail.returnMode() + "')");
        if (network.jail.returnMode().equals("FIXED") && network.jail.fixedServer().isBlank()) p.add("network.jail.return.fixed-server: required when network.jail.return.mode is FIXED");
        if (network.jail.enabled() && !storage.shared()) p.add("network.jail.backend: only used with a shared database (database.type: mariadb | mysql); remove it on a standalone server");
        String policy = y.getString("network.outage.policy", "ENFORCE_KNOWN");
        if (!policy.equalsIgnoreCase("ENFORCE_KNOWN") && !policy.equalsIgnoreCase("DENY_ALL")) p.add("network.outage.policy: must be ENFORCE_KNOWN or DENY_ALL (got '" + policy + "')");
        this.casework = new Casework(y.getBoolean("casework.enabled", false), types(y.getStringList("casework.require-case-for"), "casework.require-case-for", p));
        this.appeals = new Appeals(y.getBoolean("appeals.require-ticket", false), range(y, "appeals.min-reason-length", 10, 1, 500, p));
        this.jail = new JailCfg(y.getString("punishments.jail.jail-name", "").trim(), y.getString("punishments.jail.cell-name", "").trim(),
                y.getBoolean("punishments.jail.mute-while-jailed", false));

        this.escalation = new Escalation(!"all".equalsIgnoreCase(y.getString("escalation.scope", "category")),
                y.getBoolean("escalation.count-direct-punishments", true));

        Severity minSev = Severity.MAJOR;
        try {
            minSev = Severity.parse(y.getString("direct.minimum-severity", "MAJOR"));
        } catch (IllegalArgumentException e) {
            p.add("direct.minimum-severity: must be MINOR, MODERATE, MAJOR or SEVERE");
        }
        this.direct = new Direct(y.getBoolean("direct.enabled", true), minSev,
                range(y, "direct.min-description-length", 30, 1, 500, p),
                range(y, "direct.min-justification-length", 30, 1, 500, p),
                range(y, "direct.min-evidence", 1, 1, 20, p),
                types(y.getStringList("direct.type-name-to-confirm"), "direct.type-name-to-confirm", p));

        EnumSet<PunishType> emTypes = types(y.getStringList("emergency.allowed-types"), "emergency.allowed-types", p);
        if (emTypes.contains(PunishType.JAIL) || emTypes.contains(PunishType.WARN) || emTypes.contains(PunishType.KICK)
                || emTypes.contains(PunishType.IPBAN)) {
            p.add("emergency.allowed-types: only MUTE and BAN are allowed (use custody through a case for jail)");
            emTypes.removeAll(EnumSet.of(PunishType.JAIL, PunishType.WARN, PunishType.KICK, PunishType.IPBAN));
        }
        long emMax = dur(y, "emergency.max-duration", "24h", false, p);
        long emDef = dur(y, "emergency.default-duration", "6h", false, p);
        if (emDef > emMax) p.add("emergency.default-duration cannot exceed max-duration");
        long emReview = dur(y, "emergency.review-deadline", "12h", false, p);
        if (emReview > emMax) p.add("emergency.review-deadline should not exceed max-duration (the action would expire before review)");
        this.emergency = new Emergency(y.getBoolean("emergency.enabled", true), emTypes, emDef, emMax, emReview,
                dur(y, "emergency.overdue-repeat", "1h", false, p));

        if (y.isConfigurationSection("punishments.ip-bans")) {
            java.util.Set<String> ex = new java.util.HashSet<>();
            for (String a : y.getStringList("punishments.ip-bans.exclude-addresses")) if (!a.isBlank()) ex.add(a.trim());
            this.ipBans = new IpBans(y.getBoolean("punishments.ip-bans.refuse-private-addresses", true), java.util.Set.copyOf(ex),
                    range(y, "punishments.ip-bans.max-accounts-per-address", 15, 0, 100_000, p), range(y, "punishments.ip-bans.window-days", 30, 1, 3650, p) * 86_400_000L);
        } else {
            this.ipBans = IpBans.OFF;
        }
        this.quick = new Quick(y.getBoolean("punishments.quick.require-reason", true),
                types(y.getStringList("punishments.quick.require-evidence"), "punishments.quick.require-evidence", p),
                y.getBoolean("punishments.quick.type-name-to-confirm", false));

        Map<PunishType, LinkedHashMap<String, Long>> lim = new EnumMap<>(PunishType.class);
        ConfigurationSection ls = y.getConfigurationSection("punishments.limits");
        if (ls != null) {
            for (String tk : ls.getKeys(false)) {
                PunishType t;
                try {
                    t = PunishType.parse(tk);
                } catch (IllegalArgumentException e) {
                    p.add("punishments.limits: unknown type '" + tk + "'");
                    continue;
                }
                if (!t.canBeTimed()) {
                    p.add("punishments.limits." + tk + ": this type has no duration");
                    continue;
                }
                ConfigurationSection tiers = ls.getConfigurationSection(tk);
                LinkedHashMap<String, Long> m = new LinkedHashMap<>();
                if (tiers != null) {
                    for (String tier : tiers.getKeys(false)) {
                        if (!tier.matches("[a-z0-9_]{1,20}")) {
                            p.add("punishments.limits." + tk + ": tier name '" + tier + "' must be lower-case letters/digits/underscore");
                            continue;
                        }
                        m.put(tier, dur(y, "punishments.limits." + tk + "." + tier, "1h", false, p));
                    }
                }
                lim.put(t, m);
            }
        }
        this.limits = lim;
        this.warningDelivery = y.getBoolean("punishments.warning-delivery", true);
        this.kickDelayTicks = range(y, "punishments.kick-delay-ticks", 0, 0, 200, p);

        this.protections = new Protections(y.getBoolean("protections.enforce-hierarchy", true),
                y.getBoolean("protections.block-self-targeting", true),
                y.getString("protections.immunity-node", "aurapunishments.immune"));

        this.mute = new Mute(lowerSet(y.getStringList("mute.blocked-commands")), lowerSet(y.getStringList("mute.blocked-subcommands")),
                lowerSet(y.getStringList("mute.allowed-commands")));
        this.legacy = new Legacy(y.getBoolean("legacy-commands.block", true), lowerSet(y.getStringList("legacy-commands.blocked")));

        Map<String, Boolean> notif = new LinkedHashMap<>();
        ConfigurationSection ns = y.getConfigurationSection("notifications");
        if (ns != null) for (String k : ns.getKeys(false)) notif.put(k, ns.getBoolean(k, true));
        this.notifications = Map.copyOf(notif);

        // ---------------- warnings
        WarnTitle wt = new WarnTitle(y.getBoolean("warnings.notifications.title.enabled", true),
                y.getString("warnings.notifications.title.title", "<red><bold>\u26a0 OFFICIAL WARNING \u26a0"),
                y.getString("warnings.notifications.title.subtitle", "<yellow>Please correct your behavior."),
                y.getBoolean("warnings.notifications.title.show-reason", false),
                range(y, "warnings.notifications.title.reason-max-chars", 40, 10, 100, p),
                y.getString("warnings.notifications.title.subtitle-with-reason", "<yellow><reason>"),
                range(y, "warnings.notifications.title.fade-in-ticks", 10, 0, 200, p),
                range(y, "warnings.notifications.title.stay-ticks", 100, 10, 600, p),
                range(y, "warnings.notifications.title.fade-out-ticks", 20, 0, 200, p));
        String soundKey = y.getString("warnings.notifications.sound.sound", "block.note_block.pling");
        double vol = y.getDouble("warnings.notifications.sound.volume", 0.8);
        double pitch = y.getDouble("warnings.notifications.sound.pitch", 1.0);
        if (vol < 0 || vol > 2) p.add("warnings.notifications.sound.volume: must be between 0.0 and 2.0");
        if (pitch < 0.5 || pitch > 2) p.add("warnings.notifications.sound.pitch: must be between 0.5 and 2.0");
        if (!soundKey.matches("[a-z0-9_.\\-/:]{1,100}")) p.add("warnings.notifications.sound.sound: '" + soundKey + "' is not a valid sound key");
        String soundSource = y.getString("warnings.notifications.sound.source", "master").toLowerCase(Locale.ROOT);
        if (!Set.of("master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice", "ui").contains(soundSource)) {
            p.add("warnings.notifications.sound.source: unknown sound source '" + soundSource + "'");
        }
        this.warnings = new Warnings(y.getBoolean("warnings.notifications.chat.enabled", true), wt,
                new WarnSound(y.getBoolean("warnings.notifications.sound.enabled", true), soundKey, (float) vol, (float) pitch, soundSource),
                y.getBoolean("warnings.notifications.actionbar.enabled", false),
                y.getString("warnings.notifications.actionbar.text", "<red>\u26a0 You received an official warning - see chat"),
                range(y, "warnings.notifications.spacing-ticks", 120, 20, 1200, p),
                y.getBoolean("warnings.notifications.defer-title-while-prompting", true),
                new WarnRepeat(y.getBoolean("warnings.repeat.enabled", true), dur(y, "warnings.repeat.window", "30d", false, p),
                        range(y, "warnings.repeat.threshold", 3, 2, 50, p), y.getBoolean("warnings.repeat.same-category-only", false),
                        y.getBoolean("warnings.repeat.recommend-case", true)));
        if (wt.enabled() && wt.title().isBlank()) p.add("warnings.notifications.title.title: cannot be empty while the title is enabled");

        // ---------------- owner review of quick punishments
        EnumSet<PunishType> reviewTypes = y.isList("review.required-types") ? types(y.getStringList("review.required-types"), "review.required-types", p)
                : EnumSet.of(PunishType.MUTE, PunishType.BAN, PunishType.IPBAN);
        if (reviewTypes.contains(PunishType.WARN) || reviewTypes.contains(PunishType.KICK)) {
            p.add("review.required-types: warnings and kicks never enter owner review");
            reviewTypes.remove(PunishType.WARN);
            reviewTypes.remove(PunishType.KICK);
        }
        EnumSet<PunishType> permOnly = types(y.getStringList("review.permanent-only-types"), "review.permanent-only-types", p);
        for (PunishType t : permOnly) if (!reviewTypes.contains(t)) p.add("review.permanent-only-types: " + t + " is not listed in review.required-types");
        permOnly.retainAll(reviewTypes);
        long evDeadline = dur(y, "review.evidence-deadline", "24h", false, p);
        long rvDeadline = dur(y, "review.review-deadline", "72h", false, p);
        long remBefore = dur(y, "review.reminder-before", "12h", false, p);
        if (remBefore >= rvDeadline) p.add("review.reminder-before: must be shorter than review-deadline");
        this.review = new Review(y.getBoolean("review.enabled", true), reviewTypes, dur(y, "review.min-duration", "0s", false, p),
                range(y, "review.exempt-level", 0, 0, 100000, p), evDeadline, rvDeadline, remBefore,
                dur(y, "review.reminder-repeat", "12h", false, p), range(y, "review.max-evidence-reminders", 6, 1, 50, p),
                y.getBoolean("review.notify-in-game", true), permOnly, y.getBoolean("review.include-emergency", true));

        // ---------------- dashboard
        Map<String, Integer> prio = new LinkedHashMap<>();
        for (String k : List.of("custody-overdue", "custody-imminent", "emergency-overdue", "emergency-pending", "quick-review-overdue",
                "quick-review-pending", "appeal", "awaiting-verdict", "awaiting-sentencing", "further-investigation", "awaiting-trial")) {
            prio.put(k, range(y, "dashboard.priority." + k, defaultPriority(k), 0, 1000, p));
        }
        this.dashboard = new Dashboard(dur(y, "dashboard.approaching-custody", "12h", false, p), Map.copyOf(prio));

        // ---------------- defendant communication
        java.util.LinkedHashSet<String> dst = new java.util.LinkedHashSet<>();
        for (String st : y.isList("defendant.notify-statuses") ? y.getStringList("defendant.notify-statuses") : List.of("IN_TRIAL", "DISMISSED", "CLOSED", "UNDER_INVESTIGATION")) {
            try {
                dst.add(dev.aura.punishments.model.CaseStatus.parse(st).name());
            } catch (IllegalArgumentException e) {
                p.add("defendant.notify-statuses: unknown case status '" + st + "'");
            }
        }
        this.defendant = new Defendant(y.getBoolean("defendant.enabled", true),
                y.getString("defendant.hearing-instructions", "Staff will contact you with the Discord hearing details. Please make sure you can be reached on Discord."),
                y.getBoolean("defendant.include-reasoning", true), y.getBoolean("defendant.sound", false), Set.copyOf(dst));

        String exportFolder = y.getString("exports.folder", "exports");
        if (!exportFolder.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) {
            p.add("exports.folder: must be a plain folder name (letters, digits, . _ -; not starting with a dot) inside the plugin folder");
            exportFolder = "exports";
        }
        this.exports = new Exports(y.getBoolean("exports.enabled", true), exportFolder, y.getBoolean("exports.include-timeline", true));
        this.coreProtect = new CoreProtectCfg(y.getBoolean("integrations.coreprotect.enabled", true),
                range(y, "integrations.coreprotect.max-radius", 50, 1, 500, p),
                dur(y, "integrations.coreprotect.max-time", "7d", false, p), range(y, "integrations.coreprotect.sample-rows", 8, 0, 30, p));

        // ---------------- discord (1.0.0 layout still works: webhook-url acts as the default for every category)
        boolean dEnabled = y.getBoolean("discord.enabled", false);
        String defaultUrl = resolveSecret(y.getString("discord.webhook-url", ""), env);
        if (secrets != null && secrets.isString("default") && !secrets.getString("default","").isBlank()) defaultUrl = resolveSecret(secrets.getString("default", ""), env);
        if (!defaultUrl.isBlank() && !validWebhook(defaultUrl)) p.add("discord.webhook-url: must be an https://discord.com/api/webhooks/... URL (use ${env:NAME} to keep it out of the file)");
        Map<String, DiscordChannel> channels = new LinkedHashMap<>();
        ConfigurationSection cs = y.getConfigurationSection("discord.channels");
        boolean legacy = cs == null;
        for (String cat : DISCORD_CATEGORIES) {
            ConfigurationSection c = cs == null ? null : cs.getConfigurationSection(cat);
            List<String> urls = new ArrayList<>(urlList(c == null ? null : c, "webhooks", env, "discord.channels." + cat + ".webhooks", p));
            if (secrets != null) urls.addAll(secretUrls(secrets, cat, env, "webhooks.yml " + cat, p));
            String fb = c == null ? "default" : c.getString("fallback", "default").toLowerCase(Locale.ROOT);
            if (!fb.equals("default") && !DISCORD_CATEGORIES.contains(fb)) {
                p.add("discord.channels." + cat + ".fallback: must be 'default' or another category (got '" + fb + "')");
                fb = "default";
            }
            List<String> roles = new ArrayList<>();
            if (c != null) for (String r : c.getStringList("mention-roles")) {
                if (!r.matches("\\d{5,25}")) p.add("discord.channels." + cat + ".mention-roles: '" + r + "' is not a numeric Discord role id");
                else roles.add(r);
            }
            channels.put(cat, new DiscordChannel(c == null || c.getBoolean("enabled", true), List.copyOf(urls), fb, List.copyOf(roles),
                    c == null ? Set.of() : lowerSet(c.getStringList("mention-events"))));
        }
        // fallback chains must terminate
        for (String cat : DISCORD_CATEGORIES) {
            String cur = cat;
            int hops = 0;
            while (!cur.equals("default") && hops++ <= DISCORD_CATEGORIES.size()) cur = channels.get(cur).fallback();
            if (!cur.equals("default")) p.add("discord.channels." + cat + ".fallback: the fallback chain loops");
        }
        Map<String, DiscordEvent> events = new LinkedHashMap<>();
        ConfigurationSection es = y.getConfigurationSection("discord.events");
        boolean anyEventUrl = false;
        if (es != null) {
            for (String k : es.getKeys(false)) {
                List<String> urls = new ArrayList<>(urlList(es.getConfigurationSection(k), "webhooks", env, "discord.events." + k + ".webhooks", p));
                String single = resolveSecret(es.getString(k + ".webhook-url", ""), env);
                if (!single.isBlank()) {
                    if (!validWebhook(single)) p.add("discord.events." + k + ".webhook-url: must be an https Discord webhook URL");
                    else urls.add(single);
                }
                String cat = es.getString(k + ".category", "").toLowerCase(Locale.ROOT);
                if (!cat.isEmpty() && !DISCORD_CATEGORIES.contains(cat)) {
                    p.add("discord.events." + k + ".category: unknown category '" + cat + "'");
                    cat = "";
                }
                if (!urls.isEmpty()) anyEventUrl = true;
                events.put(k, new DiscordEvent(es.getBoolean(k + ".enabled", true), cat, List.copyOf(urls)));
            }
        }
        boolean anyChannelUrl = channels.values().stream().anyMatch(ch -> !ch.webhooks().isEmpty());
        if (dEnabled && defaultUrl.isBlank() && !anyChannelUrl && !anyEventUrl) {
            p.add("discord.enabled is true but no webhook is configured (set discord.webhook-url, a channel's webhooks, or an event webhook)");
        }
        this.discord = new Discord(dEnabled, defaultUrl, y.getString("discord.username", "AuraPunishments"),
                y.getString("discord.avatar-url", ""), Map.copyOf(channels), Map.copyOf(events),
                range(y, "discord.timeout-seconds", 10, 1, 60, p), range(y, "discord.max-retries", 3, 0, 20, p),
                range(y, "discord.outbox-retention-days", 14, 1, 365, p), 1000L * range(y, "discord.retry-backoff-seconds", 5, 1, 300, p), legacy,
                punishLog(y, "discord.punishment-log", true, env, p, secrets), punishLog(y, "discord.public-log", false, env, p, secrets));
        if (dEnabled && publicLogNeedsUrl(this.discord.publicLog())) p.add("discord.public-log.enabled is true but discord.public-log.webhooks is empty: the public log never uses the private staff webhooks");

        this.auditRetentionDays = range(y, "retention.audit-days", 0, 0, 36500, p);
        this.jailEnabled = y.getBoolean("integrations.jailreloaded.enabled", true);
        this.closeGuisOnPermissionChange = y.getBoolean("integrations.luckperms.close-guis-on-permission-change", true);
    }

    public static Settings parse(YamlConfiguration y) throws ConfigException {
        return parse(y, null, System::getenv);
    }

    /** @param secrets optional webhooks.yml (kept out of config.yml so it can have tighter file permissions / stay out of backups) */
    public static Settings parse(YamlConfiguration y, YamlConfiguration secrets, java.util.function.UnaryOperator<String> env) throws ConfigException {
        List<String> problems = new ArrayList<>();
        Settings s = new Settings(y, secrets, env, problems);
        if (!problems.isEmpty()) throw new ConfigException(problems);
        return s;
    }

    // ------------------------------------------------------------------ helpers
    public static final Set<String> PUNISH_LOG_EVENTS = Set.of("issued", "modified", "revoked", "expired", "appeal", "judicial");
    public static final Set<String> PUNISH_LOG_FIELDS = Set.of("player", "type", "reason", "duration", "expires", "issuer", "punishment-id",
            "server", "evidence", "source", "network-status", "case");
    /** The only fields a public (player-facing) notice may ever contain; nothing about staff, evidence, notes or addresses. */
    public static final Set<String> PUBLIC_LOG_FIELDS = Set.of("player", "type", "reason", "duration", "expires", "server");

    private static boolean publicLogNeedsUrl(PunishLog l) {
        return l.enabled() && l.webhooks().isEmpty();
    }

    private static PunishLog punishLog(YamlConfiguration y, String path, boolean defaultEnabled, java.util.function.UnaryOperator<String> env,
                                       List<String> p, YamlConfiguration secrets) {
        boolean pub = path.endsWith("public-log");
        List<String> urls = new ArrayList<>(urlList(y.getConfigurationSection(path), "webhooks", env, path + ".webhooks", p));
        if (secrets != null) urls.addAll(secretUrls(secrets, pub ? "public-log" : "punishment-log", env, "webhooks.yml " + (pub ? "public-log" : "punishment-log"), p));
        EnumSet<PunishType> ty = y.isList(path + ".types") ? types(y.getStringList(path + ".types"), path + ".types", p)
                : (pub ? EnumSet.of(PunishType.BAN, PunishType.IPBAN) : EnumSet.allOf(PunishType.class));
        Set<String> ev = new java.util.LinkedHashSet<>();
        for (String e : y.isList(path + ".events") ? y.getStringList(path + ".events") : (pub ? List.of("issued") : new ArrayList<>(PUNISH_LOG_EVENTS))) {
            String k = e.toLowerCase(Locale.ROOT);
            if (!PUNISH_LOG_EVENTS.contains(k)) p.add(path + ".events: unknown event '" + e + "' (use " + String.join(", ", new java.util.TreeSet<>(PUNISH_LOG_EVENTS)) + ")");
            else ev.add(k);
        }
        Set<String> fl = new java.util.LinkedHashSet<>();
        List<String> defFields = pub ? List.of("player", "type", "duration") : new ArrayList<>(PUNISH_LOG_FIELDS);
        for (String f : y.isList(path + ".fields") ? y.getStringList(path + ".fields") : defFields) {
            String k = f.toLowerCase(Locale.ROOT);
            if (!PUNISH_LOG_FIELDS.contains(k)) p.add(path + ".fields: unknown field '" + f + "' (use " + String.join(", ", new java.util.TreeSet<>(PUNISH_LOG_FIELDS)) + ")");
            else if (pub && !PUBLIC_LOG_FIELDS.contains(k)) p.add(path + ".fields: '" + f + "' can never appear in the public log (allowed: " + String.join(", ", new java.util.TreeSet<>(PUBLIC_LOG_FIELDS)) + ")");
            else fl.add(k);
        }
        boolean showReason = y.getBoolean(path + ".show-reason", false);
        if (pub && showReason) fl.add("reason");
        if (!pub) showReason = fl.contains("reason");
        return new PunishLog(y.getBoolean(path + ".enabled", defaultEnabled), List.copyOf(urls), Set.copyOf(ty), Set.copyOf(ev), Set.copyOf(fl), showReason);
    }

    private static int range(YamlConfiguration y, String path, int def, int min, int max, List<String> p) {
        int v = y.getInt(path, def);
        if (v < min || v > max) {
            p.add(path + ": must be between " + min + " and " + max + " (got " + v + ")");
            return def;
        }
        return v;
    }

    private static long dur(YamlConfiguration y, String path, String def, boolean allowPermanent, List<String> p) {
        String text = y.getString(path, def);
        if (text != null && text.trim().matches("0[smhdw]?")) return 0L; // an explicit zero ("off") is valid for optional windows
        long v = Durations.parse(text);
        if (v == -2 || (!allowPermanent && v == Durations.PERMANENT)) {
            p.add(path + ": invalid duration '" + text + "'");
            return Durations.parse(def);
        }
        return v;
    }

    private static EnumSet<PunishType> types(List<String> in, String path, List<String> p) {
        EnumSet<PunishType> out = EnumSet.noneOf(PunishType.class);
        for (String s : in) {
            try {
                out.add(PunishType.parse(s));
            } catch (IllegalArgumentException e) {
                p.add(path + ": unknown punishment type '" + s + "'");
            }
        }
        return out;
    }

    public static final List<String> DISCORD_CATEGORIES = List.of("punishment", "judicial", "custody", "owner-review", "direct-emergency", "appeal", "administrative");

    private static int defaultPriority(String k) {
        return switch (k) {
            case "custody-overdue" -> 100;
            case "emergency-overdue" -> 95;
            case "custody-imminent" -> 90;
            case "emergency-pending" -> 80;
            case "quick-review-overdue" -> 75;
            case "appeal" -> 60;
            case "awaiting-sentencing" -> 55;
            case "awaiting-verdict" -> 50;
            case "quick-review-pending" -> 45;
            case "further-investigation" -> 40;
            default -> 30; // awaiting-trial
        };
    }

    /** Resolves ${env:NAME}; a missing variable yields "" (reported as an unconfigured webhook, never echoed). */
    static String resolveSecret(String raw, java.util.function.UnaryOperator<String> env) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("${env:") && t.endsWith("}")) {
            String v = env.apply(t.substring(6, t.length() - 1));
            return v == null ? "" : v.trim();
        }
        return t;
    }

    private static List<String> urlList(org.bukkit.configuration.ConfigurationSection sec, String key, java.util.function.UnaryOperator<String> env, String path, List<String> p) {
        List<String> out = new ArrayList<>();
        if (sec == null) return out;
        for (String raw : sec.getStringList(key)) {
            String u = resolveSecret(raw, env);
            if (u.isBlank()) continue;
            if (!validWebhook(u)) p.add(path + ": entry is not a valid https Discord webhook URL (value hidden)");
            else out.add(u);
        }
        return out;
    }

    private static List<String> secretUrls(YamlConfiguration secrets, String cat, java.util.function.UnaryOperator<String> env, String path, List<String> p) {
        List<String> out = new ArrayList<>();
        List<String> raw = secrets.isList(cat) ? secrets.getStringList(cat) : secrets.isString(cat) ? List.of(secrets.getString(cat, "")) : List.of();
        for (String r : raw) {
            String u = resolveSecret(r, env);
            if (u.isBlank()) continue;
            if (!validWebhook(u)) p.add(path + ": entry is not a valid https Discord webhook URL (value hidden)");
            else out.add(u);
        }
        return out;
    }

    private static Set<String> lowerSet(List<String> in) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT).trim());
        return Set.copyOf(out);
    }

    private static String serverNameOf(org.bukkit.configuration.file.YamlConfiguration y) {
        return y.getString("network.server-name", "").trim();
    }

    private static String safeFile(String name, List<String> p) {
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,60}")) {
            p.add("database.file: must be a plain file name (letters, digits, . _ -)");
            return "punishments.db";
        }
        return name;
    }

    public static boolean validWebhook(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            java.net.URI u = new java.net.URI(url.trim());
            String host = u.getHost();
            return "https".equalsIgnoreCase(u.getScheme()) && host != null
                    && (host.equals("discord.com") || host.endsWith(".discord.com")
                    || host.equals("discordapp.com") || host.endsWith(".discordapp.com"))
                    && u.getPath() != null && u.getPath().startsWith("/api/webhooks/");
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ convenience
    public boolean notify(String event) {
        return notifications.getOrDefault(event, true);
    }

    public Map<String, Long> tiersFor(PunishType t) {
        return limits.getOrDefault(t, new LinkedHashMap<>());
    }
}
