package dev.aura.punishments.service;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

/** Builds an {@link Actor} from a Bukkit sender. The permission view is live (never a snapshot). */
public final class Actors {
    private Actors() {}

    public static Actor of(CommandSender sender) {
        if (sender instanceof Player p) return of(p);
        return Actor.console();
    }

    public static Actor of(Player p) {
        return Actor.player(p.getUniqueId(), p.getName(), new Actor.PermView() {
            @Override
            public boolean has(String node) {
                return p.isOnline() && p.hasPermission(node);
            }

            @Override
            public int level() {
                return p.isOnline() ? levelOf(p) : 0;
            }
        });
    }

    /** Highest N among granted aurapunishments.level.N nodes. */
    public static int levelOf(Player p) {
        int best = 0;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission();
            if (perm.startsWith(Perms.LEVEL_PREFIX)) {
                best = Math.max(best, parseLevel(perm));
            }
        }
        return best;
    }

    public static int parseLevel(String node) {
        try {
            return Integer.parseInt(node.substring(Perms.LEVEL_PREFIX.length()));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
