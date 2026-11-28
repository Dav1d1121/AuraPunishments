package dev.aura.punishments.db;

/**
 * Where the data lives. {@code sqlite} is the standalone default (one file, exactly as in 1.0 - 1.3). {@code mariadb} and {@code mysql}
 * mean one shared database used by every server of a network: the same jar on each backend, each with its own {@code network.server-name}.
 *
 * @param type               sqlite | mariadb | mysql
 * @param createDatabase     create the database (utf8mb4) when it does not exist; needs the privilege, otherwise create it by hand

 * @param allowMigration     this server may upgrade the shared schema (set it on ONE server, after stopping the others and taking a backup)
 * @param poolSize           largest number of connections this server keeps open
 * @param minIdle            connections kept ready when idle
 * @param connectionTimeoutMs how long a request waits for a connection before it fails (the staff member is told, nothing is faked)
 * @param idleTimeoutMs      an unused connection is closed after this long (above minIdle)
 * @param maxLifetimeMs      connections are replaced after this long (keep it below the server's wait_timeout)
 * @param keepaliveMs        idle connections are tested this often so a dropped one is noticed before it is needed
 */
public record StorageConfig(String type, String host, int port, String database, String username, String password, boolean ssl,
                            boolean createDatabase, boolean allowMigration, int poolSize, int minIdle, long connectionTimeoutMs, long idleTimeoutMs,
                            long maxLifetimeMs, long keepaliveMs) {

    public boolean shared() {
        return !"sqlite".equalsIgnoreCase(type);
    }

    public boolean mysql() {
        return "mysql".equalsIgnoreCase(type);
    }

    /** The JDBC URL, without the password. The MariaDB driver speaks to both MariaDB and MySQL servers. */
    public String jdbcUrl(boolean withDatabase) {
        StringBuilder sb = new StringBuilder("jdbc:mariadb://").append(host).append(':').append(port).append('/');
        if (withDatabase) sb.append(database);
        sb.append("?useSsl=").append(ssl ? "true" : "false");
        sb.append("&characterEncoding=utf8mb4&serverTimezone=UTC&connectTimeout=").append(Math.max(1000, connectionTimeoutMs));
        sb.append("&socketTimeout=").append(Math.max(5000, connectionTimeoutMs * 3));
        return sb.toString();
    }

    /** A copy that is safe to print: never contains the password. */
    @Override
    public String toString() {
        return type + "://" + host + ':' + port + '/' + database + " as " + username;
    }

    public static StorageConfig sqlite() {
        return new StorageConfig("sqlite", "", 0, "", "", "", false, false, true, 1, 1, 10_000, 600_000, 1_800_000, 0);
    }
}
