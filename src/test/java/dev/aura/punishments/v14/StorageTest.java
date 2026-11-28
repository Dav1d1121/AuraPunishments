package dev.aura.punishments.v14;

import dev.aura.punishments.config.ConfigException;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Database;
import dev.aura.punishments.db.Dialect;
import dev.aura.punishments.db.Schema;
import dev.aura.punishments.db.Sql;
import dev.aura.punishments.db.StorageConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** 1.4.0 storage: the MariaDB/MySQL dialect, the configuration, and (when a MariaDB server is reachable) the shared database itself. */
class StorageTest {
    static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(StorageTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ configuration
    @Test
    void sqliteIsTheDefaultAndNeedsNoNetworkSettings() throws Exception {
        Settings s = Settings.parse(shipped());
        assertEquals("sqlite", s.storage.type());
        assertFalse(s.storage.shared());
    }

    @Test
    void aSharedDatabaseNeedsAServerNameAndAUser() {
        YamlConfiguration y = shipped();
        y.set("database.type", "mariadb");
        y.set("database.username", "");
        ConfigException e = assertThrows(ConfigException.class, () -> Settings.parse(y));
        String all = String.join("\n", e.problems());
        assertTrue(all.contains("network.server-name"), all);
        assertTrue(all.contains("database.username"), all);
    }

    @Test
    void aValidSharedConfigurationIsParsedAndTheEnvironmentSuppliesThePassword() throws Exception {
        YamlConfiguration y = shipped();
        y.set("database.type", "MariaDB");
        y.set("network.server-name", "survival");
        y.set("database.host", "db.internal");
        y.set("database.port", 3307);
        y.set("database.name", "aura_net");
        y.set("database.username", "aura");
        y.set("database.password", "${env:AP_DB_PASSWORD}");
        y.set("database.pool.maximum-size", 6);
        Settings s = Settings.parse(y, null, name -> name.equals("AP_DB_PASSWORD") ? "s3cret" : null);
        assertTrue(s.storage.shared());
        assertEquals("s3cret", s.storage.password());
        assertEquals(6, s.storage.poolSize());
        assertEquals("mariadb://db.internal:3307/aura_net as aura", s.storage.toString());
        assertFalse(s.storage.toString().contains("s3cret"), "the password never appears in anything printable");
        assertTrue(s.storage.jdbcUrl(true).startsWith("jdbc:mariadb://db.internal:3307/aura_net?"));
    }

    @Test
    void anUnknownStorageTypeIsRejectedWithAnActionableMessage() {
        YamlConfiguration y = shipped();
        y.set("database.type", "postgres");
        ConfigException e = assertThrows(ConfigException.class, () -> Settings.parse(y));
        assertTrue(String.join("\n", e.problems()).contains("sqlite, mariadb or mysql"));
    }

    // ------------------------------------------------------------------ the dialect (pure text)
    @Test
    void portableDdlBecomesMariaDbDdl() {
        Dialect.MariaDb d = new Dialect.MariaDb(false);
        String t = d.translate("""
                CREATE TABLE sample (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  uuid TEXT NOT NULL,
                  kind TEXT NOT NULL DEFAULT 'STANDARD',
                  note TEXT,
                  sensitive INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL
                )""");
        assertTrue(t.contains("id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"), t);
        assertTrue(t.contains("kind VARCHAR(191) NOT NULL DEFAULT 'STANDARD'"), "a literal default needs a bounded type: " + t);
        assertTrue(t.contains("note TEXT"), t);
        assertTrue(t.contains("created_at BIGINT NOT NULL"), "timestamps are 64-bit: " + t);
        assertTrue(t.contains("`sensitive` BIGINT") || t.contains("`sensitive` "), "the reserved column name is quoted: " + t);
        assertTrue(t.endsWith("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"), t);
        assertFalse(t.contains("AUTOINCREMENT"));
    }

    @Test
    void aTextPrimaryKeyIsBoundedAndIndexesOnTextColumnsGetAPrefix() {
        Dialect.MariaDb d = new Dialect.MariaDb(false);
        assertTrue(d.translate("CREATE TABLE p (uuid TEXT PRIMARY KEY, name_lower TEXT NOT NULL, level INTEGER NOT NULL DEFAULT 0)").contains("uuid VARCHAR(191) PRIMARY KEY"));
        assertEquals("CREATE INDEX idx_p_name ON p(name_lower(191))", d.translate("CREATE INDEX idx_p_name ON p(name_lower)"));
        assertEquals("CREATE INDEX idx_p_level ON p(level)", d.translate("CREATE INDEX idx_p_level ON p(level)"), "a numeric column gets no prefix");
        assertEquals("CREATE INDEX idx_p_uuid ON p(uuid)", d.translate("CREATE INDEX idx_p_uuid ON p(uuid)"), "a bounded column gets no prefix");
    }

    @Test
    void aColumnAddedLaterIsRememberedForItsIndex() {
        Dialect.MariaDb d = new Dialect.MariaDb(false);
        d.translate("CREATE TABLE q (id INTEGER PRIMARY KEY AUTOINCREMENT)");
        assertEquals("ALTER TABLE q ADD COLUMN uid TEXT", d.translate("ALTER TABLE q ADD COLUMN uid TEXT"));
        assertEquals("CREATE UNIQUE INDEX idx_q_uid ON q(uid(191))", d.translate("CREATE UNIQUE INDEX idx_q_uid ON q(uid)"));
        assertEquals("ALTER TABLE q ADD COLUMN op_ms BIGINT", d.translate("ALTER TABLE q ADD COLUMN op_ms INTEGER"));
    }

    @Test
    void aPartialUniqueIndexLosesItsWhereBecauseNullsAreAlreadyDistinct() {
        Dialect.MariaDb d = new Dialect.MariaDb(false);
        d.translate("CREATE TABLE r (id INTEGER PRIMARY KEY AUTOINCREMENT, punishment_id INTEGER)");
        assertEquals("CREATE UNIQUE INDEX idx_r ON r(punishment_id)", d.translate("CREATE UNIQUE INDEX idx_r ON r(punishment_id) WHERE punishment_id IS NOT NULL"));
    }

    @Test
    void upsertInsertIgnoreRandomIdAndTriggersUseMariaDbSyntax() {
        Dialect d = new Dialect.MariaDb(false);
        assertEquals("INSERT INTO meta(k,v) VALUES(?,?) ON DUPLICATE KEY UPDATE v=VALUES(v)", d.upsert("meta(k,v) VALUES(?,?)", List.of("k"), List.of("v={new:v}")));
        assertEquals("INSERT IGNORE INTO t(a) VALUES(?)", d.insertIgnore("t(a) VALUES(?)"));
        assertEquals("UUID()", d.randomId());
        assertTrue(d.identityTrigger("punishments").contains("BEFORE INSERT ON punishments FOR EACH ROW SET NEW.uid = IFNULL(NEW.uid, UUID()), NEW.origin = IFNULL(NEW.origin, @ap_origin)"));
        assertTrue(d.originTrigger("audit_log").contains("NEW.origin = IFNULL(NEW.origin, @ap_origin)"));
        assertEquals("SELECT CAST(id AS CHAR) FROM x", d.translate("SELECT CAST(id AS TEXT) FROM x"));
    }

    // ------------------------------------------------------------------ the shared database itself (needs a reachable MariaDB: skipped otherwise)
    private static final String HOST = System.getProperty("ap.test.db.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("ap.test.db.port", 3306);
    private final String dbName = "aura_s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    private StorageConfig cfg(boolean allowMigration) {
        return new StorageConfig("mariadb", HOST, PORT, dbName, System.getProperty("ap.test.db.user", "aura"), System.getProperty("ap.test.db.password", "aurapass"),
                false, true, allowMigration, 4, 1, 5_000, 300_000, 1_500_000, 60_000);
    }

    private Database server(String origin, boolean allowMigration) {
        return Database.shared(cfg(allowMigration), Logger.getLogger("test"), origin);
    }

    @BeforeEach
    void requireMariaDb() {
        Assumptions.assumeTrue(reachable(), "no MariaDB server at " + HOST + ":" + PORT + " - the shared-database tests are skipped");
    }

    private static boolean reachable() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterEach
    void dropDatabase() {
        if (!reachable()) return;
        java.util.Properties p = new java.util.Properties();
        p.setProperty("user", System.getProperty("ap.test.db.user", "aura"));
        p.setProperty("password", System.getProperty("ap.test.db.password", "aurapass"));
        try (var c = new org.mariadb.jdbc.Driver().connect("jdbc:mariadb://" + HOST + ":" + PORT + "/", p); var st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + dbName + "`");
        } catch (SQLException ignored) {
            // harmless leftover
        }
    }

