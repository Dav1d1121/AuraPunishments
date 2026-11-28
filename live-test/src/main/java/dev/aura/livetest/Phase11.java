package dev.aura.livetest;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.Sql;
import dev.aura.punishments.service.JudicialMode;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 1.3.0 live phase: boot a REAL data folder written by an earlier version (1.0.0 schema v1, or 1.x schema v2) with the 1.3.0 jar.
 * This part checks what only the running plugin can show; the row-by-row comparison of the migrated database with the original is done
 * afterwards by the driver script (see docs/evidence).
 */
final class Phase11 {
    private final LiveTest t;

    Phase11(LiveTest t) {
        this.t = t;
    }

    private long count(String sql) throws Exception {
        return t.ap.core().db.txSync(cn -> Sql.count(cn, sql));
    }

    void run() throws Exception {
        File data = t.ap.getDataFolder();
        List<String> exp = Files.readAllLines(new File(t.getDataFolder(), "expect-11.txt").toPath());
        String originalConfigHash = exp.get(0);
        long activeBlocks = Long.parseLong(exp.get(1));
        t.check("the plugin enabled on the old data folder", t.ap.isEnabled(), "");
        t.check("database migrated to schema v3", t.ap.core().db.schemaVersion() == 3, String.valueOf(t.ap.core().db.schemaVersion()));
        t.check("the judicial system is ON (the old config.yml has no judicial.enabled line): behaviour is unchanged by the upgrade", t.ap.core().mode.enabled()
                && t.ap.core().mode.state() == JudicialMode.State.ENABLED, "");
        byte[] cfg = Files.readAllBytes(new File(data, "config.yml").toPath());
        t.check("config.yml was not modified by the upgrade", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cfg)).equals(originalConfigHash), "");
        t.check("a reference copy of the new options was written next to it", new File(data, "config-" + t.ap.getPluginMeta().getVersion() + "-reference.yml").isFile(), "");
        t.check("a pre-migration backup of the database exists", new File(data, "backups").isDirectory()
                && java.util.Arrays.stream(new File(data, "backups").list()).anyMatch(n -> n.contains("pre-v2") || n.contains("pre-v3")), String.join(",", new File(data, "backups").list())); // named after the first migration step taken
        t.check("every row has a unique id and the server name", count("SELECT COUNT(*) FROM punishments WHERE uid IS NULL OR origin IS NULL") == 0
                && count("SELECT COUNT(*) FROM cases WHERE uid IS NULL OR origin IS NULL") == 0
                && count("SELECT COUNT(*) FROM evidence WHERE uid IS NULL OR origin IS NULL") == 0
                && count("SELECT COUNT(*) FROM punishments") == count("SELECT COUNT(DISTINCT uid) FROM punishments"), "");
        t.check("the audit hash chain still verifies after the migration", t.ap.core().db.txSync(cn -> Repo.Audits.verify(cn)) == -1L, "");
        // enforcement is judged the way the server does it: a lookup must find the punishment, and it must find NOTHING that has run out
        var rows = t.ap.core().db.txSync(cn -> Repo.Punishments.active(cn));
        long live = 0;
        long wrong = 0;
        long now = System.currentTimeMillis();
        for (var p : rows) {
            if (p.type() != dev.aura.punishments.model.PunishType.BAN && p.type() != dev.aura.punishments.model.PunishType.IPBAN && p.type() != dev.aura.punishments.model.PunishType.MUTE) continue;
            var hit = p.type() == dev.aura.punishments.model.PunishType.MUTE ? t.ap.punishments().muteFor(p.targetUuid()) : t.ap.punishments().banFor(p.targetUuid(), p.ip());
            boolean running = p.expiresAt() == null || p.expiresAt() > now;
            if (running && hit.isPresent() && hit.get().id() == p.id()) live++;
            else if (!running && hit.isPresent() && hit.get().id() == p.id()) wrong++;
        }
        t.check("every ban/IP ban/mute that was running before the upgrade is enforced right after it (" + activeBlocks + " expected)", live == activeBlocks, live + " enforced");
        t.check("nothing that had already run out is enforced", wrong == 0, wrong + " expired punishments still enforced");
        long judicialBefore = count("SELECT COUNT(*) FROM cases WHERE status IN ('OPEN','UNDER_INVESTIGATION','AWAITING_TRIAL','IN_TRIAL','AWAITING_VERDICT','GUILTY','AWAITING_SENTENCING','UNDER_REVIEW','APPEALED')");
        t.ap.core().mode.refresh().get();
        t.check("the open-work report agrees with the data (" + judicialBefore + " open cases)", t.ap.core().mode.report().openCases() == judicialBefore,
                String.valueOf(t.ap.core().mode.report()));
        t.check("old cases are readable through the services", count("SELECT COUNT(*) FROM cases") == 0
                || LiveTest.r(t.ap.cases().detail(LiveTest.god("LiveOwner11"), t.ap.core().db.txSync(cn -> Sql.one(cn, "SELECT MIN(id) FROM cases", rs -> rs.getLong(1))))).isOk(), "");
        t.check("old punishments are readable through the punishment screen service", count("SELECT COUNT(*) FROM punishments") == 0
                || LiveTest.r(t.ap.records().detail(LiveTest.god("LiveOwner11"), t.ap.core().db.txSync(cn -> Sql.one(cn, "SELECT MIN(id) FROM punishments", rs -> rs.getLong(1))))).isOk(), "");
    }
}
