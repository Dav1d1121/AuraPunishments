package dev.aura.punishments.v13;

import dev.aura.punishments.db.Database;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.db.Schema;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.model.Simple.Source;
import dev.aura.punishments.service.Actor;
import dev.aura.punishments.service.CaseService;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.support.Fx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A representative 1.2.x database (schema v2: judicial cases, evidence, appeals with a ticket, custody, direct/emergency/
 * judicial punishments, reviews, notifications, a hash-chained audit log) must upgrade to schema v3 with every record, id and
 * relationship intact, gain unique ids and its server name, and keep working with the services.
 */
class MigrationV3Test {
    static final Pattern UUID_FORMAT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    static final String ALEX = "00000000-0000-0000-0000-0000000000a1";
    static final String BLAKE = "00000000-0000-0000-0000-0000000000b2";
    static final String MOD = "00000000-0000-0000-0000-0000000000cc";

    @TempDir
    Path dir;

    private Path buildV2() throws Exception {
        Path file = dir.resolve("punishments.db");
        writeV2(file);
        return file;
    }

    /** A representative schema-v2 (1.2.x) database, also used by the plugin-level upgrade test. */
    static void writeV2(Path file) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            for (int i = 0; i < 2; i++) for (String s : Schema.MIGRATIONS.get(i).statements()) st.execute(s);
            st.execute("INSERT INTO meta(k,v) VALUES('schema_version','2')");
            // cases: a sentenced judicial case with an appeal ticket, a direct case, an emergency case, an open case, a hidden quick case
            st.execute("INSERT INTO cases(id,status,kind,accused_uuid,accused_name,offense_key,severity,description,prosecutor_uuid,prosecutor_name,judge_name,verdict,verdict_reason,verdict_by_name,verdict_at,created_at,updated_at,version,appeal_ticket) VALUES"
                    + "(1,'APPEALED','STANDARD','" + ALEX + "','Alex','griefing','MAJOR','Griefed the spawn builds repeatedly','" + MOD + "','Mod','Judge','GUILTY','Clear evidence','Judge',5000,1000,6000,4,'ticket-0042'),"
                    + "(2,'DIRECT_PUNISHMENT_ISSUED','DIRECT','" + BLAKE + "','Blake','hacking','SEVERE','Used a fly hack in front of staff','" + MOD + "','Mod',NULL,NULL,NULL,NULL,NULL,2000,2000,1,NULL),"
                    + "(3,'UNDER_INVESTIGATION','EMERGENCY','" + BLAKE + "','Blake','other','SEVERE','EMERGENCY: raiding voice chat','" + MOD + "','Mod',NULL,NULL,NULL,NULL,NULL,3000,3000,0,NULL),"
                    + "(4,'OPEN','STANDARD','" + ALEX + "','Alex','spam','MINOR','Spamming the chat repeatedly again','" + MOD + "','Mod',NULL,NULL,NULL,NULL,NULL,4000,4000,0,NULL),"
                    + "(5,'CLOSED','QUICK','" + ALEX + "','Alex','other','MODERATE','Quick ban with evidence','" + MOD + "','Mod',NULL,NULL,NULL,NULL,NULL,500,500,1,NULL)");
            st.execute("INSERT INTO case_events(case_id,ts,actor_name,type,detail) VALUES (1,1000,'Mod','CREATED','x'),(1,5000,'Judge','VERDICT','y')");
            // evidence incl. a voided + corrected pair
            st.execute("INSERT INTO evidence(id,case_id,type,reference,description,submitted_by,submitted_name,submitted_at,voided,void_reason,voided_name,voided_at,corrects_id) VALUES"
                    + "(1,1,'SCREENSHOT','https://example.com/a.png','before',NULL,'Mod',1100,1,'wrong image','Judge',1200,NULL),"
                    + "(2,1,'SCREENSHOT','https://example.com/b.png','after',NULL,'Mod',1300,0,NULL,NULL,NULL,1),"
                    + "(3,2,'CHAT_LOG','chat excerpt',NULL,NULL,'Mod',2100,0,NULL,NULL,NULL,NULL),"
                    + "(4,5,'EXTERNAL_LINK','https://example.com/q.png',NULL,NULL,'Mod',510,0,NULL,NULL,NULL,NULL)");
            st.execute("INSERT INTO notes(id,case_id,profile_uuid,author_name,text,created_at,sensitive) VALUES (1,1,NULL,'Mod','case note',1500,0),(2,NULL,'" + ALEX + "','Mod','profile note',1600,1)");
            // custody: a pretrial detainee and a final jail sentence
            st.execute("INSERT INTO custody(id,case_id,uuid,name,status,requested_name,requested_at,started_at,deadline,jail_name,jail_prisoner_id,updated_at) VALUES"
                    + "(1,4,'" + ALEX + "','Alex','ACTIVE','Mod',4100,4200,9999999999999,'main','jid-1',4200),"
                    + "(2,1,'" + BLAKE + "','Blake','SERVING','Judge',5100,5200,NULL,'main','jid-2',5200)");
            String cols = "(id,type,status,target_uuid,target_name,reason,issuer_uuid,issuer_name,issued_at,expires_at,case_id,source,delivered,reviewed,custody_id,review_deadline)";
            st.execute("INSERT INTO punishments" + cols + " VALUES"
                    + "(1,'BAN','ACTIVE','" + ALEX + "','Alex','Judicial ban','" + MOD + "','Judge',5000,NULL,1,'JUDICIAL',1,0,NULL,NULL),"
                    + "(2,'JAIL','ACTIVE','" + BLAKE + "','Blake','Judicial jail sentence','" + MOD + "','Judge',5200,9999999999999,1,'JUDICIAL',1,0,2,NULL),"
                    + "(3,'BAN','ACTIVE','" + BLAKE + "','Blake','Direct ban','" + MOD + "','Mod',2000,NULL,2,'DIRECT',1,0,NULL,NULL),"
                    + "(4,'MUTE','ACTIVE','" + BLAKE + "','Blake','Temporary protective action','" + MOD + "','Mod',3000,9999999999999,3,'EMERGENCY',1,0,NULL,99999),"
                    + "(5,'MUTE','ACTIVE','" + ALEX + "','Alex','Quick mute in force','" + MOD + "','Mod',3500,9999999999999,NULL,'QUICK',1,0,NULL,NULL),"
                    + "(6,'WARN','COMPLETED','" + ALEX + "','Alex','Old warning','" + MOD + "','Mod',100,NULL,NULL,'QUICK',1,0,NULL,NULL)");
            st.execute("INSERT INTO appeals(id,case_id,filed_by_name,filed_at,reason,status,assigned_name) VALUES (1,1,'Support',5500,'Says it was a mistake','UNDER_REVIEW','Reviewer'),"
                    + "(2,2,'Support',2500,'Earlier appeal','UPHELD',NULL)");
            st.execute("UPDATE appeals SET decided_by_name='Reviewer', decision_reason='Stands', decided_at=2600 WHERE id=2");
            // audit chain computed exactly as the plugin does
            String prev = "GENESIS";
            String[][] rows = {{"1000", "case.create", "1", "created"}, {"5000", "judicial.verdict", "1", "guilty"}, {"6000", "appeal.file", "1", "says mistake"}};
            for (String[] r : rows) {
                String hash = digest(prev, Long.parseLong(r[0]), null, "Mod", r[1], null, Long.valueOf(r[2]), r[3], false);
                st.execute("INSERT INTO audit_log(ts,actor_name,action,case_id,detail,elevated,prev_hash,hash) VALUES (" + r[0] + ",'Mod','" + r[1] + "'," + r[2] + ",'" + r[3] + "',0,'" + prev + "','" + hash + "')");
                prev = hash;
            }
            st.execute("INSERT INTO pending_ops(kind,state,case_id,custody_id,uuid,created_at,updated_at) VALUES ('JAIL_START','DONE',4,1,'" + ALEX + "',4100,4200)");
            st.execute("INSERT INTO profiles(uuid,name,name_lower,first_seen,last_seen,last_ip,level) VALUES ('" + ALEX + "','Alex','alex',1,2,'10.0.0.5',0)");
        }
    }

    static String digest(String prev, long ts, Object actor, String actorName, String action, Object target, Long caseId, String detail, boolean elevated) throws Exception {
        String joined = prev + "|" + ts + "|" + actor + "|" + actorName + "|" + action + "|" + target + "|" + caseId + "|" + detail + "|" + elevated;
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8)));
    }

    static long one(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String str(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static List<String> col(Connection c, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    @Test
    void everyRecordIdAndRelationshipSurvivesTheUpgrade() throws Exception {
        Path file = buildV2();
        Database db = new Database(file, Logger.getLogger("test"), "smp");
        db.open();
        assertEquals(dev.aura.punishments.db.Schema.latest(), db.schemaVersion());
        assertEquals("smp", db.origin());
        db.close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(5, one(c, "SELECT COUNT(*) FROM cases"));
            assertEquals(6, one(c, "SELECT COUNT(*) FROM punishments"));
            assertEquals(4, one(c, "SELECT COUNT(*) FROM evidence"), "no evidence lost by the table rebuild");
            assertEquals(2, one(c, "SELECT COUNT(*) FROM appeals"));
            assertEquals(2, one(c, "SELECT COUNT(*) FROM custody"));
            assertEquals(2, one(c, "SELECT COUNT(*) FROM notes"));
            assertEquals(3, one(c, "SELECT COUNT(*) FROM audit_log"));
            // evidence: ids, owners, voiding and correction links are exactly as before
            assertEquals(1, one(c, "SELECT case_id FROM evidence WHERE id=1"));
            assertEquals(1, one(c, "SELECT voided FROM evidence WHERE id=1"));
            assertEquals("wrong image", str(c, "SELECT void_reason FROM evidence WHERE id=1"));
            assertEquals(1, one(c, "SELECT corrects_id FROM evidence WHERE id=2"));
            assertEquals(5, one(c, "SELECT case_id FROM evidence WHERE id=4"));
            assertEquals(0, one(c, "SELECT COUNT(*) FROM evidence WHERE punishment_id IS NOT NULL"), "existing evidence stays on its case");
            // appeals: status, reviewer, decision and (new) the case's Ticket Tool reference
            assertEquals("UNDER_REVIEW", str(c, "SELECT status FROM appeals WHERE id=1"));
            assertEquals("Reviewer", str(c, "SELECT assigned_name FROM appeals WHERE id=1"));
            assertEquals("ticket-0042", str(c, "SELECT ticket FROM appeals WHERE id=1"), "the case's ticket is now on the appeal itself");
            assertEquals("Stands", str(c, "SELECT decision_reason FROM appeals WHERE id=2"));
            assertEquals("ticket-0042", str(c, "SELECT appeal_ticket FROM cases WHERE id=1"), "the case keeps its own reference too");
            // punishments, custody, relationships
            assertEquals("Judicial jail sentence", str(c, "SELECT reason FROM punishments WHERE id=2"));
            assertEquals(2, one(c, "SELECT custody_id FROM punishments WHERE id=2"));
            assertEquals("SERVING", str(c, "SELECT status FROM custody WHERE id=2"));
            assertEquals("JUDICIAL", str(c, "SELECT source FROM punishments WHERE id=1"));
            assertEquals(99999, one(c, "SELECT review_deadline FROM punishments WHERE id=4"));
            assertEquals(1, one(c, "SELECT COUNT(*) FROM pending_ops"));
            assertEquals("main", str(c, "SELECT jail_name FROM custody WHERE id=1"));
            // the migration created no review for pre-existing quick punishments beyond what v2 itself did, and posted nothing
            assertEquals(0, one(c, "SELECT COUNT(*) FROM discord_outbox"));
            assertEquals(0, one(c, "SELECT COUNT(*) FROM punishments WHERE jail_state IS NOT NULL"), "old jail sentences stay case-backed; none is mistaken for a standalone jail");
        }
        try (Stream<Path> s = Files.list(dir.resolve("backups"))) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().contains("pre-v3")), "a pre-migration backup was taken");
        }
    }

    @Test
    void everyExistingRecordGetsAUniqueIdAndTheServerName() throws Exception {
        Path file = buildV2();
        Database db = new Database(file, Logger.getLogger("test"), "smp");
        db.open();
        db.close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            Set<String> all = new HashSet<>();
            int total = 0;
            for (String t : List.of("punishments", "cases", "evidence", "notes", "appeals", "reviews", "custody")) {
                for (String uid : col(c, "SELECT uid FROM " + t)) {
                    assertNotNull(uid, t + " row without a uid");
                    assertTrue(UUID_FORMAT.matcher(uid).matches(), t + " uid is not a version-4 UUID: " + uid);
                    all.add(uid);
                    total++;
                }
                assertEquals(0, one(c, "SELECT COUNT(*) FROM " + t + " WHERE origin IS NULL OR origin<>'smp'"), t + " rows carry the originating server");
            }
            assertEquals(total, all.size(), "ids are unique across every table");
            assertTrue(total >= 20);
            assertEquals(0, one(c, "SELECT COUNT(*) FROM audit_log WHERE origin<>'smp' OR origin IS NULL"));
        }
    }

    @Test
    void theAuditChainStillVerifiesAfterTheUpgrade() throws Exception {
        Path file = buildV2();
        Database db = new Database(file, Logger.getLogger("test"), "smp");
        db.open();
        assertEquals(-1L, (long) db.<Long>txSync(c -> Repo.Audits.verify(c)), "adding columns must not break the tamper-evident chain");
        db.close();
    }

    @Test
    void newRecordsAreIdentifiedAutomaticallyByTheDatabase() throws Exception {
        Path file = buildV2();
        Database db = new Database(file, Logger.getLogger("test"), "smp");
        db.open();
        long id = db.<Long>txSync(c -> Repo.Punishments.insert(c, PunishType.MUTE, PunishStatus.ACTIVE, java.util.UUID.randomUUID(), "New", null, "Fresh mute after upgrade",
                null, "Mod", 10_000L, null, null, Source.QUICK, null, true, null, null, "op-1"));
        var p = db.<dev.aura.punishments.model.Records.Punishment>txSync(c -> Repo.Punishments.get(c, id));
        assertTrue(UUID_FORMAT.matcher(p.uid()).matches());
        assertEquals("smp", p.origin());
        assertEquals("op-1", p.opId());
        db.close();
    }

    @Test
    void evidenceAndAppealsCanNowAttachToAPunishmentButNeverToNothing() throws Exception {
        Path file = buildV2();
        Database db = new Database(file, Logger.getLogger("test"), "smp");
        db.open();
        long ev = db.<Long>txSync(c -> Repo.Evidences.addToPunishment(c, 5, dev.aura.punishments.model.Simple.EvidenceType.EXTERNAL_LINK, "https://example.com/new.png", null, null, "Mod", 20_000L, null));
        assertEquals(5L, (long) db.<Long>txSync(c -> Repo.Evidences.get(c, ev).punishmentId()));
        assertNull(db.<Long>txSync(c -> Repo.Evidences.get(c, ev).caseId()));
        assertThrows(java.sql.SQLException.class, () -> db.txSync(c -> dev.aura.punishments.db.Sql.insert(c,
                "INSERT INTO evidence(type,reference,submitted_name,submitted_at) VALUES('OTHER','x','Mod',1)")), "evidence must belong to a case or a punishment");
        long ap = db.<Long>txSync(c -> Repo.Appeals.addForPunishment(c, 5, "Support", "Please look again", "0050", 21_000L));
        assertEquals("0050", db.<dev.aura.punishments.model.Records.Appeal>txSync(c -> Repo.Appeals.get(c, ap)).ticket());
        db.close();
    }

    @Test
    void reopeningDoesNotMigrateAgainAndKeepsEveryId() throws Exception {
        Path file = buildV2();
        Database first = new Database(file, Logger.getLogger("test"), "smp");
        first.open();
        String uidBefore = first.<String>txSync(c -> Repo.Punishments.get(c, 1).uid());
        first.close();
        Database again = new Database(file, Logger.getLogger("test"), "smp");
        again.open();
        assertEquals(uidBefore, again.<String>txSync(c -> Repo.Punishments.get(c, 1).uid()), "ids never change");
        assertEquals(dev.aura.punishments.db.Schema.latest(), again.schemaVersion());
        again.close();
        try (Stream<Path> s = Files.list(dir.resolve("backups"))) {
            assertEquals(1, s.filter(p -> p.getFileName().toString().contains("pre-v3")).count(), "no second backup when nothing was migrated");
        }
    }

    @Test
    void aBlankServerNameGeneratesAndRemembersOne() throws Exception {
        Path file = buildV2();
        Database first = new Database(file, Logger.getLogger("test"), "");
        first.open();
        String name = first.origin();
        assertTrue(name.matches("srv-[0-9a-f]{8}"), name);
        first.close();
        Database again = new Database(file, Logger.getLogger("test"), null);
        again.open();
        assertEquals(name, again.origin(), "the generated name is remembered across restarts");
        again.close();
    }

    @Test
    void theUpgradedDatabaseWorksWithTheServices() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Fx.BACKEND.equals("sqlite"), "this upgrades a 1.x SQLite FILE; the shared database has its own migration (see the network tests)");
        Path file = buildV2();
        Fx.Mutator m = new Fx.Mutator();
        m.set.put("judicial.enabled", true);
        m.serverName = "smp";
        try (Fx fx = new Fx(file, m)) {
            Actor owner = Fx.owner();
            var detail = V13Support.r(fx.cases.detail(owner, 1)).orThrow();
            assertEquals(2, detail.evidence().size(), "case evidence incl. the voided item is still readable");
            assertEquals(1, detail.appeals().size());
            assertEquals("ticket-0042", detail.appeals().get(0).ticket());
            assertEquals("ticket-0042", detail.c().appealTicket());
            assertTrue(fx.punishments.cache().size() > 0, "active punishments were loaded into the enforcement cache");
            assertTrue(fx.punishments.banFor(java.util.UUID.fromString(ALEX), null).isPresent());
            // judicial work continues: the open appeal can still be decided through the case
            var decided = V13Support.r(fx.judicial.decideAppeal(owner, 1, dev.aura.punishments.model.Simple.AppealStatus.UPHELD, "The verdict stands after review."));
            assertTrue(decided.isOk(), decided.message());
            // and the new open-work report sees what is open (case 3 emergency, case 4 open, the detainee)
            fx.core.mode.refresh().get();
            assertEquals(1, fx.core.mode.report().pretrialDetainees());
            assertTrue(fx.core.mode.report().openCases() >= 2);
        }
    }

    @Test
    void aVersionOneDatabaseUpgradesAllTheWayToVersionThree() throws Exception {
        Path file = dir.resolve("v1.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            for (String s : Schema.MIGRATIONS.get(0).statements()) st.execute(s);
            st.execute("INSERT INTO meta(k,v) VALUES('schema_version','1')");
            st.execute("INSERT INTO punishments(id,type,status,target_uuid,target_name,reason,issuer_name,issued_at,source,delivered) VALUES (1,'BAN','ACTIVE','" + ALEX + "','Alex','Old ban','Mod',1000,'QUICK',1)");
            st.execute("INSERT INTO cases(id,status,kind,accused_uuid,accused_name,offense_key,severity,description,created_at,updated_at,version) VALUES (1,'OPEN','STANDARD','" + ALEX + "','Alex','other','MINOR','An old open case from 1.0',1,1,0)");
            st.execute("INSERT INTO appeals(case_id,filed_by_name,filed_at,reason,status) VALUES (1,'Alex',7000,'Please review','PENDING')");
        }
        Database db = new Database(file, Logger.getLogger("test"), "lobby");
        db.open();
        assertEquals(dev.aura.punishments.db.Schema.latest(), db.schemaVersion());
        assertEquals("lobby", db.<String>txSync(c -> Repo.Punishments.get(c, 1).origin()));
        assertEquals(1L, (long) db.<Long>txSync(c -> Repo.Appeals.countPending(c)));
        db.close();
    }
}
