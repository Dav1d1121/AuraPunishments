package dev.aura.punishments.integration;

import dev.aura.punishments.service.CoreProtectPort;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.api.result.MessageResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** The only class that touches CoreProtect's API. Only lookups are called: see {@link CoreProtectPort}. */
public final class CoreProtectBridge implements CoreProtectPort {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private final CoreProtectAPI api;

    private CoreProtectBridge(CoreProtectAPI api) {
        this.api = api;
    }

    /** @return null when CoreProtect is missing, disabled or exposes an incompatible API (never throws). */
    public static CoreProtectBridge create() {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("CoreProtect");
            if (!(p instanceof CoreProtect cp) || !p.isEnabled()) return null;
            CoreProtectAPI api = cp.getAPI();
            if (api == null || !api.isEnabled() || api.APIVersion() < 9) return null;
            return new CoreProtectBridge(api);
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean available() {
        try {
            return api.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String status() {
        return available() ? "CoreProtect API v" + api.APIVersion() + " (read-only)" : "CoreProtect API not enabled";
    }

    @Override
    public Result lookup(Query q) {
        int seconds = (int) Math.min(Integer.MAX_VALUE, q.seconds());
        List<String> lines = new ArrayList<>();
        int total;
        switch (q.kind()) {
            case BLOCKS -> {
                World w = Bukkit.getWorld(q.world() == null ? "" : q.world());
                if (w == null) throw new IllegalArgumentException("unknown world");
                List<String[]> rows = api.performLookup(seconds, List.of(q.player()), null, null, null, List.of(0, 1), q.radius(), new Location(w, q.x(), q.y(), q.z()));
                total = rows == null ? 0 : rows.size();
                if (rows != null) for (String[] r : rows) {
                    CoreProtectAPI.ParseResult pr = api.parseResult(r);
                    lines.add(TS.format(Instant.ofEpochMilli(pr.getTimestamp())) + " " + pr.getActionString() + " " + pr.getType().name().toLowerCase()
                            + " @" + pr.worldName() + " " + pr.getX() + "," + pr.getY() + "," + pr.getZ() + (pr.isRolledBack() ? " (rolled back)" : ""));
                }
            }
            case CHAT, COMMANDS -> {
                List<MessageResult> rows = q.kind() == Kind.CHAT ? api.chatLookup(q.player(), seconds) : api.commandLookup(q.player(), seconds);
                total = rows == null ? 0 : rows.size();
                if (rows != null) for (MessageResult m : rows) lines.add(TS.format(Instant.ofEpochMilli(m.getTimestamp())) + " " + m.getMessage().replace('\n', ' '));
            }
            case SESSIONS -> {
                List<String[]> rows = api.sessionLookup(q.player(), seconds);
                total = rows == null ? 0 : rows.size();
                if (rows != null) for (String[] r : rows) {
                    CoreProtectAPI.ParseResult pr = api.parseResult(r);
                    lines.add(TS.format(Instant.ofEpochMilli(pr.getTimestamp())) + " " + pr.getActionString() + " @" + pr.worldName());
                }
            }
            default -> total = 0;
        }
        return new Result(lines, total, false);
    }
}
