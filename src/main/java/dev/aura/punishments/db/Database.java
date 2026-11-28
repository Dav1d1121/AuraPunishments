package dev.aura.punishments.db;

import dev.aura.punishments.db.Sql.SqlFunction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Storage with a single owning thread per server: standalone SQLite (one file, one connection) or the shared MariaDB/MySQL database of a
 * network (a small connection pool).
 *
 * <p>All statements run on one dedicated executor, which serialises this server's writes and keeps blocking I/O off the server thread.
 * Each {@link #tx} call is one atomic transaction. If the connection is lost the next call tries to reopen it (SQLite) or takes a fresh one
 * from the pool (shared); while the database is unavailable every operation fails with a {@link SQLException} (never a silent success)
 * and {@link #lastError()} is available to diagnostics. In shared mode the database itself decides between servers: row locks, unique
 * keys and version columns, because several servers write to it at once.
 */
public final class Database implements AutoCloseable {
    /** The database server cannot be reached (down, wrong address, refused): the situation the outage policy is about. Not a schema or version problem. */
    public static final class UnavailableException extends SQLException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String jdbcUrl;
    private final Path file;
    private final Logger log;
    private final String configuredOrigin;
    private volatile String origin = "default";
    private final StorageConfig storage;
    private final Dialect dialect;
    private com.zaxxer.hikari.HikariDataSource pool; // shared mode only
    private static final String MIGRATION_LOCK = "aurapunishments.migrate";
    private final ExecutorService exec;
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private Connection conn; // only touched on the executor thread

    public Database(Path file, Logger log) {
        this(file, log, null);
    }

    /**
     * @param configuredOrigin the admin-chosen name of this server ({@code network.server-name}); blank means "generate
     *                         one once and remember it". It is stamped on every record this server creates.
     */
    public Database(Path file, Logger log, String configuredOrigin) {
        this(file, log, configuredOrigin, StorageConfig.sqlite());
    }

    /** A shared MariaDB/MySQL database (the network release). {@code origin} is this server's required, unique name. */
    public static Database shared(StorageConfig storage, Logger log, String origin) {
        return new Database(null, log, origin, storage);
    }

    private Database(Path file, Logger log, String configuredOrigin, StorageConfig storage) {
        this.storage = storage;
        this.dialect = storage.shared() ? new Dialect.MariaDb(storage.mysql()) : Dialect.SQLITE;
        this.configuredOrigin = configuredOrigin == null ? "" : configuredOrigin.trim();
        this.file = file;
        this.jdbcUrl = storage.shared() ? storage.jdbcUrl(true) : "jdbc:sqlite:" + (file == null ? ":memory:" : file.toAbsolutePath());
        this.log = log;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AuraPunishments-DB");
            t.setDaemon(true);
            return t;
        });
    }

    /** Open the database, back up before migrating, and migrate to the latest schema. May be called again after an {@link UnavailableException}. */
    public void open() throws SQLException {
        try {
            exec.submit(() -> {
                if (pool != null) {
                    pool.close(); // a previous attempt got as far as a pool but not as far as a usable schema
                    pool = null;
                }
                connect();
                migrate();
                lastError.set(null);
                return null;
            }).get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof SQLException se) {
                lastError.set(se.getMessage());
                throw se;
            }
            throw new SQLException(c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException(e);
        }
    }

    public boolean shared() {
        return storage.shared();
    }

    public StorageConfig storage() {
        return storage;
    }

    /** Same as {@link #databaseTimeMillis()} inside a running transaction. */
    public long databaseTimeMillisIn(Connection c) throws SQLException {
        if (!storage.shared()) return System.currentTimeMillis();
        return Sql.count(c, "SELECT CAST(UNIX_TIMESTAMP(NOW(3))*1000 AS SIGNED)");
    }

    /** The database server's clock in epoch milliseconds (shared mode), to notice a server whose own clock has drifted. */
    public long databaseTimeMillis() throws SQLException {
        if (!storage.shared()) return System.currentTimeMillis();
        return txSync(c -> Sql.count(c, "SELECT CAST(UNIX_TIMESTAMP(NOW(3))*1000 AS SIGNED)"));
    }

    /** Open the connection pool of a shared database (creating the database first when configured to). */
    private void connectShared() throws SQLException {
        if (storage.createDatabase()) {
            java.sql.Driver driver = new org.mariadb.jdbc.Driver();
            java.util.Properties props = new java.util.Properties();
            props.setProperty("user", storage.username());
            props.setProperty("password", storage.password());
            try (Connection c = driver.connect(storage.jdbcUrl(false), props); Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE IF NOT EXISTS `" + storage.database() + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
        }
        com.zaxxer.hikari.HikariConfig cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setPoolName("AuraPunishments");
        cfg.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
        cfg.setJdbcUrl(storage.jdbcUrl(true));
        cfg.setUsername(storage.username());
        cfg.setPassword(storage.password());
        cfg.setMaximumPoolSize(storage.poolSize());
        cfg.setMinimumIdle(storage.minIdle());
        cfg.setConnectionTimeout(Math.max(1000, storage.connectionTimeoutMs()));
        cfg.setIdleTimeout(storage.idleTimeoutMs());
        cfg.setMaxLifetime(storage.maxLifetimeMs());
        if (storage.keepaliveMs() > 0 && storage.keepaliveMs() < storage.maxLifetimeMs()) cfg.setKeepaliveTime(storage.keepaliveMs());
        cfg.setInitializationFailTimeout(Math.max(1000, storage.connectionTimeoutMs()));
        cfg.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        // every connection: strict SQL, || concatenates (the portable SQL uses it), UTC, and this server's name for the identity triggers
        cfg.setConnectionInitSql("SET SESSION sql_mode='STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION,PIPES_AS_CONCAT', "
                + "time_zone='+00:00', @ap_origin='" + configuredOrigin.replace("'", "") + "'");
        try {
            pool = new com.zaxxer.hikari.HikariDataSource(cfg);
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            throw new UnavailableException("Cannot connect to the shared database " + storage + ": " + root.getMessage(), e);
        }
    }

    private void connect() throws SQLException {
        if (storage.shared()) {
            connectShared();
            return;
        }
        if (file != null) {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
            } catch (IOException e) {
                throw new SQLException("Cannot create data folder: " + e.getMessage(), e);
            }
        }
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
            if (file != null) {
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA synchronous = FULL");
            }
        }
        conn = c;
    }

    /** The identifier of this server that is stamped on records it creates (never blank once the database is open). */
    public String origin() {
        return origin;
    }

    public Dialect dialect() {
        return dialect;
    }

    private void migrate() throws SQLException {
        if (storage.shared()) {
            migrateShared();
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
        }
        resolveOrigin();
        int current = Sql.one(conn, "SELECT v FROM meta WHERE k='schema_version'", rs -> Integer.parseInt(rs.getString(1))) == null
                ? 0 : Sql.one(conn, "SELECT v FROM meta WHERE k='schema_version'", rs -> Integer.parseInt(rs.getString(1)));
        int latest = Schema.latest();
        if (current > latest) {
            throw new SQLException("Database schema v" + current + " is newer than this plugin (v" + latest
                    + "). Refusing to start so data is not damaged. Restore the matching plugin version or a backup.");
        }
        if (current == latest) return;
        if (current > 0 && file != null) backup(current);
        for (Schema.Migration m : Schema.MIGRATIONS) {
            if (m.version() <= current) continue;
            conn.setAutoCommit(false);
            try {
                Schema.Context ctx = new Schema.Context(dialect, origin, log);
                for (Schema.Step step : m.steps()) step.run(conn, ctx);
                Sql.update(conn, dialect.upsert("meta(k,v) VALUES('schema_version',?)", List.of("k"), List.of("v={new:v}")),
                        String.valueOf(m.version()));
                conn.commit();
                log.info("Database migrated to schema v" + m.version() + " (" + m.description() + ")");
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Create or upgrade the shared schema. A named database lock makes two servers that start together take turns; a server may only
     * upgrade an existing schema when it was told to ({@code database.allow-migration}), because the other servers of the network
     * would otherwise keep running against a schema they do not understand. DDL cannot be rolled back on MariaDB/MySQL, so an interrupted
     * migration is recorded and blocks every server until an administrator restores the backup.
     */
    private void migrateShared() throws SQLException {
        if (configuredOrigin.isEmpty()) throw new SQLException("network.server-name is required with a shared database");
        this.origin = configuredOrigin;
        Dialect.MariaDb d = (Dialect.MariaDb) dialect;
        try (Connection c = pool.getConnection()) {
            Long got = Sql.one(c, "SELECT GET_LOCK(?, 60)", rs -> rs.getLong(1), MIGRATION_LOCK);
            if (got == null || got != 1L) throw new SQLException("Another server is migrating the shared database; start this one again in a minute.");
            try {
                d.loadSchema(c);
                try (Statement st = c.createStatement()) {
                    st.execute(d.translate("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)"));
                }
                String interrupted = Sql.one(c, "SELECT v FROM meta WHERE k='migrating_to'", rs -> rs.getString(1));
                if (interrupted != null) {
                    throw new SQLException("A schema migration to v" + interrupted + " did not finish. The shared database may be half-migrated: restore your "
                            + "backup (see docs/NETWORK_DEPLOYMENT.md), or clear the 'migrating_to' row in the meta table only if you have verified the schema.");
                }
                String v = Sql.one(c, "SELECT v FROM meta WHERE k='schema_version'", rs -> rs.getString(1));
                int current = v == null ? 0 : Integer.parseInt(v);
                int latest = Schema.latest();
                if (current > latest) {
                    throw new SQLException("The shared database is at schema v" + current + " but this plugin only knows v" + latest
                            + ". Update this server's AuraPunishments to the same version as the rest of the network.");
                }
                if (current == latest) return;
                if (current > 0 && !storage.allowMigration()) {
                    throw new SQLException("The shared database is at schema v" + current + " and this plugin needs v" + latest + ". Stop every server of the network, "
                            + "back up the database, then start ONE server with database.allow-migration: true (see docs/NETWORK_DEPLOYMENT.md). Start the others afterwards.");
                }
                for (Schema.Migration m : Schema.MIGRATIONS) {
                    if (m.version() <= current) continue;
                    Sql.update(c, d.upsert("meta(k,v) VALUES('migrating_to',?)", List.of("k"), List.of("v={new:v}")), String.valueOf(m.version()));
                    c.setAutoCommit(false);
                    Schema.Context ctx = new Schema.Context(dialect, origin, log);
                    for (Schema.Step step : m.steps()) step.run(c, ctx);
                    Sql.update(c, d.upsert("meta(k,v) VALUES('schema_version',?)", List.of("k"), List.of("v={new:v}")), String.valueOf(m.version()));
                    Sql.update(c, "DELETE FROM meta WHERE k='migrating_to'");
                    c.commit();
                    c.setAutoCommit(true);
                    log.info("Shared database migrated to schema v" + m.version() + " (" + m.description() + ")");
                }
            } finally {
                try {
                    if (!c.getAutoCommit()) c.setAutoCommit(true);
                    Sql.one(c, "SELECT RELEASE_LOCK(?)", rs -> rs.getLong(1), MIGRATION_LOCK);
                } catch (SQLException ignored) {
                    // the lock is released when the connection closes anyway
                }
            }
        }
    }

    /**
     * Server identity: a random id is generated once and kept in {@code meta}; the admin-chosen name wins when set.
     * A name that differs from the one first recorded is used but logged, because renaming a server later splits
     * its records across two origins (they stay valid; they just no longer group together).
     */
    private void resolveOrigin() throws SQLException {
        String id = Sql.one(conn, "SELECT v FROM meta WHERE k='server_id'", rs -> rs.getString(1));
        if (id == null) {
            id = "srv-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            Sql.update(conn, "INSERT INTO meta(k,v) VALUES('server_id',?)", id);
        }
        String recorded = Sql.one(conn, "SELECT v FROM meta WHERE k='origin_name'", rs -> rs.getString(1));
        String chosen = configuredOrigin.isEmpty() ? (recorded != null ? recorded : id) : configuredOrigin;
        if (recorded == null) {
            Sql.update(conn, "INSERT INTO meta(k,v) VALUES('origin_name',?)", chosen);
        } else if (!recorded.equals(chosen)) {
            log.warning("network.server-name is now '" + chosen + "' but this database's records were created as '" + recorded
                    + "'. Records already stored keep the old name; choose one name per server and keep it.");
            Sql.update(conn, "UPDATE meta SET v=? WHERE k='origin_name'", chosen);
        }
        this.origin = chosen;
    }

    private void backup(int fromVersion) {
        try {
            Path dir = file.toAbsolutePath().getParent().resolve("backups");
            Files.createDirectories(dir);
            Path target = dir.resolve(file.getFileName() + ".pre-v" + (fromVersion + 1) + "-" + System.currentTimeMillis());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Database backed up to " + target.getFileName() + " before migration");
        } catch (Exception e) {
            log.warning("Could not back up database before migration: " + e.getMessage());
        }
    }

    private Connection ensure() throws SQLException {
        if (storage.shared()) {
            if (pool == null) throw new UnavailableException("The shared database is not connected", null);
            return pool.getConnection();
        }
        if (conn != null) {
            try {
                if (!conn.isClosed() && conn.isValid(2)) return conn;
            } catch (SQLException ignored) {
                // fall through and reconnect
            }
            try {
                conn.close();
            } catch (SQLException ignored) {
                // ignore
            }
            conn = null;
        }
        connect();
        return conn;
    }

    /** Run work on the DB thread inside one transaction. Completes exceptionally with the SQLException on failure. */
    public <T> CompletableFuture<T> tx(SqlFunction<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            exec.execute(() -> {
                try {
                    f.complete(runTx(work));
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            f.completeExceptionally(new SQLException("Database is shut down"));
        }
        return f;
    }

    private <T> T runTx(SqlFunction<T> work) throws SQLException {
        // another server can win a lock on the shared database: such a transaction is simply run again (the work is pure database work)
        for (int attempt = 1; ; attempt++) {
            try {
                return runTxOnce(work);
            } catch (SQLException se) {
                if (!storage.shared() || attempt >= 3 || !retryable(se)) throw se;
                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw se;
                }
            }
        }
    }

    /** Deadlock / lock wait timeout / serialization failure (MariaDB 1213 and 1205, SQLState 40001). */
    private static boolean retryable(SQLException e) {
        return "40001".equals(e.getSQLState()) || e.getErrorCode() == 1213 || e.getErrorCode() == 1205;
    }

    private <T> T runTxOnce(SqlFunction<T> work) throws SQLException {
        Connection c;
        try {
            c = ensure();
        } catch (SQLException e) {
            lastError.set(e.getMessage());
            throw e;
        }
        try {
            c.setAutoCommit(false);
            T result = work.apply(c);
            c.commit();
            lastError.set(null);
            return result;
        } catch (Throwable t) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // connection may already be dead
            }
            if (t instanceof SQLException se) {
                lastError.set(se.getMessage());
                throw se;
            }
            throw t;
        } finally {
            try {
                c.setAutoCommit(true);
            } catch (SQLException ignored) {
                // ignore
            }
            if (storage.shared()) {
                try {
                    c.close(); // back to the pool
                } catch (SQLException ignored) {
                    // ignore
                }
            }
        }
    }

    /** Blocking convenience for tests and off-thread callers (login thread). Never call from the server thread. */
    public <T> T txSync(SqlFunction<T> work) throws SQLException {
        try {
            return tx(work).get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof SQLException se) throw se;
            if (c instanceof RuntimeException re) throw re;
            throw new SQLException(c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException(e);
        }
    }

    /** Test hook: forcibly break the connection to simulate a database failure. */
    public void simulateFailure() {
        if (storage.shared()) {
            if (pool != null) pool.getHikariPoolMXBean().softEvictConnections();
            return;
        }
        exec.execute(() -> {
            try {
                if (conn != null) conn.close();
            } catch (SQLException ignored) {
                // ignore
            }
        });
    }

    public String lastError() {
        return lastError.get();
    }

    public boolean healthy() {
        return lastError.get() == null;
    }

    public int schemaVersion() {
        try {
            return txSync(c -> {
                String v = Sql.one(c, "SELECT v FROM meta WHERE k='schema_version'", rs -> rs.getString(1));
                return v == null ? 0 : Integer.parseInt(v);
            });
        } catch (SQLException e) {
            return -1;
        }
    }

    @Override
    public void close() {
        if (exec.isShutdown()) return; // already closed (a failed start, a recovery that never happened, a second onDisable)
        try {
            exec.execute(() -> {
                try {
                    if (conn != null) conn.close();
                    if (pool != null) pool.close();
                } catch (SQLException ignored) {
                    // ignore
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return;
        }
        exec.shutdown();
        try {
            exec.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
