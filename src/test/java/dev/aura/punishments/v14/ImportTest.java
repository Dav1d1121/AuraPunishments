package dev.aura.punishments.v14;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.Schema;
import dev.aura.punishments.db.Sql;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.CaseService;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService;
import dev.aura.punishments.service.SqliteImport;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Durations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Importing the old per-server SQLite databases into the shared database: dry run, remapped references, repeats, no reactivation, untouched originals. */
class ImportTest {
    @TempDir Path dir;
    Fx target;
    SqliteImport importer;
    Actor mod = NetworkTest.staff("Mod");

    @BeforeEach
    void start() throws Exception {
        Assumptions.assumeTrue(NetworkTest.reachable(), "no MariaDB server: the import tests are skipped");
        Fx.Mutator m = new Fx.Mutator();
        m.shared = true;
        m.serverName = "network";
        target = new Fx(null, m);
        Files.createDirectories(dir.resolve("migration"));
        importer = new SqliteImport(target.core, Logger.getLogger("test"), dir.toFile());
    }

    @AfterEach
    void stop() {
        if (target != null) target.close();
    }

    // ------------------------------------------------------------------ fixtures: an old standalone server
    interface Populate {
        void on(Fx old) throws Exception;
    }

    File oldServer(String file, String name, Populate p) throws Exception {
        return oldServer(file, name, false, p);
    }

    /** @param casework the old server ran the case workflow (cases, no court) */
    File oldServer(String file, String name, boolean casework, Populate p) throws Exception {
        File f = dir.resolve("migration").resolve(file).toFile();
        Fx.Mutator m = new Fx.Mutator();
        m.forceSqlite = true;
        m.serverName = name;
        if (casework) {
            m.set.put("judicial.enabled", false);
            m.set.put("casework.enabled", true);
        }
        try (Fx old = new Fx(f.toPath(), m)) {
            p.on(old);
        }
        return f;
    }

    static Punishment issue(Fx on, Actor by, Target t, PunishType type, long dur, String reason) throws Exception {
        return on.punishments.issueQuick(by, new PunishmentService.Request(t, type, dur, reason, type == PunishType.BAN ? List.of(Fx.ev()) : List.of())).get().orThrow();
    }

    SqliteImport.Report check(String file, String label) throws Exception {
        return importer.run(file, new SqliteImport.Options(true, label, false), "Admin").get();
    }

    SqliteImport.Report run(String file, String label) throws Exception {
        return importer.run(file, new SqliteImport.Options(false, label, false), "Admin").get();
    }

    long count(String table) throws Exception {
        return target.db.txSync(c -> Sql.count(c, "SELECT COUNT(*) FROM " + table));
    }

    List<Punishment> punishmentsOf(Target t) throws Exception {
        var all = new java.util.ArrayList<>(target.db.txSync(c -> Repo.Punishments.forTarget(c, t.uuid(), 100, 0)));
        all.sort(java.util.Comparator.comparingLong(Punishment::id));
        return all;
    }

