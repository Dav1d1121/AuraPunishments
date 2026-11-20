package dev.aura.punishments.config;

import dev.aura.punishments.config.Offense.Recommendation;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.util.Durations;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Validated, immutable view of offenses.yml. */
public final class OffenseCatalog {
    private final Map<String, Offense> offenses;

    private OffenseCatalog(Map<String, Offense> offenses) {
        this.offenses = Collections.unmodifiableMap(offenses);
    }

    public Optional<Offense> get(String key) {
        return Optional.ofNullable(key == null ? null : offenses.get(key.toLowerCase(Locale.ROOT)));
    }

    public List<Offense> all() {
        return List.copyOf(offenses.values());
    }

    public static OffenseCatalog parse(YamlConfiguration yaml) throws ConfigException {
        List<String> problems = new ArrayList<>();
        Map<String, Offense> out = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("offenses");
        if (root == null) {
            throw new ConfigException(List.of("offenses.yml: missing top-level 'offenses' section"));
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            String at = "offenses." + key;
            if (s == null) {
                problems.add(at + ": must be a section");
                continue;
            }
            if (!key.matches("[a-z0-9_]{1,40}")) {
                problems.add(at + ": key must be lower-case letters, digits or underscore");
                continue;
            }
            Severity sev;
            try {
                sev = Severity.parse(s.getString("severity", "MINOR"));
            } catch (IllegalArgumentException e) {
                problems.add(at + ".severity: must be MINOR, MODERATE, MAJOR or SEVERE");
                sev = Severity.MINOR;
            }
            EnumSet<PunishType> allowed = EnumSet.noneOf(PunishType.class);
            for (String t : s.getStringList("allowed-punishments")) {
                try {
                    allowed.add(PunishType.parse(t));
                } catch (IllegalArgumentException e) {
                    problems.add(at + ".allowed-punishments: unknown type '" + t + "'");
                }
            }
            if (allowed.isEmpty()) problems.add(at + ".allowed-punishments: must list at least one type");
            long max = parseDuration(s.getString("max-duration", "30d"), at + ".max-duration", problems);
            List<Recommendation> recs = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("recommendations")) {
                try {
                    int priors = ((Number) m.get("priors")).intValue();
                    PunishType type = PunishType.parse(String.valueOf(m.get("type")));
                    long dur = 0;
                    if (type.canBeTimed()) {
                        Object d = m.get("duration");
                        dur = d == null ? -2 : Durations.parse(String.valueOf(d));
                        if (dur == -2) {
                            problems.add(at + ".recommendations: timed type " + type + " needs a valid duration");
                            continue;
                        }
                    }
                    if (!allowed.contains(type)) {
                        problems.add(at + ".recommendations: " + type + " is not in allowed-punishments");
                        continue;
                    }
                    recs.add(new Recommendation(priors, type, dur));
                } catch (RuntimeException e) {
                    problems.add(at + ".recommendations: malformed entry " + m);
                }
            }
            Map<PunishType, List<Long>> presets = new EnumMap<>(PunishType.class);
            ConfigurationSection ps = s.getConfigurationSection("presets");
            if (ps != null) {
                for (String tk : ps.getKeys(false)) {
                    try {
                        PunishType t = PunishType.parse(tk);
                        List<Long> list = new ArrayList<>();
                        for (String d : ps.getStringList(tk)) {
                            long v = Durations.parse(d);
                            if (v == -2) problems.add(at + ".presets." + tk + ": invalid duration '" + d + "'");
                            else list.add(v);
                        }
                        presets.put(t, list);
                    } catch (IllegalArgumentException e) {
                        problems.add(at + ".presets: unknown type '" + tk + "'");
                    }
                }
            }
            ConfigurationSection ev = s.getConfigurationSection("evidence");
            int minCase = ev == null ? 0 : ev.getInt("minimum-for-case", 0);
            int minDirect = ev == null ? 1 : ev.getInt("minimum-for-direct", 1);
            if (minCase < 0 || minDirect < 0) problems.add(at + ".evidence: minimums cannot be negative");
            out.put(key, new Offense(key, s.getString("display", key), s.getString("description", ""),
                    s.getString("icon", "PAPER"), sev, s.getBoolean("direct-eligible", false),
                    s.getString("permission", ""), minCase, minDirect, allowed, max, List.copyOf(recs), presets));
        }
        if (out.isEmpty() && problems.isEmpty()) problems.add("offenses.yml: no offenses defined");
        if (!out.containsKey("other") && problems.isEmpty()) problems.add("offenses.yml: an 'other' offense is required as the fallback category");
        if (!problems.isEmpty()) throw new ConfigException(problems);
        return new OffenseCatalog(out);
    }

    private static long parseDuration(String text, String at, List<String> problems) {
        long v = Durations.parse(text);
        if (v == -2) {
            problems.add(at + ": invalid duration '" + text + "'");
            return Durations.DAY;
        }
        return v;
    }
}
