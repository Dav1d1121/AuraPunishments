package dev.aura.punishments.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Tiny JDBC helpers. Every statement is parameterised; user text is never concatenated into SQL. */
public final class Sql {
    private Sql() {}

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            int idx = i + 1;
            if (a == null) ps.setObject(idx, null);
            else if (a instanceof UUID u) ps.setString(idx, u.toString());
            else if (a instanceof Enum<?> e) ps.setString(idx, e.name());
            else if (a instanceof Boolean b) ps.setInt(idx, b ? 1 : 0);
            else ps.setObject(idx, a);
        }
    }

    public static <T> List<T> query(Connection c, String sql, RowMapper<T> m, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(m.map(rs));
                return out;
            }
        }
    }

    public static <T> T one(Connection c, String sql, RowMapper<T> m, Object... args) throws SQLException {
        List<T> l = query(c, sql, m, args);
        return l.isEmpty() ? null : l.get(0);
    }

    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    /** Insert and return the generated key. */
    public static long insert(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            throw new SQLException("No generated key for insert");
        }
    }

    public static long count(Connection c, String sql, Object... args) throws SQLException {
        Long v = one(c, sql, rs -> rs.getLong(1), args);
        return v == null ? 0 : v;
    }

    public static UUID uuid(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static boolean bool(ResultSet rs, String col) throws SQLException {
        return rs.getInt(col) != 0;
    }
}
