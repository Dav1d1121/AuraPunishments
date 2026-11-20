package dev.aura.punishments.service;

import java.util.UUID;

/**
 * Who is performing an operation. Services only ever see an Actor, never a raw CommandSender,
 * so authorisation is identical for the GUI, commands and scheduled system actions, and can be
 * unit-tested without a server. The {@link PermView} is a LIVE view: revoking a permission in
 * LuckPerms takes effect on the next check even while a GUI is open.
 */
public record Actor(UUID uuid, String name, Kind kind, PermView perms) {

    public enum Kind { PLAYER, CONSOLE, SYSTEM }

    public interface PermView {
        boolean has(String node);

        /** Highest aurapunishments.level.N held (0 if none). */
        int level();
    }

    public static final UUID SYSTEM_UUID = new UUID(0, 0);

    public static Actor console() {
        return new Actor(null, "Console", Kind.CONSOLE, ALL);
    }

    public static Actor system() {
        return new Actor(SYSTEM_UUID, "System", Kind.SYSTEM, ALL);
    }

    public static Actor player(UUID uuid, String name, PermView perms) {
        return new Actor(uuid, name, Kind.PLAYER, perms);
    }

    private static final PermView ALL = new PermView() {
        @Override
        public boolean has(String node) {
            return true;
        }

        @Override
        public int level() {
            return Integer.MAX_VALUE;
        }
    };

    public boolean isPlayer() {
        return kind == Kind.PLAYER;
    }

    public boolean isPrivileged() {
        return kind != Kind.PLAYER;
    }

    public boolean has(String node) {
        return perms.has(node);
    }

    public int level() {
        return perms.level();
    }

    /** UUID stored in the database; console has none. */
    public UUID dbUuid() {
        return kind == Kind.PLAYER ? uuid : null;
    }
}
