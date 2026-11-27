package dev.aura.punishments.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * The few places where SQL differs between database engines. Everything else in the plugin is plain
 * portable SQL. 1.3.0 ships the SQLite dialect only; the network release (1.4.0) adds MariaDB/MySQL
 * implementations behind this same interface, so no service or repository code has to change.
 *
 * <p>Statements that need an engine-specific form are written once in portable shorthand and passed
 * through the dialect:
 * <ul>
 *   <li>{@link #translate} rewrites DDL ({@code INTEGER PRIMARY KEY AUTOINCREMENT}, column types);</li>
 *   <li>{@link #insertIgnore} turns an INSERT into "insert unless the unique key exists";</li>
 *   <li>{@link #upsert} appends the "update when the key exists" clause; inside {@code updates} a value
 *       coming from the attempted insert is written {@code {new:column}}.</li>
 * </ul>
 */
public interface Dialect {
    /** Short stable name, e.g. {@code sqlite}; also stored in diagnostics. */
    String name();

    /** Adapt portable DDL to this engine. Identity for SQLite, which the portable form targets. */
    String translate(String portableSql);

    /** {@code insertBody} is everything after "INSERT ... INTO", e.g. {@code t(a,b) VALUES(?,?)}. */
    String insertIgnore(String insertBody);

    /** @return the complete upsert statement: {@code INSERT INTO <insertBody> <conflict clause>}. */
    String upsert(String insertBody, List<String> conflictColumns, List<String> updates);

    /** SQL expression for "a new random UUID string" evaluated by the database (used by SQL-only backfills). */
    String randomId();

    /**
     * DDL for a trigger that gives every newly inserted row of {@code table} a unique {@code uid} and this server's
     * {@code origin} (read from {@code meta.origin_name}) when the inserting code did not supply them. It makes the
     * "every record is globally identifiable" rule hold for all code paths, present and future.
     */
    String identityTrigger(String table);

    /** Same for a table that only carries the origin (the audit log). */
    String originTrigger(String table);

    final class SQLite implements Dialect {
        @Override
        public String name() {
            return "sqlite";
        }

        @Override
        public String translate(String portableSql) {
            return portableSql;
        }

        @Override
        public String insertIgnore(String insertBody) {
            return "INSERT OR IGNORE INTO " + insertBody;
        }

        @Override
        public String upsert(String insertBody, List<String> conflictColumns, List<String> updates) {
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(insertBody)
                    .append(" ON CONFLICT(").append(String.join(",", conflictColumns)).append(") DO UPDATE SET ");
            for (int i = 0; i < updates.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(updates.get(i).replaceAll("\\{new:([A-Za-z0-9_]+)}", "excluded.$1"));
            }
            return sb.toString();
        }

        @Override
        public String identityTrigger(String table) {
            return "CREATE TRIGGER trg_" + table + "_identity AFTER INSERT ON " + table + " WHEN NEW.uid IS NULL BEGIN "
                    + "UPDATE " + table + " SET uid=" + randomId() + ", origin=COALESCE(NEW.origin,(SELECT v FROM meta WHERE k='origin_name')) "
                    + "WHERE id=NEW.id; END";
        }

        @Override
        public String originTrigger(String table) {
            return "CREATE TRIGGER trg_" + table + "_origin AFTER INSERT ON " + table + " WHEN NEW.origin IS NULL BEGIN "
                    + "UPDATE " + table + " SET origin=(SELECT v FROM meta WHERE k='origin_name') WHERE id=NEW.id; END";
        }

        @Override
        public String randomId() {
            // RFC 4122 version-4 layout, so ids written by SQL look exactly like the ones Java generates
            return "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)),2) || '-'"
                    + " || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)),2) || '-' || hex(randomblob(6)))";
        }
    }

    Dialect SQLITE = new SQLite();

    /** The dialect of an open connection. Only SQLite is supported in 1.3.0; anything else is refused loudly. */
    static Dialect of(Connection c) throws SQLException {
        String product = c.getMetaData().getDatabaseProductName();
        if (product != null && product.toLowerCase(java.util.Locale.ROOT).contains("sqlite")) return SQLITE;
        throw new SQLException("Unsupported database engine '" + product + "'. AuraPunishments 1.3.0 supports SQLite; "
                + "MariaDB/MySQL arrives with the network release.");
    }
}
