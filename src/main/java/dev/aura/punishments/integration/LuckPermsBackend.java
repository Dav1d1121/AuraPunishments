package dev.aura.punishments.integration;

import dev.aura.punishments.service.Actors;
import dev.aura.punishments.service.LevelResolver.TargetAuth;
import dev.aura.punishments.service.Perms;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** LuckPerms API usage, isolated so the plugin runs (with reduced offline protection) when LuckPerms is absent. */
public final class LuckPermsBackend implements BukkitLevelResolver.OfflineBackend {
    private final LuckPerms lp;
    private final List<EventSubscription<?>> subs = new ArrayList<>();

    private LuckPermsBackend(LuckPerms lp) {
        this.lp = lp;
    }

    public static LuckPermsBackend create() {
        var reg = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        return reg == null ? null : new LuckPermsBackend(reg.getProvider());
    }

    private CachedPermissionData data(User u) {
        return u.getCachedData().getPermissionData(lp.getContextManager().getStaticQueryOptions());
    }

    @Override
    public CompletableFuture<TargetAuth> auth(UUID uuid, String immunityNode) {
        return lp.getUserManager().loadUser(uuid).thenApply(u -> {
            CachedPermissionData d = data(u);
            int level = 0;
            for (Map.Entry<String, Boolean> e : d.getPermissionMap().entrySet()) {
                if (Boolean.TRUE.equals(e.getValue()) && e.getKey().startsWith(Perms.LEVEL_PREFIX)) level = Math.max(level, Actors.parseLevel(e.getKey()));
            }
            boolean immune = immunityNode != null && !immunityNode.isBlank() && d.checkPermission(immunityNode).asBoolean();
            return new TargetAuth(level, immune);
        });
    }

    @Override
    public CompletableFuture<Boolean> has(UUID uuid, String node) {
        return lp.getUserManager().loadUser(uuid).thenApply(u -> data(u).checkPermission(node).asBoolean());
    }

    /** Close open GUIs of players whose permissions just changed so they lose access immediately. */
    public void onPermissionChange(Plugin plugin, Consumer<Player> recheck) {
        subs.add(lp.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, ev -> {
            UUID id = ev.getUser().getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) recheck.accept(p);
            });
        }));
    }

    public void shutdown() {
        subs.forEach(EventSubscription::close);
        subs.clear();
    }
}
