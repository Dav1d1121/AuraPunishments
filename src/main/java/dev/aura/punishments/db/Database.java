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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * SQLite storage with a single owning thread.
 *
 * <p>All statements run on one dedicated executor over one connection, which serialises writes and
 * keeps blocking I/O off the server thread. Each {@link #tx} call is one atomic transaction. If the
 * connection is lost the next call tries to reopen it; while the database is unavailable every
 * operation fails with a {@link SQLException} (never a silent success) and {@link #lastError()} is
 * available to diagnostics.
 */
public final class Database implements AutoCloseable {
    private final String jdbcUrl;
    private final Path file;
    private final Logger log;
    private final ExecutorService exec;
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private Connection conn; // only touched on the executor thread

    public Database(Path file, Logger log) {
        this.file = file;
        this.jdbcUrl = "jdbc:sqlite:" + (file == null ? ":memory:" : file.toAbsolutePath());
        this.log = log;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AuraPunishments-DB");
            t.setDaemon(true);
            return t;
        });
    }

    /** Open the database, back up before migrating, and migrate to the latest schema. */
    public void open() throws SQLException {
        try {
            exec.submit(() -> {
                connect();
                migrate();
                return null;
            }).get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof SQLException se) throw se;
            throw new SQLException(c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException(e);
        }
    }

    private void connect() throws SQLException {
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

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
        }
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
                for (String s : m.statements()) {
                    try (Statement st = conn.createStatement()) {
                        st.execute(s);
                    }
                }
                Sql.update(conn, "INSERT INTO meta(k,v) VALUES('schema_version',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
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
        exec.execute(() -> {
            try {
                if (conn != null) conn.close();
            } catch (SQLException ignored) {
                // ignore
            }
        });
        exec.shutdown();
        try {
            exec.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
