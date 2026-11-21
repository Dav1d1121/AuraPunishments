package dev.aura.punishments;

import dev.aura.punishments.db.Database;
import dev.aura.punishments.db.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** A real 1.0.0 database (schema v1) must migrate to v2 with every record intact and nothing falsely "new". */
class MigrationV2Test {
    @TempDir
    Path dir;

    private Path buildV1() throws Exception {
        Path file = dir.resolve("punishments.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            for (String s : Schema.MIGRATIONS.get(0).statements()) st.execute(s);
            st.execute("INSERT INTO meta(k,v) VALUES('schema_version','1')");
            st.execute("INSERT INTO cases(id,status,kind,accused_uuid,accused_name,offense_key,severity,description,created_at,updated_at,version) VALUES"
                    + "(7,'CLOSED','QUICK','00000000-0000-0000-0000-000000000001','Alex','other','MODERATE','Quick ban with evidence',1000,1000,1)");
            // delivered warning, UNdelivered warning (offline player), quick mute, quick ban (with case), judicial ban, revoked quick mute, emergency
            String cols = "(id,type,status,target_uuid,target_name,reason,issuer_uuid,issuer_name,issued_at,expires_at,case_id,source,delivered,reviewed)";
            st.execute("INSERT INTO punishments" + cols + " VALUES"
                    + "(1,'WARN','COMPLETED','00000000-0000-0000-0000-000000000001','Alex','Old delivered warning','00000000-0000-0000-0000-0000000000aa','Mod',1000,NULL,NULL,'QUICK',1,0),"
                    + "(2,'WARN','COMPLETED','00000000-0000-0000-0000-000000000001','Alex','Undelivered warning','00000000-0000-0000-0000-0000000000aa','Mod',2000,NULL,NULL,'QUICK',0,0),"
                    + "(3,'MUTE','ACTIVE','00000000-0000-0000-0000-000000000001','Alex','Quick mute in force','00000000-0000-0000-0000-0000000000aa','Mod',3000,9000000000000,NULL,'QUICK',1,0),"
                    + "(4,'BAN','ACTIVE','00000000-0000-0000-0000-000000000001','Alex','Quick ban in force','00000000-0000-0000-0000-0000000000aa','Mod',4000,NULL,7,'QUICK',1,0),"
                    + "(5,'BAN','ACTIVE','00000000-0000-0000-0000-000000000001','Alex','Judicial ban','00000000-0000-0000-0000-0000000000bb','Judge',5000,NULL,7,'JUDICIAL',1,0),"
                    + "(6,'KICK','COMPLETED','00000000-0000-0000-0000-000000000001','Alex','Kick','00000000-0000-0000-0000-0000000000aa','Mod',6000,NULL,NULL,'QUICK',1,0),"
                    + "(8,'WARN','REVOKED','00000000-0000-0000-0000-000000000001','Alex','Revoked undelivered warning','00000000-0000-0000-0000-0000000000aa','Mod',2500,NULL,NULL,'QUICK',0,0)");
            st.execute("INSERT INTO appeals(case_id,filed_by_name,filed_at,reason,status) VALUES(7,'Alex',7000,'Please review','PENDING')");
        }
        return file;
    }

    private static long one(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void migratesV1ToV2PreservingEverything() throws Exception {
        Path file = buildV1();
        Database db = new Database(file, Logger.getLogger("test"));
        db.open();
        assertEquals(Schema.latest(), db.schemaVersion());
        db.close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(7, one(c, "SELECT COUNT(*) FROM punishments"), "no punishment lost");
            assertEquals(1, one(c, "SELECT COUNT(*) FROM cases"));
            assertEquals(1, one(c, "SELECT COUNT(*) FROM appeals WHERE status='PENDING'"), "appeal status kept");
            assertEquals("Quick mute in force", str(c, "SELECT reason FROM punishments WHERE id=3"));
            // legacy quick punishments that would now need review are imported as LEGACY, never as new pending work
            assertEquals(2, one(c, "SELECT COUNT(*) FROM reviews"), "the quick mute and the quick ban");
            assertEquals(0, one(c, "SELECT COUNT(*) FROM reviews WHERE status<>'LEGACY'"), "nothing historical shows up as new pending review");
            assertEquals(2, one(c, "SELECT COUNT(*) FROM reviews WHERE migrated=1"));
            assertEquals(0, one(c, "SELECT COUNT(*) FROM reviews WHERE punishment_id IN (1,2,5,6,8)"), "warnings, kicks and judicial sentences never get a review");
            assertEquals(2, one(c, "SELECT COUNT(*) FROM review_events WHERE type='MIGRATED'"));
            // only the genuinely undelivered, non-revoked warning becomes a queued notification
            assertEquals(1, one(c, "SELECT COUNT(*) FROM player_notifications"));
            assertEquals("warn:2", str(c, "SELECT dedupe_key FROM player_notifications"));
            assertEquals(0, one(c, "SELECT COUNT(*) FROM discord_outbox"), "migration never posts historical records to Discord");
            // new columns exist and are empty
            assertEquals(0, one(c, "SELECT COUNT(*) FROM punishments WHERE modified_at IS NOT NULL"));
        }
        try (Stream<Path> s = Files.list(dir.resolve("backups"))) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().contains("pre-v2")), "a pre-migration backup was taken");
        }
    }

    @Test
    void migrationIsRepeatableAndDoesNotDuplicate() throws Exception {
        Path file = buildV1();
        Database first = new Database(file, Logger.getLogger("test"));
        first.open();
        first.close();
        Database again = new Database(file, Logger.getLogger("test"));
        again.open();
        again.close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(2, one(c, "SELECT COUNT(*) FROM reviews"));
            assertEquals(1, one(c, "SELECT COUNT(*) FROM player_notifications"));
        }
    }

    @Test
    void aNewerSchemaIsRefusedInsteadOfDamaged() throws Exception {
        Path file = buildV1();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            st.execute("UPDATE meta SET v='99' WHERE k='schema_version'");
        }
        Database db = new Database(file, Logger.getLogger("test"));
        var ex = assertThrows(java.sql.SQLException.class, db::open);
        assertTrue(ex.getMessage().contains("newer"));
        db.close();
    }

    private static String str(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
