package dev.aura.punishments.service;

import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.model.Simple.Source;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A local copy of every ACTIVE ban, IP ban and mute, kept next to the plugin's files while a shared database is in use. It exists for
 * one reason: when this server starts and the database cannot be reached, the bans and mutes it already knew about keep being enforced
 * instead of silently disappearing. It is never the authority and never used while the database answers; when the database is back the
 * index is reloaded from it. Written atomically, so a crash cannot leave half a file.
 */
public final class ActiveSnapshot {
    private ActiveSnapshot() {}

    public static File file(File dataFolder) {
        return new File(dataFolder, "active-punishments.yml");
    }

    public static void save(File file, List<Punishment> active) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("saved-at", System.currentTimeMillis());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Punishment p : active) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("type", p.type().name());
            m.put("status", p.status().name());
            m.put("target-uuid", p.targetUuid().toString());
            m.put("target-name", p.targetName());
            m.put("ip", p.ip());
            m.put("reason", p.reason());
            m.put("issuer-name", p.issuerName());
            m.put("issued-at", p.issuedAt());
            m.put("expires-at", p.expiresAt());
            m.put("origin", p.origin());
            rows.add(m);
        }
        y.set("punishments", rows);
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        y.save(tmp);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** @return the saved punishments; empty when there is no file or it cannot be read (the caller reports that) */
    public static List<Punishment> load(File file) {
        List<Punishment> out = new ArrayList<>();
        if (!file.isFile()) return out;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : y.getMapList("punishments")) {
            try {
                Object exp = m.get("expires-at");
                out.add(new Punishment(((Number) m.get("id")).longValue(), PunishType.valueOf(String.valueOf(m.get("type"))), PunishStatus.valueOf(String.valueOf(m.get("status"))),
                        UUID.fromString(String.valueOf(m.get("target-uuid"))), String.valueOf(m.get("target-name")), m.get("ip") == null ? null : String.valueOf(m.get("ip")),
                        String.valueOf(m.get("reason")), null, String.valueOf(m.get("issuer-name")), ((Number) m.get("issued-at")).longValue(),
                        exp == null ? null : ((Number) exp).longValue(), null, null, null, null, Source.STANDARD, null, true, null, false, null, null, null, null,
                        null, m.get("origin") == null ? null : String.valueOf(m.get("origin")), null, null, null, null, null));
            } catch (RuntimeException e) {
                // one unreadable row must not discard the others
            }
        }
        return out;
    }

    public static boolean exists(File dataFolder) {
        return file(dataFolder).isFile();
    }
}
