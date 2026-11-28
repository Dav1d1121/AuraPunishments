package dev.aura.punishments.service;

import dev.aura.punishments.db.Database;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.Schema;
import dev.aura.punishments.db.Sql;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Moves the data of one old per-server SQLite database (1.0 - 1.3.x) into the shared network database.
 *
 * <ul>
 *   <li><b>The original is never touched.</b> The file (and its -wal/-shm) is COPIED to a temporary folder; the copy is upgraded to the
 *       current schema and read from there.</li>
 *   <li><b>Dry run = the real thing, rolled back.</b> Both run the same code in one transaction; a dry run (or any error) rolls it back, so
 *       what the report promises is what a real run does, and a failed run leaves the shared database exactly as it was.</li>
 *   <li><b>Ids are remapped, references kept.</b> Every row gets a new id in the shared database; cases, punishments, custody, evidence,
 *       notes, appeals, reviews and their events follow their parents. {@code migration_map} remembers old id -&gt; new id per source.</li>
 *   <li><b>Safe to repeat.</b> A row that was imported before (same source and old id), that already exists (same stable uid or the same
 *       player/type/time/issuer) or a file with the same SHA-256 adds nothing.</li>
 *   <li><b>Nothing comes back to life.</b> Ended, expired and revoked punishments stay ended. An active ban or mute whose time already ran
 *       out is recorded as expired. When two sources hold an active ban (or mute) for the same player, the stricter one stays active and
 *       the other is recorded as superseded.</li>
 *   <li><b>Unfinished business blocks the import</b> (pretrial detention, interrupted jail operations, live jails unless converted), and so
 *       does any row that cannot be stored: the report says which.</li>
 * </ul>
 */
public final class SqliteImport {
    public static final String FOLDER = "migration";

    public record Options(boolean dryRun, String label, boolean convertLiveJails) {}

    public static final class TableResult {
        public final String table;
        public int read;
        public int imported;
        public int alreadyThere;
        public int merged;
        public int skipped;

        TableResult(String table) {
            this.table = table;
        }
    }

    public static final class Report {
        public String label;
        public String fileName;
        public String sha256;
        public boolean dryRun;
        public int sourceSchema;
        public boolean stableUids;
        public final List<TableResult> tables = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public boolean alreadyImported;
        public boolean applied;

