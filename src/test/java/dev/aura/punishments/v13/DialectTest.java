package dev.aura.punishments.v13;

import dev.aura.punishments.db.Database;
import dev.aura.punishments.db.Dialect;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The storage abstraction: the SQLite dialect produces working statements, and an unknown engine is refused loudly. */
class DialectTest {
    @Test
    void theSqliteDialectBuildsWorkingUpsertAndInsertIgnoreStatements() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:"); Statement st = c.createStatement()) {
            Dialect d = Dialect.of(c);
            assertEquals("sqlite", d.name());
            st.execute("CREATE TABLE t (k TEXT PRIMARY KEY, v TEXT NOT NULL, n INTEGER NOT NULL DEFAULT 0)");
            String up = d.upsert("t(k,v) VALUES(?,?)", List.of("k"), List.of("v={new:v}", "n=n+1"));
            for (String v : List.of("one", "two", "three")) {
                try (var ps = c.prepareStatement(up)) {
                    ps.setString(1, "key");
                    ps.setString(2, v);
                    ps.executeUpdate();
                }
            }
            try (var rs = st.executeQuery("SELECT v,n FROM t")) {
                assertTrue(rs.next());
                assertEquals("three", rs.getString(1));
                assertEquals(2, rs.getInt(2));
            }
            String ig = d.insertIgnore("t(k,v) VALUES(?,?)");
            try (var ps = c.prepareStatement(ig)) {
                ps.setString(1, "key");
                ps.setString(2, "ignored");
                assertEquals(0, ps.executeUpdate(), "an existing key is silently skipped");
                ps.setString(1, "other");
                assertEquals(1, ps.executeUpdate());
            }
            try (var rs = st.executeQuery("SELECT " + d.randomId())) {
                rs.next();
                assertTrue(rs.getString(1).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
            }
        }
    }

    @Test
    void ddlPassesThroughUnchangedForSqlite() {
        String ddl = "CREATE TABLE x (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)";
        assertEquals(ddl, Dialect.SQLITE.translate(ddl));
    }

    @Test
    void theDatabaseExposesItsDialectAndServerIdentity() throws Exception {
        Database db = new Database(null, Logger.getLogger("test"), "lobby");
        db.open();
        try {
            assertEquals("sqlite", db.dialect().name());
            assertEquals("lobby", db.origin());
            assertEquals(dev.aura.punishments.db.Schema.latest(), db.schemaVersion());
        } finally {
            db.close();
        }
    }

    @Test
    void anUnsupportedEngineIsRefusedWithAClearMessage() throws Exception {
        java.lang.reflect.InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "getMetaData" -> java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.DatabaseMetaData.class},
                    (p2, m2, a2) -> m2.getName().equals("getDatabaseProductName") ? "OracleDB" : null);
            default -> null;
        };
        Connection fake = (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, h);
        SQLException ex = assertThrows(SQLException.class, () -> Dialect.of(fake));
        assertTrue(ex.getMessage().contains("Unsupported database engine"), ex.getMessage());
    }
}
