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

    /**
     * MariaDB 10.6+ and MySQL 8+ (the shared network database). The portable DDL the migrations are written in is rewritten on the fly:
     * {@code INTEGER} becomes {@code BIGINT} (timestamps are milliseconds), {@code AUTOINCREMENT} becomes {@code AUTO_INCREMENT},
     * a {@code TEXT} column that is a key or has a literal default becomes {@code VARCHAR(191)}, other {@code TEXT} columns that get an index
     * get a prefix length, and a partial unique index loses its {@code WHERE} (a unique index already allows many NULLs here).
     * The column types of what already exists are read from the database before a migration, so a later migration can alter earlier tables.
     */
    final class MariaDb implements Dialect {
        private final boolean mysql;
        /** table (lower case) -> column (lower case) -> "text" | "other" */
        private final java.util.Map<String, java.util.Map<String, String>> columns = new java.util.HashMap<>();

        public MariaDb(boolean mysql) {
            this.mysql = mysql;
        }

        @Override
        public String name() {
            return mysql ? "mysql" : "mariadb";
        }

        /** Learn the column types of the tables that already exist (so index prefixes can be added to TEXT columns later on). */
        public void loadSchema(Connection c) throws SQLException {
            columns.clear();
            try (var st = c.createStatement(); var rs = st.executeQuery(
                    "SELECT table_name, column_name, data_type FROM information_schema.columns WHERE table_schema = DATABASE()")) {
                while (rs.next()) {
                    String type = rs.getString(3).toLowerCase(java.util.Locale.ROOT);
                    columns.computeIfAbsent(rs.getString(1).toLowerCase(java.util.Locale.ROOT), k -> new java.util.HashMap<>())
                            .put(rs.getString(2).toLowerCase(java.util.Locale.ROOT), type.contains("text") ? "text" : "other");
                }
            }
        }

        private static final java.util.regex.Pattern CREATE_TABLE = java.util.regex.Pattern.compile("(?is)^CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s*\\((.*)\\)\\s*$");
        private static final java.util.regex.Pattern CREATE_INDEX = java.util.regex.Pattern.compile("(?is)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s+ON\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(WHERE\\s+.*)?$");
        private static final java.util.regex.Pattern ADD_COLUMN = java.util.regex.Pattern.compile("(?is)^ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+(\\w+)\\s+(\\w+)(.*)$");
        private static final java.util.regex.Pattern RENAME = java.util.regex.Pattern.compile("(?is)^ALTER\\s+TABLE\\s+(\\w+)\\s+RENAME\\s+TO\\s+(\\w+)\\s*$");
        private static final java.util.regex.Pattern DROP = java.util.regex.Pattern.compile("(?is)^DROP\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?(\\w+)\\s*$");
        private static final java.util.regex.Pattern COLUMN = java.util.regex.Pattern.compile("(?is)^(\\w+)\\s+(INTEGER|TEXT|REAL|BLOB)\\b(.*)$");

        private static final java.util.regex.Pattern RESERVED = java.util.regex.Pattern.compile("(?i)(?<![`'\\w])sensitive(?![`'\\w])");

        /** Quote the schema's one column name that MariaDB / MySQL reserve ("sensitive"). */
        public static String quoteReserved(String sql) {
            return RESERVED.matcher(sql).replaceAll("`sensitive`");
        }

        @Override
        public String translate(String portableSql) {
            return quoteReserved(translateTypes(portableSql));
        }

        private String translateTypes(String portableSql) {
            String sql = portableSql.strip();
            java.util.regex.Matcher m;
            if ((m = CREATE_TABLE.matcher(sql)).matches()) return createTable(m.group(1) == null ? "" : m.group(1), m.group(2), m.group(3));
            if ((m = CREATE_INDEX.matcher(sql)).matches()) return createIndex(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6));
            if ((m = ADD_COLUMN.matcher(sql)).matches()) {
                String type = convertType(m.group(2), m.group(3), m.group(4));
                remember(m.group(1), m.group(2), type);
                return "ALTER TABLE " + m.group(1) + " ADD COLUMN " + m.group(2) + " " + type;
            }
            if ((m = RENAME.matcher(sql)).matches()) {
                var cols = columns.remove(m.group(1).toLowerCase(java.util.Locale.ROOT));
                if (cols != null) columns.put(m.group(2).toLowerCase(java.util.Locale.ROOT), cols);
                return sql;
            }
            if ((m = DROP.matcher(sql)).matches()) columns.remove(m.group(2).toLowerCase(java.util.Locale.ROOT));
            return sql.replaceAll("(?i)CAST\\(([^()]*(?:\\([^()]*\\))?[^()]*) AS TEXT\\)", "CAST($1 AS CHAR)");
        }

        private void remember(String table, String column, String converted) {
            columns.computeIfAbsent(table.toLowerCase(java.util.Locale.ROOT), k -> new java.util.HashMap<>())
                    .put(column.toLowerCase(java.util.Locale.ROOT), converted.toUpperCase(java.util.Locale.ROOT).startsWith("TEXT") ? "text" : "other");
        }

        /** "name TYPE rest" -> "TYPE' rest'" for this engine. */
        private String convertType(String column, String type, String rest) {
            String r = rest == null ? "" : rest;
            switch (type.toUpperCase(java.util.Locale.ROOT)) {
                case "INTEGER":
                    if (r.toUpperCase(java.util.Locale.ROOT).contains("PRIMARY KEY AUTOINCREMENT")) {
                        return "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" + r.replaceAll("(?i)\\s*PRIMARY KEY AUTOINCREMENT", "");
                    }
                    return "BIGINT" + r;
                case "REAL":
                    return "DOUBLE" + r;
                case "BLOB":
                    return "LONGBLOB" + r;
                default: // TEXT
                    String u = r.toUpperCase(java.util.Locale.ROOT);
                    if (u.contains("PRIMARY KEY") || u.contains("DEFAULT '")) return "VARCHAR(191)" + r; // keys and literal defaults need a bounded type
                    return "TEXT" + r;
            }
        }

        private String createTable(String ifNot, String table, String body) {
            java.util.List<String> parts = splitTopLevel(body);
            StringBuilder out = new StringBuilder("CREATE TABLE ").append(ifNot).append(table).append(" (");
            columns.remove(table.toLowerCase(java.util.Locale.ROOT));
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i).strip();
                java.util.regex.Matcher m = COLUMN.matcher(part);
                if (m.matches()) {
                    String converted = convertType(m.group(1), m.group(2), m.group(3));
                    remember(table, m.group(1), converted);
                    part = m.group(1) + " " + converted;
                }
                if (i > 0) out.append(", ");
                out.append(part);
            }
            return out.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4").toString();
        }

        private String createIndex(String unique, String ifNot, String name, String table, String cols, String where) {
            java.util.List<String> out = new java.util.ArrayList<>();
            var known = columns.getOrDefault(table.toLowerCase(java.util.Locale.ROOT), java.util.Map.of());
            for (String col : cols.split(",")) {
                String c = col.strip();
                String bare = c.split("\\s+")[0];
                out.add("text".equals(known.get(bare.toLowerCase(java.util.Locale.ROOT))) ? bare + "(191)" + c.substring(bare.length()) : c);
            }
            // a partial index ("WHERE x IS NOT NULL") is dropped: unique indexes here already accept several NULLs
            return "CREATE " + (unique == null ? "" : "UNIQUE ") + "INDEX " + name + " ON " + table + "(" + String.join(", ", out) + ")";
        }

        private static java.util.List<String> splitTopLevel(String s) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            int depth = 0;
            boolean quote = false;
            int start = 0;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\'') quote = !quote;
                if (quote) continue;
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (ch == ',' && depth == 0) {
                    parts.add(s.substring(start, i));
                    start = i + 1;
                }
            }
            parts.add(s.substring(start));
            return parts;
        }

        @Override
        public String insertIgnore(String insertBody) {
            return "INSERT IGNORE INTO " + insertBody;
        }

        @Override
        public String upsert(String insertBody, List<String> conflictColumns, List<String> updates) {
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(insertBody).append(" ON DUPLICATE KEY UPDATE ");
            for (int i = 0; i < updates.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(updates.get(i).replaceAll("\\{new:([A-Za-z0-9_]+)}", "VALUES($1)"));
            }
            return sb.toString();
        }

        @Override
        public String randomId() {
            return "UUID()";
        }

        /** The inserting connection carries the server's name in {@code @ap_origin} (set when the connection is opened). */
        @Override
        public String identityTrigger(String table) {
            return "CREATE TRIGGER trg_" + table + "_identity BEFORE INSERT ON " + table + " FOR EACH ROW "
                    + "SET NEW.uid = IFNULL(NEW.uid, UUID()), NEW.origin = IFNULL(NEW.origin, @ap_origin)";
        }

        @Override
        public String originTrigger(String table) {
            return "CREATE TRIGGER trg_" + table + "_origin BEFORE INSERT ON " + table + " FOR EACH ROW "
                    + "SET NEW.origin = IFNULL(NEW.origin, @ap_origin)";
        }
    }

    Dialect SQLITE = new SQLite();

    /** The dialect of an open connection. Anything that is not SQLite, MariaDB or MySQL is refused loudly. */
    static Dialect of(Connection c) throws SQLException {
        String product = c.getMetaData().getDatabaseProductName();
        String p = product == null ? "" : product.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("sqlite")) return SQLITE;
        if (p.contains("mariadb")) return new MariaDb(false);
        if (p.contains("mysql")) return new MariaDb(true);
        throw new SQLException("Unsupported database engine '" + product + "'. AuraPunishments supports SQLite, MariaDB 10.6+ and MySQL 8+.");
    }
}
