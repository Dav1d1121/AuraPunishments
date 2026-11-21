package dev.aura.punishments.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.Records.Notification;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent in-game notifications for players (warnings, defendant communication).
 *
 * <p>A notification is queued inside the same transaction as the fact it reports, so it cannot be lost
 * and cannot exist without its cause. Delivery shows it to the player and only afterwards marks it
 * delivered; a player who is offline, or who disconnects mid-delivery, simply receives it on the next login.
 * Notifications are delivered oldest first with a configurable gap, one delivery loop per player at a time,
 * and the dedupe key makes a second queueing of the same fact a no-op, so nothing is shown twice.
 */
public final class NotificationService {
    public static final String WARNING = "WARNING";
    public static final String DEFENDANT = "DEFENDANT";

    private final Core core;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<UUID> rerun = ConcurrentHashMap.newKeySet();

    NotificationService(Core core) {
        this.core = core;
    }

    /** Queue a notification inside the caller's transaction. @return false when it was already queued. */
    public boolean enqueue(Connection c, String dedupeKey, UUID player, String kind, String refType, Long refId,
                           Map<String, String> payload) throws SQLException {
        JsonObject o = new JsonObject();
        payload.forEach(o::addProperty);
        return Repo.Notifications.enqueue(c, dedupeKey, player, kind, refType, refId, o.toString(), core.clock.now());
    }

    public static Map<String, String> payload(Notification n) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JsonObject o = JsonParser.parseString(n.payload()).getAsJsonObject();
            o.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().isJsonNull() ? "" : e.getValue().getAsString()));
        } catch (RuntimeException ignored) {
            // a damaged payload is shown with whatever could be read
        }
        return out;
    }

    /** Very old queued notices (other than warnings, which must always reach the player) are expired. */
    public void expireStale() {
        core.db.tx(c -> {
            Repo.Notifications.expireOlderThan(c, core.clock.now() - 30L * 86_400_000L);
            return Repo.Notifications.pruneDelivered(c, core.clock.now() - 90L * 86_400_000L);
        });
    }

    /**
     * Tell the accused about their case. Queued persistently (so an offline defendant sees it at the next login and a
     * restart loses nothing); {@code key} identifies the fact so it can never be queued - or shown - twice. These messages
     * report procedure; only a VERDICT/SENTENCE message states a finding.
     */
    public void defendant(dev.aura.punishments.model.Records.Case cs, String event, String detail, String key) {
        Settings.Defendant cfg = core.settings.defendant;
        if (!cfg.enabled() || cs.accusedUuid() == null) return;
        Map<String, String> pl = new LinkedHashMap<>();
        pl.put("event", event);
        pl.put("ref", cs.ref());
        pl.put("offense", core.offenses.get(cs.offenseKey()).map(o -> o.display()).orElse(cs.offenseKey()));
        pl.put("reason", dev.aura.punishments.util.Text.truncate(cs.description(), 300));
        pl.put("detail", detail == null ? "" : detail);
        pl.put("instructions", cfg.hearingInstructions());
        core.db.tx(c -> enqueue(c, "def:" + cs.id() + ":" + event + ":" + key, cs.accusedUuid(), DEFENDANT, "case", cs.id(), pl))
                .thenCompose(queued -> deliverPending(cs.accusedUuid()))
                .exceptionally(t -> {
                    core.log.warning("Could not queue a defendant notification for " + cs.ref() + ": " + t.getMessage());
                    return null;
                });
    }

    /** Deliver everything queued for this player if they are online. Safe to call at any time and from any thread. */
    public CompletableFuture<Void> deliverPending(UUID uuid) {
        rerun.add(uuid); // always request a pass first, so a loop that is finishing can never miss this request
        if (!inFlight.add(uuid)) return CompletableFuture.completedFuture(null);
        rerun.remove(uuid);
        return core.main.call(() -> core.enforcement.isOnline(uuid))
                .thenCompose(on -> !on ? CompletableFuture.<Void>completedFuture(null)
                        : core.db.tx(c -> Repo.Notifications.pendingFor(c, uuid)).thenCompose(list -> step(uuid, list, 0)))
                .exceptionally(t -> {
                    core.log.warning("Could not deliver notifications: " + t.getMessage());
                    return null;
                })
                .thenCompose(v -> {
                    inFlight.remove(uuid);
                    return rerun.contains(uuid) ? deliverPending(uuid) : CompletableFuture.<Void>completedFuture(null);
                });
    }

    private CompletableFuture<Void> step(UUID uuid, List<Notification> list, int i) {
        if (i >= list.size()) return CompletableFuture.completedFuture(null);
        Notification n = list.get(i);
        boolean late = core.clock.now() - n.createdAt() > 60_000;
        return core.main.call(() -> core.enforcement.deliverNotification(uuid, n, late)).handle((shown, ex) -> {
            if (ex != null) { // one bad notification must not block the player's whole queue
                core.log.warning("Notification #" + n.id() + " could not be shown: " + ex.getMessage());
                core.db.tx(c -> {
                    Repo.Notifications.noteAttempt(c, n.id());
                    if (n.attempts() + 1 >= 5 && !WARNING.equals(n.kind())) Repo.Notifications.expire(c, n.id());
                    return null;
                });
                return null;
            }
            return shown;
        }).thenCompose(shown -> {
            if (shown == null) return step(uuid, list, i + 1);
            if (!shown) return CompletableFuture.<Void>completedFuture(null); // went offline: stays queued for next login
            return core.db.tx(c -> {
                Repo.Notifications.markDelivered(c, n.id(), core.clock.now());
                if (WARNING.equals(n.kind()) && n.refId() != null) Repo.Punishments.markDelivered(c, n.refId());
                return null;
            }).thenCompose(x -> {
                if (i + 1 >= list.size()) return CompletableFuture.<Void>completedFuture(null);
                long gap = WARNING.equals(n.kind()) ? core.settings.warnings.spacingTicks() : 40;
                return core.main.later(gap).thenCompose(y -> step(uuid, list, i + 1));
            });
        });
    }
}