    @Test
    void aFreshSharedDatabaseIsCreatedMigratedAndHealthy() throws Exception {
        Database db = server("survival", false);
        db.open();
        try {
            assertTrue(db.shared());
            assertEquals("mariadb", db.dialect().name());
            assertEquals(Schema.latest(), db.schemaVersion());
            assertEquals("survival", db.origin(), "the origin is the configured server name, never generated");
            assertTrue(db.healthy());
        } finally {
            db.close();
        }
    }

    @Test
    void aSharedDatabaseRefusesToStartWithoutAServerName() {
        Database db = server("", false);
        SQLException e = assertThrows(SQLException.class, db::open);
        assertTrue(e.getMessage().contains("network.server-name"), e.getMessage());
        db.close();
    }

    @Test
    void everyRecordCarriesItsOwnServersNameAndAUniqueId() throws Exception {
        Database a = server("survival", false);
        Database b = server("towny", false);
        a.open();
        b.open();
        try {
            for (Database db : List.of(a, b)) {
                db.txSync(c -> Sql.update(c, "INSERT INTO notes(case_id,profile_uuid,author_name,text,created_at) VALUES(NULL,'u','Mod','written here',1)"));
            }
            List<String[]> rows = a.txSync(c -> Sql.query(c, "SELECT origin, uid FROM notes ORDER BY id", rs -> new String[]{rs.getString(1), rs.getString(2)}));
            assertEquals(2, rows.size());
            assertEquals("survival", rows.get(0)[0], "stamped by the connection that inserted it");
            assertEquals("towny", rows.get(1)[0]);
            assertEquals(36, rows.get(0)[1].length());
            assertNotEquals(rows.get(0)[1], rows.get(1)[1]);
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void twoServersStartingAtTheSameTimeTakeTurnsAndMigrateOnce() throws Exception {
        Database a = server("lobby", false);
        Database b = server("skyblock", false);
        try {
            CompletableFuture<Void> fa = CompletableFuture.runAsync(() -> {
                try {
                    a.open();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            CompletableFuture<Void> fb = CompletableFuture.runAsync(() -> {
                try {
                    b.open();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            CompletableFuture.allOf(fa, fb).get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(Schema.latest(), a.schemaVersion());
            assertEquals(Schema.latest(), b.schemaVersion());
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void aServerWithAnOlderPluginIsRefusedAndNeverDamagesANewerSchema() throws Exception {
        Database first = server("survival", false);
        first.open();
        first.txSync(c -> Sql.update(c, "UPDATE meta SET v='99' WHERE k='schema_version'"));
        first.close();
        Database old = server("towny", false);
        SQLException e = assertThrows(SQLException.class, old::open);
        assertTrue(e.getMessage().contains("schema v99") && e.getMessage().contains("Update this server"), e.getMessage());
        old.close();
    }

    @Test
    void anOlderSchemaIsOnlyUpgradedByAServerThatWasToldTo() throws Exception {
        Database first = server("survival", false);
        first.open();
        first.txSync(c -> Sql.update(c, "UPDATE meta SET v='2' WHERE k='schema_version'"));
        first.close();
        Database other = server("towny", false);
        SQLException e = assertThrows(SQLException.class, other::open);
        assertTrue(e.getMessage().contains("allow-migration") && e.getMessage().contains("Stop every server"), e.getMessage());
        other.close();
    }

    @Test
    void anInterruptedMigrationBlocksEveryServerUntilAnAdministratorLooksAtIt() throws Exception {
        Database first = server("survival", false);
        first.open();
        first.txSync(c -> Sql.update(c, "INSERT INTO meta(k,v) VALUES('migrating_to','3')"));
        first.close();
        Database other = server("towny", true);
        SQLException e = assertThrows(SQLException.class, other::open);
        assertTrue(e.getMessage().contains("did not finish") && e.getMessage().contains("backup"), e.getMessage());
        other.close();
    }

    @Test
    void aLostConnectionIsReplacedFromThePoolAndNeverReportedAsSuccess() throws Exception {
        Database db = server("survival", false);
        db.open();
        try {
            db.simulateFailure();
            long n = db.txSync(c -> Sql.count(c, "SELECT COUNT(*) FROM meta"));
            assertTrue(n >= 1);
            assertTrue(db.healthy());
        } finally {
            db.close();
        }
    }

    @Test
    void aWrongPasswordFailsFastWithAMessageThatNeverContainsThePassword() {
        StorageConfig bad = new StorageConfig("mariadb", HOST, PORT, dbName, "aura", "definitely-wrong-password", false, false, false, 2, 1, 2_000, 300_000, 1_500_000, 60_000);
        Database db = Database.shared(bad, Logger.getLogger("test"), "survival");
        SQLException e = assertThrows(SQLException.class, db::open);
        assertFalse(e.getMessage().contains("definitely-wrong-password"), e.getMessage());
        db.close();
    }
}
