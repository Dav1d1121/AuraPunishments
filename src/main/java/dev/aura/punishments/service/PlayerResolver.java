package dev.aura.punishments.service;

import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.Records.Profile;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Safe player resolution. Only players who have actually joined this server can be targeted, and
 * UUIDs are never fabricated from a typed name (Bukkit's offline-UUID fallback is deliberately
 * not used). Ambiguous names are rejected; staff can then use the UUID.
 */
public final class PlayerResolver extends Svc {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]{1,32}");

    public record Target(UUID uuid, String name, boolean online) {}

    public PlayerResolver(Core core) {
        super(core);
    }

    /** Must be called on the main thread (reads online players). */
    public CompletableFuture<Result<Target>> resolve(String input) {
        if (input == null || input.isBlank()) return done(Result.fail(Code.INVALID, "Specify a player."));
        String q = input.trim();
        UUID asUuid = parseUuid(q);
        if (asUuid != null) {
            Player p = Bukkit.getPlayer(asUuid);
            if (p != null) return done(Result.ok(new Target(p.getUniqueId(), p.getName(), true)));
            return db(c -> {
                Profile pr = Repo.Profiles.get(c, asUuid);
                if (pr != null) return Result.ok(new Target(pr.uuid(), pr.name(), false));
                return Result.<Target>fail(Code.NOT_FOUND, "No player with that UUID has joined this server.");
            });
        }
        if (!NAME.matcher(q).matches()) return done(Result.fail(Code.INVALID, "That is not a valid player name."));
        Player online = Bukkit.getPlayerExact(q);
        if (online != null) return done(Result.ok(new Target(online.getUniqueId(), online.getName(), true)));

        return db(c -> {
            List<Profile> found = Repo.Profiles.byName(c, q);
            if (found.size() == 1) {
                Profile pr = found.get(0);
                return Result.ok(new Target(pr.uuid(), pr.name(), false));
            }
            if (found.size() > 1) {
                return Result.<Target>fail(Code.INVALID, "More than one known player used the name '" + q
                        + "'. Use the player's UUID instead.");
            }
            return Result.<Target>fail(Code.NOT_FOUND, "Unknown player '" + q + "'. Only players who have joined this server can be targeted.");
        }).thenCompose(r -> {
            if (r.isOk() || r.code() != Code.NOT_FOUND) return done(r);
            // Fall back to the server's own user cache (players who joined before this plugin was installed).
            return core.main.call(() -> {
                OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(q);
                if (op != null && op.hasPlayedBefore() && op.getName() != null) {
                    return Result.ok(new Target(op.getUniqueId(), op.getName(), op.isOnline()));
                }
                return r;
            });
        });
    }

    private static UUID parseUuid(String s) {
        if (s.length() != 36 && s.length() != 32) return null;
        try {
            if (s.length() == 32) {
                s = s.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5");
            }
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Record that a player joined (name, last IP, authority level). Fire-and-forget on the DB thread. */
    public CompletableFuture<Void> recordJoin(UUID uuid, String name, String ip, int level) {
        return core.db.tx(c -> {
            Repo.Profiles.upsert(c, uuid, name, ip, level, core.clock.now());
            return null;
        }).handle((v, ex) -> {
            if (ex != null) core.log.warning("Could not record profile for " + name + ": " + ex.getMessage());
            return null;
        });
    }

    public CompletableFuture<Void> recordLevel(UUID uuid, int level) {
        return core.db.tx(c -> {
            Repo.Profiles.updateLevel(c, uuid, level, core.clock.now());
            return null;
        }).handle((v, ex) -> null);
    }

    public CompletableFuture<List<String>> suggest(String prefix, int limit) {
        return core.db.tx(c -> Repo.Profiles.byPrefix(c, prefix, limit).stream().map(Profile::name).toList())
                .handle((v, ex) -> ex == null ? v : List.<String>of());
    }
}
