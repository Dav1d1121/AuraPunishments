package dev.aura.punishments.service;

import java.util.List;

/**
 * Read-only view of CoreProtect used for case evidence. There is deliberately no rollback, restore or purge method:
 * nothing in AuraPunishments can change the world through CoreProtect, and a lookup result never decides a case.
 */
public interface CoreProtectPort {
    enum Kind { BLOCKS, CHAT, COMMANDS, SESSIONS }

    /** @param world/x/y/z/radius only used for BLOCKS; world may be null for the other kinds */
    record Query(String player, Kind kind, long seconds, String world, int x, int y, int z, int radius) {}

    record Result(List<String> lines, int total, boolean truncated) {}

    boolean available();

    String status();

    /** Blocking; call off the main thread. */
    Result lookup(Query q) throws Exception;

    CoreProtectPort NONE = new CoreProtectPort() {
        @Override public boolean available() { return false; }
        @Override public String status() { return "CoreProtect is not installed"; }
        @Override public Result lookup(Query q) { throw new IllegalStateException("CoreProtect is not available"); }
    };
}