    static String sha(File f) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(md.digest(Files.readAllBytes(f.toPath())));
    }

    // ------------------------------------------------------------------ tests
    @Test
    void aDryRunReportsEverythingAndWritesNothing() throws Exception {
        Target steve = Fx.target("Steve");
        oldServer("survival.db", "survival", old -> {
            issue(old, mod, steve, PunishType.BAN, Durations.DAY, "Repeated griefing of builds.");
            issue(old, mod, Fx.target("Alex"), PunishType.MUTE, Durations.HOUR, "Spamming the global channel.");
        });
        var rep = check("survival.db", "survival");
        assertTrue(rep.ok(), rep.lines().toString());
        assertTrue(rep.dryRun && !rep.applied);
        assertEquals(2, rep.tables.stream().filter(t -> t.table.equals("punishments")).findFirst().orElseThrow().imported);
        assertEquals(0, count("punishments"), "nothing was written");
        assertEquals(0, count("migration_runs"));
        assertEquals(0, count("migration_map"));
        assertTrue(rep.lines().stream().anyMatch(l -> l.contains("Nothing was written (dry run)")), rep.lines().toString());
        assertTrue(Arrays.stream(dir.resolve("migration").toFile().list()).anyMatch(n -> n.startsWith("report-survival-check-")), "the report is kept");
    }

    @Test
    void theRealRunRemapsIdsAndKeepsEveryReference() throws Exception {
        Target steve = Fx.target("Steve");
        // the shared database already has data, so the old ids (1, 2, ...) cannot simply be reused
        issue(target, mod, Fx.target("Existing"), PunishType.WARN, 0, "Earlier network warning.");
        oldServer("survival.db", "survival", true, old -> old.punishments.openCaseAndPunish(Fx.owner(), new CaseService.CreateRequest(steve, "harassment",
                dev.aura.punishments.model.Simple.Severity.MAJOR, "Sustained harassment of other players.", null, List.of(Fx.ev("https://example.com/a.png"))),
                new PunishmentService.Request(steve, PunishType.BAN, Durations.DAY, "Sustained harassment of other players.", List.of())).get().orThrow());
        var rep = run("survival.db", "survival");
        assertTrue(rep.ok() && rep.applied, rep.lines().toString());
        List<Punishment> ps = punishmentsOf(steve);
        assertEquals(1, ps.size());
        Punishment p = ps.get(0);
        assertNotEquals(1L, p.id(), "a new id in the shared database");
        assertNotNull(p.caseId());
        var cs = target.db.txSync(c -> Repo.Cases.get(c, p.caseId()));
        assertEquals(steve.uuid(), cs.accusedUuid(), "the punishment still points at ITS case");
        assertEquals("survival", p.origin(), "and says where it came from");
        long evidence = target.db.txSync(c -> Sql.count(c, "SELECT COUNT(*) FROM evidence WHERE case_id=?", cs.id()));
        assertEquals(1, evidence, "the case's evidence followed it");
        assertEquals(1, count("migration_runs"));
        assertTrue(count("migration_map") >= 3);
        assertTrue(target.db.txSync(c -> Repo.Audits.verify(c)) == -1, "the shared audit chain is intact after the import");
        assertTrue(target.db.txSync(c -> Sql.count(c, "SELECT COUNT(*) FROM audit_log WHERE detail LIKE '%[imported from survival #%'")) > 0);
    }

    @Test
    void repeatingAnImportAddsNothing() throws Exception {
        Target steve = Fx.target("Steve");
        File f = oldServer("survival.db", "survival", old -> issue(old, mod, steve, PunishType.BAN, Durations.DAY, "Repeated griefing of builds."));
        assertTrue(run("survival.db", "survival").applied);
        long before = count("punishments");
        long audit = count("audit_log");
        var again = run("survival.db", "survival");
        assertTrue(again.alreadyImported, again.lines().toString());
        assertEquals(before, count("punishments"));
        assertEquals(audit, count("audit_log"));
        // the same bytes under another name and another label are recognised too
        Files.copy(f.toPath(), dir.resolve("migration").resolve("copy-of-survival.db"));
        var copy = run("copy-of-survival.db", "other");
        assertTrue(copy.alreadyImported, copy.lines().toString());
        assertEquals(before, count("punishments"));
    }

    @Test
    void aNewerCopyOfTheSameServerOnlyAddsWhatIsNew() throws Exception {
        Target steve = Fx.target("Steve"), alex = Fx.target("Alex");
        File f = oldServer("survival.db", "survival", old -> issue(old, mod, steve, PunishType.BAN, Durations.DAY, "Repeated griefing of builds."));
        assertTrue(run("survival.db", "survival").applied);
        // the old server ran a while longer: same file, one more punishment
        Fx.Mutator m = new Fx.Mutator();
        m.forceSqlite = true;
        m.serverName = "survival";
        try (Fx old = new Fx(f.toPath(), m)) {
            issue(old, mod, alex, PunishType.MUTE, Durations.HOUR, "Spamming the global channel.");
        }
        var rep = run("survival.db", "survival");
        assertTrue(rep.applied, rep.lines().toString());
        assertEquals(1, punishmentsOf(steve).size(), "the ban was not imported twice");
        assertEquals(1, punishmentsOf(alex).size(), "the new mute was");
    }

    @Test
    void theSameRowsUnderAnotherLabelAreRecognisedByTheirContent() throws Exception {
        Target steve = Fx.target("Steve");
        oldServer("a.db", "survival", old -> issue(old, mod, steve, PunishType.BAN, Durations.DAY, "Repeated griefing of builds."));
        assertTrue(run("a.db", "survival").applied);
        // a different file (different bytes) that carries the same stable record: the old server's copy taken a minute later
        Files.copy(dir.resolve("migration").resolve("a.db"), dir.resolve("migration").resolve("b.db"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("migration").resolve("b.db")); Statement st = c.createStatement()) {
            st.execute("INSERT INTO profiles(uuid,name,name_lower,first_seen,last_seen,level) VALUES('" + Fx.target("New").uuid() + "','New','new',1,2,0)");
        }
        var rep = run("b.db", "survival-copy");
        assertTrue(rep.applied, rep.lines().toString());
        assertEquals(1, punishmentsOf(steve).size(), "the same stable id (or the same player/type/time/issuer) is never stored twice");
    }

    @Test
    void nothingThatEndedComesBackToLife() throws Exception {
        Target revoked = Fx.target("Revoked"), expiredOnPaper = Fx.target("Expired"), served = Fx.target("Served");
        File f = oldServer("survival.db", "survival", old -> {
            Punishment p = issue(old, mod, revoked, PunishType.BAN, Durations.DAY, "Repeated griefing of builds.");
            old.punishments.revoke(mod, p.id(), "Banned the wrong player.").get().orThrow();
            issue(old, mod, expiredOnPaper, PunishType.BAN, Durations.HOUR, "Repeated griefing of builds.");
            issue(old, mod, served, PunishType.MUTE, Durations.HOUR, "Spamming the global channel.");
            old.now += 2 * Durations.HOUR;
            old.punishments.expireDue().get(); // the mute is swept as expired; the ban was never swept because the old server stopped first
        });
        // the old server stopped before sweeping the ban: its row is still ACTIVE in the file although its time ran out
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getPath()); Statement st = c.createStatement()) {
            st.execute("UPDATE punishments SET status='ACTIVE', ended_at=NULL, ended_by=NULL, end_reason=NULL WHERE target_name='Expired'");
        }
        target.now += 3 * Durations.HOUR;
        var rep = run("survival.db", "survival");
        assertTrue(rep.applied, rep.lines().toString());
        assertEquals(PunishStatus.REVOKED, punishmentsOf(revoked).get(0).status());
        assertEquals(PunishStatus.EXPIRED, punishmentsOf(expiredOnPaper).get(0).status(), "marked active, but its time had run out");
        assertEquals(PunishStatus.EXPIRED, punishmentsOf(served).get(0).status());
        assertTrue(target.db.txSync(c -> Repo.Punishments.active(c)).isEmpty(), "none of them is enforceable");
        assertTrue(rep.warnings.stream().anyMatch(w -> w.contains("time had run out")), rep.warnings.toString());
    }

    @Test
    void twoServersWithAnActiveBanForTheSamePlayerKeepTheStricterOne() throws Exception {
        Target steve = Fx.target("Steve");
        oldServer("a.db", "survival", old -> issue(old, mod, steve, PunishType.BAN, 2 * Durations.DAY, "Repeated griefing of builds."));
        oldServer("b.db", "towny", old -> issue(old, mod, steve, PunishType.BAN, 10 * Durations.DAY, "Repeated griefing of builds."));
        assertTrue(run("a.db", "survival").applied);
        var rep = run("b.db", "towny");
        assertTrue(rep.applied, rep.lines().toString());
        List<Punishment> ps = punishmentsOf(steve);
        assertEquals(2, ps.size(), "both are kept as history");
        assertEquals(1, ps.stream().filter(p -> p.status() == PunishStatus.ACTIVE).count(), "but only one can be in force");
        Punishment active = ps.stream().filter(p -> p.status() == PunishStatus.ACTIVE).findFirst().orElseThrow();
        assertEquals("towny", active.origin(), "the longer one");
        assertTrue(ps.stream().filter(p -> p.status() != PunishStatus.ACTIVE).allMatch(p -> p.endReason().contains("Superseded")), ps.toString());
        assertTrue(rep.warnings.stream().anyMatch(w -> w.contains("two sources")), rep.warnings.toString());
    }

    @Test
    void theOriginalFileIsNeverTouched() throws Exception {
        File f = oldServer("survival.db", "survival", old -> issue(old, mod, Fx.target("Steve"), PunishType.BAN, Durations.DAY, "Repeated griefing of builds."));
        String before = sha(f);
        String[] listing = dir.resolve("migration").toFile().list();
        Arrays.sort(listing);
        assertTrue(run("survival.db", "survival").applied);
        check("survival.db", "survival");
        assertEquals(before, sha(f), "not a byte of it changed (the copy was upgraded, not the file)");
        String[] after = Arrays.stream(dir.resolve("migration").toFile().list()).filter(n -> !n.startsWith("report-")).sorted().toArray(String[]::new);
        assertArrayEquals(listing, after, "no journal, backup or temp file was left next to it");
    }

    @Test
    void anOldSchemaFileIsUpgradedOnACopyAndImported() throws Exception {
        File f = dir.resolve("migration").resolve("old.db").toFile();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getPath()); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            var ctx = new Schema.Context(dev.aura.punishments.db.Dialect.SQLITE, "old", Logger.getLogger("test"));
            for (Schema.Migration m : Schema.MIGRATIONS) {
                if (m.version() > 2) break;
                for (Schema.Step step : m.steps()) step.run(c, ctx);
            }
            st.execute("INSERT INTO meta(k,v) VALUES('schema_version','2')");
            String steve = Fx.target("Steve").uuid().toString();
            st.execute("INSERT INTO profiles(uuid,name,name_lower,first_seen,last_seen,level) VALUES('" + steve + "','Steve','steve',1000,2000,0)");
            st.execute("INSERT INTO cases(id,status,kind,accused_uuid,accused_name,offense_key,severity,description,created_at,updated_at) VALUES(7,'CLOSED','STANDARD','" + steve
                    + "','Steve','griefing','MODERATE','Griefed the spawn.',1000,1000)");
            st.execute("INSERT INTO punishments(id,type,status,target_uuid,target_name,reason,issuer_name,issued_at,expires_at,case_id,source) VALUES(41,'BAN','REVOKED','" + steve
                    + "','Steve','Griefed the spawn.','OldMod',1000,99999999999999,7,'STANDARD')");
            st.execute("INSERT INTO evidence(case_id,type,reference,submitted_name,submitted_at) VALUES(7,'EXTERNAL_LINK','https://example.com/x.png','OldMod',1001)");
            st.execute("INSERT INTO audit_log(ts,actor_name,action,detail,prev_hash,hash) VALUES(1000,'OldMod','case.create','case=7','','abc')");
        }
        String before = sha(f);
        var rep = run("old.db", "oldsurvival");
        assertTrue(rep.ok() && rep.applied, rep.lines().toString());
        assertEquals(2, rep.sourceSchema);
        assertFalse(rep.stableUids, "ids invented while upgrading are not trusted for duplicate detection");
        assertEquals(before, sha(f));
        Punishment p = punishmentsOf(Fx.target("Steve")).get(0);
        assertEquals(PunishStatus.REVOKED, p.status());
        assertNotNull(p.caseId());
        assertEquals("Griefed the spawn.", target.db.txSync(c -> Repo.Cases.get(c, p.caseId())).description());
        // running the (changed-bytes) file again is recognised by old id + label, not by uid
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getPath()); Statement st = c.createStatement()) {
            st.execute("UPDATE profiles SET last_seen=3000");
        }
        var again = run("old.db", "oldsurvival");
        assertTrue(again.applied, again.lines().toString());
        assertEquals(1, punishmentsOf(Fx.target("Steve")).size());
        assertEquals(1L, (long) target.db.txSync(c -> Sql.count(c, "SELECT COUNT(*) FROM cases WHERE accused_uuid=?", Fx.target("Steve").uuid())));
    }

    @Test
    void unfinishedBusinessOnTheOldServerBlocksTheImport() throws Exception {
        Actor jailer = Fx.staff("JMod", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.punish(PunishType.JAIL), Perms.limit(PunishType.JAIL, "extended"));
        oldServer("jailed.db", "survival", true, old -> issue(old, jailer, Fx.target("Prisoner"), PunishType.JAIL, Durations.HOUR, "Griefing of the spawn area."));
        var rep = run("jailed.db", "survival");
        assertFalse(rep.ok(), "a prisoner is still being held on the old server");
        assertTrue(rep.errors.stream().anyMatch(e -> e.contains("still being served")), rep.errors.toString());
        assertFalse(rep.applied);
        assertEquals(0, count("punishments"), "nothing written");
        assertEquals(0, count("migration_runs"));
        var converted = importer.run("jailed.db", new SqliteImport.Options(false, "survival", true), "Admin").get();
        assertTrue(converted.errors.stream().anyMatch(e -> e.contains("cannot be converted")), "no jail backend configured here: " + converted.errors);
    }

    @Test
    void aJailStillBeingServedCanBeConvertedIntoAQueuedNetworkJail() throws Exception {
        Actor jailer = Fx.staff("JMod", 20, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.punish(PunishType.JAIL), Perms.limit(PunishType.JAIL, "extended"));
        Target prisoner = Fx.target("Prisoner");
        oldServer("jailed.db", "old", true, old -> issue(old, jailer, prisoner, PunishType.JAIL, Durations.HOUR, "Griefing of the spawn area."));
        Fx.Mutator m = new Fx.Mutator();
        m.shared = true;
        m.serverName = "survival";
        m.joinNetwork = true;
        m.jailBackend = "jail";
        m.sharedDatabase = target.sharedDatabaseName();
        m.set.put("judicial.enabled", false);
        m.set.put("casework.enabled", true);
        try (Fx net = new Fx(null, m); Fx jailServer = NetworkJailTest.server("jail", target.sharedDatabaseName(), false)) {
            net.now = target.now;
            jailServer.now = target.now;
            var imp = new SqliteImport(net.core, Logger.getLogger("test"), dir.toFile());
            var rep = imp.run("jailed.db", new SqliteImport.Options(false, "old", true), "Admin").get();
            assertTrue(rep.applied, rep.lines().toString());
            Punishment p = punishmentsOf(prisoner).get(0);
            assertEquals(PunishStatus.ACTIVE, p.status());
            assertEquals("PENDING_PLACEMENT", p.jailState());
            assertTrue(dev.aura.punishments.integration.RemoteJailPort.placeholder(p.jailPrisonerId()), p.jailPrisonerId());
            jailServer.network.heartbeat(List.of()).get();
            jailServer.jails.networkWork().get();
            assertTrue(jailServer.jail.find(prisoner.uuid()).isPresent(), "the jail backend carried it out");
            assertEquals("HELD", target.db.txSync(c -> Repo.Punishments.get(c, p.id())).jailState());
            assertTrue(rep.notes.stream().anyMatch(n -> n.contains("release the old JailReloaded record by hand")), rep.notes.toString());
        }
    }

    @Test
    void aFileThatIsNotADatabaseOrIsNotThereIsRefusedPolitely() throws Exception {
        Files.writeString(dir.resolve("migration").resolve("junk.db"), "this is not sqlite");
        var rep = check("junk.db", "junk");
        assertFalse(rep.ok());
        assertEquals(0, count("punishments"));
        assertFalse(check("missing.db", "missing").ok());
        assertFalse(check("../etc/passwd.db", "x").ok(), "no path tricks");
        assertFalse(check("survival.db", "bad label!").ok());
    }

    @Test
    void anImportNeedsASharedDatabase() throws Exception {
        try (Fx sqlite = new Fx(null, forceSqlite())) {
            var imp = new SqliteImport(sqlite.core, Logger.getLogger("test"), dir.toFile());
            var rep = imp.run("x.db", new SqliteImport.Options(true, "x", false), "Admin").get();
            assertFalse(rep.ok());
            assertTrue(rep.errors.get(0).contains("shared database"), rep.errors.toString());
        }
    }

    private static Fx.Mutator forceSqlite() {
        Fx.Mutator m = new Fx.Mutator();
        m.forceSqlite = true;
        return m;
    }
}
