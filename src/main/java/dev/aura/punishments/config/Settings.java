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

    public record TimeServed(boolean enabled, double rate, Set<PunishType> appliesTo) {}

    public record Judicial(boolean prosecutorMayJudge, boolean requireAssignedJudge, int minVerdictReason,
                           TimeServed timeServed) {}

    public record Escalation(boolean categoryScope, boolean countDirect) {}

    public record Direct(boolean enabled, Severity minSeverity, int minDescription, int minJustification,
                         int minEvidence, Set<PunishType> typeNameToConfirm) {}

    public record Emergency(boolean enabled, Set<PunishType> allowedTypes, long defaultDuration, long maxDuration,
                            long reviewDeadline, long overdueRepeat) {}

    public record Quick(boolean requireReason, Set<PunishType> requireEvidence) {}

    public record Protections(boolean enforceHierarchy, boolean blockSelf, String immunityNode) {}

    public record Mute(Set<String> blockedCommands, Set<String> blockedSubcommands, Set<String> allowedCommands) {}

    public record Legacy(boolean block, Set<String> blocked) {}

    public record DiscordEvent(boolean enabled, String webhook) {}

    public record Discord(boolean enabled, String webhook, String username, String avatar,
                          Map<String, DiscordEvent> events, int timeoutSeconds, int maxRetries) {}

    public final boolean debug;
    public final String dbFile;
    public final int dbTimeoutSeconds;
    public final Gui gui;
    public final Cases cases;
    public final Custody custody;
    public final Judicial judicial;
    public final Escalation escalation;
    public final Direct direct;
    public final Emergency emergency;
    public final Quick quick;
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

    private Settings(YamlConfiguration y, List<String> p) {
        this.debug = y.getBoolean("general.debug", false);
        String dbType = y.getString("database.type", "sqlite");
        if (!"sqlite".equalsIgnoreCase(dbType)) p.add("database.type: only 'sqlite' is supported in 1.0.0 (got '" + dbType + "')");
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
        double rate = y.getDouble("judicial.time-served.rate", 1.0);
        if (rate < 0 || rate > 1) p.add("judicial.time-served.rate: must be between 0.0 and 1.0");
        this.judicial = new Judicial(y.getBoolean("judicial.prosecutor-may-judge", true),
                y.getBoolean("judicial.require-assigned-judge-for-verdict", true),
                range(y, "judicial.min-verdict-reason-length", 10, 0, 200, p),
                new TimeServed(y.getBoolean("judicial.time-served.enabled", true), rate, credit));

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

        this.quick = new Quick(y.getBoolean("punishments.quick.require-reason", true),
                types(y.getStringList("punishments.quick.require-evidence"), "punishments.quick.require-evidence", p));

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

        Map<String, DiscordEvent> events = new LinkedHashMap<>();
        ConfigurationSection es = y.getConfigurationSection("discord.events");
        String defaultUrl = y.getString("discord.webhook-url", "");
        boolean dEnabled = y.getBoolean("discord.enabled", false);
        if (dEnabled && !validWebhook(defaultUrl)) {
            boolean anyOverride = false;
            if (es != null) {
                for (String k : es.getKeys(false)) {
                    if (validWebhook(es.getString(k + ".webhook-url", ""))) anyOverride = true;
                }
            }
            if (!anyOverride) p.add("discord.webhook-url: enabled but no valid https webhook URL is configured");
        }
        if (!defaultUrl.isBlank() && !validWebhook(defaultUrl)) p.add("discord.webhook-url: must be an https://discord.com/api/webhooks/... URL");
        if (es != null) {
            for (String k : es.getKeys(false)) {
                String u = es.getString(k + ".webhook-url", "");
                if (!u.isBlank() && !validWebhook(u)) p.add("discord.events." + k + ".webhook-url: must be an https Discord webhook URL");
                events.put(k, new DiscordEvent(es.getBoolean(k + ".enabled", true), u));
            }
        }
        this.discord = new Discord(dEnabled, defaultUrl, y.getString("discord.username", "AuraPunishments"),
                y.getString("discord.avatar-url", ""), Map.copyOf(events),
                range(y, "discord.timeout-seconds", 10, 1, 60, p), range(y, "discord.max-retries", 3, 0, 10, p));

        this.auditRetentionDays = range(y, "retention.audit-days", 0, 0, 36500, p);
        this.jailEnabled = y.getBoolean("integrations.jailreloaded.enabled", true);
        this.closeGuisOnPermissionChange = y.getBoolean("integrations.luckperms.close-guis-on-permission-change", true);
    }

    public static Settings parse(YamlConfiguration y) throws ConfigException {
        List<String> problems = new ArrayList<>();
        Settings s = new Settings(y, problems);
        if (!problems.isEmpty()) throw new ConfigException(problems);
        return s;
    }

    // ------------------------------------------------------------------ helpers
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

    private static Set<String> lowerSet(List<String> in) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT).trim());
        return Set.copyOf(out);
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