        public boolean ok() {
            return errors.isEmpty();
        }

        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add((dryRun ? "DRY RUN" : applied ? "IMPORTED" : "NOT IMPORTED") + " - " + fileName + " as source '" + label + "' (schema v" + sourceSchema + ", sha256 " + (sha256 == null ? "?" : sha256.substring(0, 12)) + ")");
            for (TableResult t : tables) {
                if (t.read == 0) continue;
                out.add(String.format(Locale.ROOT, "  %-20s read %d: %d %s, %d already there%s%s", t.table, t.read, t.imported, dryRun || !applied ? "would be imported" : "imported",
                        t.alreadyThere, t.merged > 0 ? ", " + t.merged + " merged" : "", t.skipped > 0 ? ", " + t.skipped + " skipped" : ""));
            }
            for (String n : notes) out.add("  note: " + n);
            for (String w : warnings) out.add("  WARNING: " + w);
            for (String e : errors) out.add("  ERROR: " + e);
            if (alreadyImported) out.add("  This exact file was imported before - nothing to do.");
            else if (!errors.isEmpty()) out.add("  Nothing was written. Fix the errors above and run the check again.");
            else if (dryRun) out.add("  Nothing was written (dry run). Run it for real with: /ap migrate run " + fileName + " " + label + " confirm");
            return out;
        }
    }

    /** Thrown inside the import transaction to roll everything back after the report is complete. */
    private static final class Rollback extends RuntimeException {
        Rollback() {
            super(null, null, false, false);
        }
    }

    private record Spec(String table, Map<String, String> fks, List<String> fingerprint) {}

    /** Parents before children. */
    private static final List<Spec> SPECS = List.of(
            new Spec("cases", Map.of(), List.of("accused_uuid", "created_at", "offense_key", "kind")),
            new Spec("custody", Map.of("case_id", "cases"), List.of("uuid", "case_id", "requested_at")),
            new Spec("punishments", Map.of("case_id", "cases", "custody_id", "custody"), List.of("target_uuid", "type", "issued_at", "issuer_name", "expires_at", "reason")),
            new Spec("case_events", Map.of("case_id", "cases"), List.of("case_id", "ts", "type", "actor_name")),
            new Spec("evidence", Map.of("case_id", "cases", "punishment_id", "punishments", "corrects_id", "evidence"), List.of("case_id", "punishment_id", "reference", "submitted_at")),
            new Spec("notes", Map.of("case_id", "cases", "punishment_id", "punishments"), List.of("case_id", "punishment_id", "created_at", "author_name")),
            new Spec("appeals", Map.of("case_id", "cases", "punishment_id", "punishments"), List.of("case_id", "punishment_id", "filed_at", "filed_by_name")),
            new Spec("direct_actions", Map.of("case_id", "cases", "punishment_id", "punishments"), List.of("case_id", "punishment_id", "created_at", "actor_name")),
            new Spec("reviews", Map.of("case_id", "cases", "punishment_id", "punishments"), List.of("target_uuid", "ptype", "issued_at", "issuer_name")),
            new Spec("review_events", Map.of("review_id", "reviews"), List.of("review_id", "ts", "type", "actor_name")));

    private final Core core;
    private final Logger log;
    private final File dataFolder;

    public SqliteImport(Core core, Logger log, File dataFolder) {
        this.core = core;
        this.log = log;
        this.dataFolder = dataFolder;
    }

    public File folder() {
        return new File(dataFolder, FOLDER);
    }

    /** The SQLite files waiting in the migration folder. */
    public List<String> available() {
        File[] files = folder().listFiles((d, n) -> n.matches("[A-Za-z0-9._-]+\\.(db|sqlite|sqlite3)"));
        List<String> out = new ArrayList<>();
        if (files != null) for (File f : files) out.add(f.getName());
        java.util.Collections.sort(out);
        return out;
    }

    // ------------------------------------------------------------------ run
    public CompletableFuture<Report> run(String fileName, Options o, String actorName) {
        CompletableFuture<Report> f = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                Report r = runBlocking(fileName, o, actorName);
                writeReport(r);
                f.complete(r);
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        }, "AuraPunishments-import");
        t.setDaemon(true);
        t.start();
        return f;
    }

    /** A copy of what staff saw, kept next to the file (best effort). */
    private void writeReport(Report r) {
        try {
            Files.createDirectories(folder().toPath());
            String name = "report-" + r.label + "-" + (r.dryRun ? "check" : "run") + "-" + core.clock.now() + ".txt";
            Files.write(new File(folder(), name).toPath(), r.lines());
        } catch (IOException | RuntimeException e) {
            log.warning("Could not write the import report: " + e.getMessage());
        }
    }

    Report runBlocking(String fileName, Options o, String actorName) {
        Report r = new Report();
        r.fileName = fileName;
        r.dryRun = o.dryRun();
        r.label = o.label();
        if (!core.db.shared()) {
            r.errors.add("An import goes INTO a shared database (database.type: mariadb | mysql). This server uses SQLite.");
            return r;
        }
        if (!fileName.matches("[A-Za-z0-9._-]+\\.(db|sqlite|sqlite3)")) {
            r.errors.add("Not a SQLite file name: " + fileName);
            return r;
        }
        if (!o.label().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) {
            r.errors.add("The source label must be 1-40 letters, digits, '.', '_' or '-' (for example the old server's name).");
            return r;
        }
        File source = new File(folder(), fileName);
        try {
            if (!source.getCanonicalFile().getParentFile().equals(folder().getCanonicalFile()) || !source.isFile()) {
                r.errors.add("There is no file " + fileName + " in plugins/AuraPunishments/" + FOLDER + "/.");
                return r;
            }
            r.sha256 = sha256(source);
        } catch (IOException e) {
            r.errors.add("Cannot read " + fileName + ": " + e.getMessage());
            return r;
        }
        Path tmp = null;
        Database src = null;
        Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        try {
            tmp = Files.createTempDirectory("ap-import");
            for (String suffix : new String[]{"", "-wal", "-shm"}) {
                File part = new File(source.getPath() + suffix);
                if (part.isFile()) Files.copy(part.toPath(), tmp.resolve("source.db" + suffix), StandardCopyOption.REPLACE_EXISTING);
            }
            Path copy = tmp.resolve("source.db");
            // what the ORIGINAL says about itself, before the copy is upgraded
            int original;
            try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + copy.toAbsolutePath()); Statement st = raw.createStatement()) {
                try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                    String res = rs.next() ? rs.getString(1) : "no answer";
                    if (!"ok".equalsIgnoreCase(res)) {
                        r.errors.add("SQLite integrity check of the file failed (" + res + "). Restore the file from a backup first.");
                        return r;
                    }
                }
                original = 0;
                try (ResultSet rs = st.executeQuery("SELECT v FROM meta WHERE k='schema_version'")) {
                    if (rs.next()) original = Integer.parseInt(rs.getString(1));
                } catch (SQLException e) {
                    r.errors.add("This file has no AuraPunishments data (" + e.getMessage() + ").");
                    return r;
                }
            }
            r.sourceSchema = original;
            r.stableUids = original >= 3;
            if (original > Schema.latest()) {
                r.errors.add("This file is from a NEWER AuraPunishments (schema v" + original + ", this one supports v" + Schema.latest() + "). Update this plugin first.");
                return r;
            }
            src = new Database(copy, log, null);
            src.open();
            Database s = src;
            for (String table : tableNames()) {
                data.put(table, s.txSync(c -> readAll(c, table)));
            }
        } catch (IOException | SQLException | RuntimeException e) {
            r.errors.add("Cannot read " + fileName + ": " + e.getMessage());
            return r;
        } finally {
            if (src != null) src.close();
            deleteQuietly(tmp);
        }
        try {
            core.db.txParallel(c -> {
                // a retried transaction starts from a clean report
                r.tables.clear();
                importAll(c, r, data, o, actorName);
                if (!r.ok() || o.dryRun() || r.alreadyImported) throw new Rollback();
                r.applied = true;
                return null;
            });
        } catch (Rollback rb) {
            r.applied = false;
        } catch (SQLException | RuntimeException e) {
            r.applied = false;
            r.errors.add("The shared database refused the import (nothing was written): " + e.getMessage());
        }
        return r;
    }

    private static List<String> tableNames() {
        List<String> t = new ArrayList<>();
        t.add("profiles");
        for (Spec s : SPECS) t.add(s.table());
        t.add("player_notifications");
        t.add("audit_log");
        t.add("pending_ops");
        t.add("discord_outbox");
        return t;
    }

    // ------------------------------------------------------------------ the import itself (one transaction)
    private void importAll(Connection c, Report r, Map<String, List<Map<String, Object>>> data, Options o, String actor) throws SQLException {
        r.warnings.clear();
        r.notes.clear();
        r.errors.clear();
        r.alreadyImported = false;
        r.applied = false;
        long now = core.clock.now();
        String label = o.label();
        Long prior = Sql.one(c, "SELECT id FROM migration_runs WHERE sha256=? ORDER BY id DESC LIMIT 1", rs -> rs.getLong(1), r.sha256);
        if (prior != null) {
            r.alreadyImported = true;
            return;
        }
        // --- things that must be settled on the old server first
        for (Map<String, Object> op : data.get("pending_ops")) {
            String st = String.valueOf(op.get("state"));
            if (st.equals("PENDING") || st.equals("EXTERNAL_DONE")) {
                r.errors.add("The old server has an unfinished operation (#" + op.get("id") + " " + op.get("kind") + "). Start it once so it finishes (or reconcile) and stop it again, then retry.");
            }
        }
        for (Map<String, Object> cu : data.get("custody")) {
            if (isLiveCustody(String.valueOf(cu.get("status")))) {
                r.errors.add("Pretrial detention is still running for case #" + cu.get("case_id") + " (custody #" + cu.get("id") + "). Release it on the old server before importing.");
            }
        }
        boolean network = core.netJail != null && core.netJail.enabled();
        for (Map<String, Object> p : data.get("punishments")) {
            if ("JAIL".equals(p.get("type")) && p.get("custody_id") == null && liveJail(p)) {
                if (!o.convertLiveJails()) {
                    r.errors.add("Jail punishment #" + p.get("id") + " (" + p.get("target_name") + ") is still being served. Release the prisoner on the old server, "
                            + "or import with 'convert-jails' to queue it for the network's jail backend (the old JailReloaded record must then be released by hand).");
                } else if (!network) {
                    r.errors.add("Jail punishment #" + p.get("id") + " cannot be converted: network.jail.backend is not set on this server.");
                }
            } else if ("JAIL".equals(p.get("type")) && p.get("custody_id") != null && String.valueOf(p.get("status")).equals("ACTIVE")) {
                r.errors.add("Jail sentence #" + p.get("id") + " of a case is still being served. Release it on the old server before importing.");
            }
        }
        if (!r.errors.isEmpty()) {
            for (String table : tableNames()) r.tables.add(counts(table, data));
            return;
        }
        Map<String, Map<Long, Long>> maps = new HashMap<>();
        for (Spec s : SPECS) maps.put(s.table(), new HashMap<>());
        for (var row : Sql.query(c, "SELECT tbl, old_id, new_id FROM migration_map WHERE source=?", rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3)}, label)) {
            maps.computeIfAbsent((String) row[0], k -> new HashMap<>()).put((Long) row[1], (Long) row[2]);
        }
        Long runId = o.dryRun() ? null : Sql.insert(c, "INSERT INTO migration_runs(source,file_name,sha256,run_at,run_by,summary) VALUES(?,?,?,?,?,?)",
                label, r.fileName, r.sha256, now, actor, "started");

        // --- profiles: merged by uuid
        TableResult tr = new TableResult("profiles");
        r.tables.add(tr);
        Set<String> profileCols = columns(c, "profiles");
        for (Map<String, Object> row : data.get("profiles")) {
            tr.read++;
            Map<String, Object> have = firstRow(c, "SELECT * FROM profiles WHERE uuid=?", row.get("uuid"));
            if (have == null) {
                insertRow(c, "profiles", row, profileCols, Map.of(), false);
                tr.imported++;
            } else {
                long first = Math.min(num(have.get("first_seen")), num(row.get("first_seen")));
                boolean newer = num(row.get("last_seen")) > num(have.get("last_seen"));
                Sql.update(c, "UPDATE profiles SET first_seen=?, last_seen=?, name=?, name_lower=?, last_ip=?, level=? WHERE uuid=?", first,
                        Math.max(num(have.get("last_seen")), num(row.get("last_seen"))), newer ? row.get("name") : have.get("name"), newer ? row.get("name_lower") : have.get("name_lower"),
                        newer && row.get("last_ip") != null ? row.get("last_ip") : have.get("last_ip"), newer ? row.get("level") : have.get("level"), row.get("uuid"));
                tr.merged++;
            }
        }

        // --- the rest, parents first
        Map<String, Set<String>> targetCols = new HashMap<>();
        for (Spec spec : SPECS) {
            TableResult t = new TableResult(spec.table());
            r.tables.add(t);
            Set<String> cols = targetCols.computeIfAbsent(spec.table(), k -> {
                try {
                    return columns(c, k);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Map<Long, Long> own = maps.get(spec.table());
            for (Map<String, Object> row : data.get(spec.table())) {
                t.read++;
                long oldId = num(row.get("id"));
                if (own.containsKey(oldId)) {
                    t.alreadyThere++;
                    continue;
                }
                Map<String, Object> over = new HashMap<>();
                boolean orphan = false;
                for (var fk : spec.fks().entrySet()) {
                    Object v = row.get(fk.getKey());
                    if (v == null) continue;
                    Long mapped = maps.get(fk.getValue()).get(num(v));
                    if (mapped == null) {
                        if (fk.getValue().equals(spec.table())) {
                            over.put(fk.getKey(), null); // a correction pointing at a row that is not there: keep the row, drop the pointer
                            r.warnings.add(spec.table() + " #" + oldId + " referred to a missing earlier row; the reference was dropped.");
                        } else {
                            orphan = true;
                            r.warnings.add(spec.table() + " #" + oldId + " refers to " + fk.getValue() + " #" + v + ", which is not in the file: skipped.");
                        }
                    } else {
                        over.put(fk.getKey(), mapped);
                    }
                }
                if (orphan) {
                    t.skipped++;
                    continue;
                }
                over.put("origin", label);
                if (!r.stableUids) over.put("uid", null); // an id invented when the copy was upgraded is not the same twice: let the database make one
                Long existing = findExisting(c, spec, row, over, r.stableUids);
                if (existing != null) {
                    own.put(oldId, existing);
                    if (runId != null) mapRow(c, label, spec.table(), oldId, existing, runId);
                    t.alreadyThere++;
                    continue;
                }
                if (spec.table().equals("punishments") && !prepareStatus(c, row, over, now, r, o, label)) {
                    t.skipped++;
                    continue;
                }
                long newId;
                try {
                    newId = insertRow(c, spec.table(), row, cols, over, true);
                } catch (SQLException e) {
                    r.errors.add(spec.table() + " #" + oldId + " cannot be stored: " + e.getMessage());
                    t.skipped++;
                    continue;
                }
                if (spec.table().equals("punishments") && "JAIL".equals(row.get("type")) && row.get("custody_id") == null && liveJail(row)) {
                    // a jail still being served becomes a jail the network's jail backend carries out (placeholder id, queued)
                    Sql.update(c, "UPDATE punishments SET jail_state='PENDING_PLACEMENT', jail_prisoner_id=?, jail_name=NULL, jail_error=NULL WHERE id=?",
                            dev.aura.punishments.integration.RemoteJailPort.PLACEHOLDER_PREFIX + JailService.tag(newId), newId);
                    r.notes.add("Jail punishment #" + oldId + " (" + row.get("target_name") + ") was queued as #" + newId + " for the jail backend; release the old JailReloaded record by hand.");
                }
                own.put(oldId, newId);
                if (runId != null) mapRow(c, label, spec.table(), oldId, newId, runId);
                t.imported++;
            }
        }

        // --- notifications that were never shown (a warning for a player who was offline)
        TableResult nt = new TableResult("player_notifications");
        r.tables.add(nt);
        Set<String> ncols = columns(c, "player_notifications");
        for (Map<String, Object> row : data.get("player_notifications")) {
            nt.read++;
            Map<String, Object> over = new HashMap<>();
            over.put("dedupe_key", "mig:" + label + ":" + row.get("dedupe_key"));
            String refType = String.valueOf(row.get("ref_type")).toLowerCase(Locale.ROOT);
            Map<Long, Long> refMap = refType.startsWith("punish") ? maps.get("punishments") : refType.startsWith("case") ? maps.get("cases") : refType.startsWith("custod") ? maps.get("custody") : null;
            if (row.get("ref_id") != null && refMap != null) {
                Long mapped = refMap.get(num(row.get("ref_id")));
                if (mapped == null) {
                    nt.skipped++;
                    continue;
                }
                over.put("ref_id", mapped);
            }
            if (Sql.count(c, "SELECT COUNT(*) FROM player_notifications WHERE dedupe_key=?", over.get("dedupe_key")) > 0) {
                nt.alreadyThere++;
                continue;
            }
            try {
                insertRow(c, "player_notifications", row, ncols, over, false);
                nt.imported++;
            } catch (SQLException e) {
                r.errors.add("player_notifications #" + row.get("id") + " cannot be stored: " + e.getMessage());
                nt.skipped++;
            }
        }
        TableResult skipped = new TableResult("discord_outbox");
        skipped.read = data.get("discord_outbox").size();
        skipped.skipped = skipped.read;
        r.tables.add(skipped);
        if (skipped.read > 0) r.notes.add(skipped.read + " Discord messages that had not been sent yet are not carried over (they were about events of the old server).");

        // --- the audit trail, LAST (it takes the chain's lock for the rest of the transaction): appended to the shared chain with the old entry noted
        TableResult at = new TableResult("audit_log");
        r.tables.add(at);
        Map<Long, Long> caseMap = maps.get("cases");
        for (Map<String, Object> row : data.get("audit_log")) {
            at.read++;
            long oldId = num(row.get("id"));
            Long already = Sql.one(c, "SELECT new_id FROM migration_map WHERE source=? AND tbl='audit_log' AND old_id=?", rs -> rs.getLong(1), label, oldId);
            if (already != null) {
                at.alreadyThere++;
                continue;
            }
            Long cid = row.get("case_id") == null ? null : caseMap.get(num(row.get("case_id")));
            String detail = (row.get("detail") == null ? "" : String.valueOf(row.get("detail"))) + " [imported from " + label + " #" + oldId + "]";
            Repo.Audits.append(c, num(row.get("ts")), uuidOrNull(row.get("actor_uuid")), String.valueOf(row.get("actor_name")), String.valueOf(row.get("action")),
                    uuidOrNull(row.get("target_uuid")), cid, detail, num(row.get("elevated")) != 0);
            if (runId != null) {
                long newId = Sql.count(c, "SELECT COALESCE(MAX(id),0) FROM audit_log");
                mapRow(c, label, "audit_log", oldId, newId, runId);
            }
            at.imported++;
        }
        if (!o.dryRun() && r.ok()) {
            int total = r.tables.stream().mapToInt(t -> t.imported).sum();
            Repo.Audits.append(c, now, null, actor, "admin.migrate", null, null, "imported " + r.fileName + " as '" + label + "': " + total + " rows (" + r.sha256 + ")", true);
            Sql.update(c, "UPDATE migration_runs SET summary=? WHERE id=?", total + " rows; " + r.warnings.size() + " warnings", runId);
        }
    }

    // ------------------------------------------------------------------ rules for punishments
    /**
     * Decide what state a punishment is imported in. Never makes an ended punishment active.
     *
     * @return false when the row must not be imported
     */
    private boolean prepareStatus(Connection c, Map<String, Object> row, Map<String, Object> over, long now, Report r, Options o, String label) throws SQLException {
        String type = String.valueOf(row.get("type"));
        String status = String.valueOf(row.get("status"));
        if (!status.equals("ACTIVE")) return true;
        Object exp = row.get("expires_at");
        boolean timed = !type.equals("JAIL") && exp != null && num(exp) <= now;
        if (timed) {
            over.put("status", "EXPIRED");
            over.put("ended_at", exp);
            over.put("ended_by", "System");
            over.put("end_reason", "Expired (recorded while importing " + label + ")");
            r.warnings.add("Punishment #" + row.get("id") + " (" + row.get("target_name") + ", " + type + ") was still marked active but its time had run out: imported as expired.");
            return true;
        }
        if (type.equals("BAN") || type.equals("MUTE")) {
            Map<String, Object> have = firstRow(c, "SELECT id, expires_at FROM punishments WHERE target_uuid=? AND type=? AND status='ACTIVE' LIMIT 1", row.get("target_uuid"), type);
            if (have != null) {
                boolean incomingStricter = exp == null ? have.get("expires_at") != null : have.get("expires_at") != null && num(exp) > num(have.get("expires_at"));
                if (incomingStricter) {
                    Sql.update(c, "UPDATE punishments SET status='REVOKED', ended_at=?, ended_by='Import', end_reason=? WHERE id=?", now,
                            "Superseded by the stricter " + type.toLowerCase(Locale.ROOT) + " imported from " + label, have.get("id"));
                    r.warnings.add(row.get("target_name") + " had an active " + type + " in two sources: the stricter one (from " + label + ") stays active, #" + have.get("id") + " was recorded as superseded.");
                } else {
                    over.put("status", "REVOKED");
                    over.put("ended_at", now);
                    over.put("ended_by", "Import");
                    over.put("end_reason", "Superseded by the active " + type.toLowerCase(Locale.ROOT) + " #" + have.get("id") + " that was already in the network database");
                    r.warnings.add(row.get("target_name") + " already had an active " + type + " (#" + have.get("id") + ") that is at least as strict: the one from " + label + " was imported as superseded.");
                }
            }
        }
        return true;
    }

    private static boolean liveJail(Map<String, Object> p) {
        String st = String.valueOf(p.get("jail_state"));
        return String.valueOf(p.get("status")).equals("ACTIVE") && (st.equals("PLACING") || st.equals("HELD") || st.equals("PENDING_PLACEMENT") || st.equals("RELEASING"));
    }

    private static boolean isLiveCustody(String status) {
        return status.equals("REQUESTED") || status.equals("ACTIVE") || status.equals("RELEASING") || status.equals("SERVING");
    }

    // ------------------------------------------------------------------ duplicates
    private Long findExisting(Connection c, Spec spec, Map<String, Object> row, Map<String, Object> over, boolean stableUids) throws SQLException {
        Set<String> cols = columns(c, spec.table());
        if (stableUids && cols.contains("uid") && row.get("uid") != null) {
            Long id = Sql.one(c, "SELECT id FROM " + spec.table() + " WHERE uid=?", rs -> rs.getLong(1), row.get("uid"));
            if (id != null) return id;
        }
        if (spec.fingerprint().isEmpty()) return null;
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (String col : spec.fingerprint()) {
            if (!cols.contains(col)) continue;
            Object v = over.containsKey(col) ? over.get(col) : row.get(col);
            if (where.length() > 0) where.append(" AND ");
            if (v == null) {
                where.append(col).append(" IS NULL");
            } else {
                where.append(col).append("=?");
                args.add(v);
            }
        }
        if (where.length() == 0) return null;
        return Sql.one(c, "SELECT id FROM " + spec.table() + " WHERE " + where + " LIMIT 1", rs -> rs.getLong(1), args.toArray());
    }

    // ------------------------------------------------------------------ plumbing
    private static void mapRow(Connection c, String label, String table, long oldId, long newId, long runId) throws SQLException {
        Sql.update(c, "INSERT INTO migration_map(source,tbl,old_id,new_id,run_id) VALUES(?,?,?,?,?)", label, table, oldId, newId, runId);
    }

    private static TableResult counts(String table, Map<String, List<Map<String, Object>>> data) {
        TableResult t = new TableResult(table);
        t.read = data.getOrDefault(table, List.of()).size();
        return t;
    }

    private static final Map<String, Set<String>> COLUMN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static Set<String> columns(Connection c, String table) throws SQLException {
        String key = c.getMetaData().getURL() + "|" + table;
        Set<String> hit = COLUMN_CACHE.get(key);
        if (hit != null) return hit;
        Set<String> cols = new java.util.LinkedHashSet<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
        }
        COLUMN_CACHE.put(key, cols);
        return cols;
    }

    private static long insertRow(Connection c, String table, Map<String, Object> row, Set<String> targetCols, Map<String, Object> overrides, boolean skipId) throws SQLException {
        List<String> names = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (var e : row.entrySet()) {
            String col = e.getKey();
            if (!targetCols.contains(col) || (skipId && col.equals("id"))) continue;
            names.add(col);
            args.add(overrides.containsKey(col) ? overrides.get(col) : e.getValue());
        }
        for (var e : overrides.entrySet()) {
            if (targetCols.contains(e.getKey()) && !names.contains(e.getKey()) && !e.getKey().equals("id")) {
                names.add(e.getKey());
                args.add(e.getValue());
            }
        }
        StringBuilder q = new StringBuilder("INSERT INTO ").append(table).append("(").append(String.join(",", names)).append(") VALUES(");
        for (int i = 0; i < names.size(); i++) q.append(i == 0 ? "?" : ",?");
        q.append(")");
        if (!skipId) {
            Sql.update(c, q.toString(), args.toArray());
            return 0;
        }
        return Sql.insert(c, q.toString(), args.toArray());
    }

    private static Map<String, Object> firstRow(Connection c, String sql, Object... args) throws SQLException {
        List<Map<String, Object>> rows = Sql.query(c, sql, SqliteImport::rowOf, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> rowOf(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) m.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
        return m;
    }

    private static List<Map<String, Object>> readAll(Connection c, String table) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = c.createStatement()) {
            boolean hasId = !table.equals("profiles");
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + table + (hasId ? " ORDER BY id" : " ORDER BY first_seen"))) {
                while (rs.next()) out.add(rowOf(rs));
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("no such table")) return out; // an old version never had it
            throw e;
        }
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : o == null ? 0L : Long.parseLong(String.valueOf(o));
    }

    private static UUID uuidOrNull(Object o) {
        try {
            return o == null ? null : UUID.fromString(String.valueOf(o));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(f.toPath())) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var s = Files.list(dir)) {
            s.forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temporary files
        }
        dir.toFile().delete();
    }
}
