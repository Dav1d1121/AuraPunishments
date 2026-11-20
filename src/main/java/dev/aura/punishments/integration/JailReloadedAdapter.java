package dev.aura.punishments.integration;

import com.graywolf336.jail.JailMain;
import com.graywolf336.jail.JailManager;
import com.graywolf336.jail.JailsAPI;
import com.graywolf336.jail.PrisonerManager;
import com.graywolf336.jail.beans.Cell;
import com.graywolf336.jail.beans.Jail;
import com.graywolf336.jail.beans.Prisoner;
import com.graywolf336.jail.enums.Settings;
import com.graywolf336.jail.events.PrePrisonerJailedEvent;
import com.graywolf336.jail.exceptions.PrisonerAlreadyJailedException;
import com.graywolf336.jail.interfaces.ICell;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The real JailReloaded integration, built only on JailReloaded's public classes
 * ({@code JailsAPI}, {@code PrisonerManager}, {@code JailManager}, its events), mirroring exactly what
 * its own /jail and /unjail commands do. Verified against JailReloaded 1.2.2 (see docs/INTEGRATION_REPORT.md).
 *
 * <p>Rules this adapter enforces:
 * <ul>
 *   <li>Every result is verified against JailReloaded's real prisoner records before success is reported.</li>
 *   <li>A release or time change touches ONLY the record whose id (JailReloaded's persistent jail id) was
 *       stored for the case. A different record for the same player is reported as NOT_OURS and left alone.</li>
 *   <li>Main thread only.</li>
 * </ul>
 */
public final class JailReloadedAdapter implements JailPort {
    private final JailMain plugin;
    private final Logger log;
    private final String version;

    JailReloadedAdapter(JailMain plugin, String version, Logger log) {
        this.plugin = plugin;
        this.version = version;
        this.log = log;
    }

    private JailManager jails() {
        return JailsAPI.getJailManager();
    }

    private PrisonerManager prisoners() {
        return JailsAPI.getPrisonerManager();
    }

    @Override
    public boolean available() {
        return plugin.isEnabled();
    }

    @Override
    public String status() {
        return plugin.isEnabled() ? "JailReloaded " + version + " (compatible)" : "JailReloaded " + version + " is disabled";
    }

    // ------------------------------------------------------------------ jail
    @Override
    public Outcome jail(JailRequest r) {
        try {
            JailManager jm = jails();
            if (jm.isPlayerJailed(r.uuid())) {
                Prisoner existing = jm.getPrisoner(r.uuid());
                return new Outcome(State.ALREADY_JAILED, existing == null ? null : existing.ensureJailId(), null,
                        r.name() + " is already jailed in JailReloaded.");
            }
            Player player = Bukkit.getPlayer(r.uuid());
            if (player != null && player.hasPermission("jail.cantbejailed")) {
                return Outcome.of(State.REFUSED, r.name() + " cannot be jailed (permission jail.cantbejailed).");
            }
            if (player == null) {
                if (!plugin.getConfig().getBoolean(Settings.ALLOWJAILINGOFFLINEPLAYERS.getPath())) {
                    return Outcome.of(State.REFUSED, "JailReloaded is configured to refuse jailing offline players ("
                            + Settings.ALLOWJAILINGOFFLINEPLAYERS.getPath() + ").");
                }
                if (!Bukkit.getOfflinePlayer(r.uuid()).hasPlayedBefore()
                        && !plugin.getConfig().getBoolean(Settings.ALLOWJAILINGNEVERPLAYEDBEFOREPLAYERS.getPath())) {
                    return Outcome.of(State.REFUSED, r.name() + " has never played on this server and JailReloaded refuses to jail them.");
                }
            }
            Jail jail = pickJail(jm, r.jailName());
            if (jail == null) {
                return Outcome.of(State.REFUSED, r.jailName() == null || r.jailName().isBlank()
                        ? "No usable jail exists in JailReloaded. Create one with /jail create."
                        : "JailReloaded has no jail named '" + r.jailName() + "'.");
            }
            String unusable = jm.unusableMessage(jail);
            if (unusable != null) return Outcome.of(State.REFUSED, "Jail '" + jail.getName() + "' cannot be used: " + unusable);

            Cell cell = null;
            if (r.cellName() != null && !r.cellName().isBlank()) {
                if (!jail.isValidCell(r.cellName())) return Outcome.of(State.REFUSED, "Jail '" + jail.getName() + "' has no cell '" + r.cellName() + "'.");
                cell = jail.getCell(r.cellName());
                if (cell.hasPrisoner()) return Outcome.of(State.REFUSED, "Cell '" + r.cellName() + "' is already occupied.");
            } else if (jail.hasCells() && plugin.getConfig().getBoolean(Settings.AUTOMATICCELL.getPath(), true)) {
                cell = jail.getFirstEmptyCell();
                if (cell == null) return Outcome.of(State.REFUSED, "Jail '" + jail.getName() + "' has no empty cell.");
            }

            String reason = "[" + r.tag() + "] " + (r.reason() == null ? "" : r.reason());
            Prisoner prisoner = new Prisoner(r.uuid().toString(), r.name(), r.mute(), r.durationMs(), r.jailer(), reason);
            PrePrisonerJailedEvent pre = new PrePrisonerJailedEvent(jail, cell, prisoner, player, player == null, prisoner.getJailer());
            Bukkit.getPluginManager().callEvent(pre);
            if (pre.isCancelled()) {
                String msg = pre.getCancelledMessage();
                return Outcome.of(State.REFUSED, "Another plugin vetoed the jailing" + (msg == null || msg.isBlank() ? "." : ": " + msg));
            }
            try {
                prisoners().prepareJail(pre.getJail(), pre.getCell(), pre.getPlayer(), pre.getPrisoner());
            } catch (PrisonerAlreadyJailedException e) {
                return Outcome.of(State.ALREADY_JAILED, r.name() + " is already jailed in JailReloaded.");
            } catch (IllegalStateException e) {
                return Outcome.of(State.ERROR, "JailReloaded could not save the jailing: " + e.getMessage());
            }
            // Verify against JailReloaded's real records.
            Prisoner held = jm.getPrisoner(r.uuid());
            if (held == null) return Outcome.of(State.ERROR, "JailReloaded accepted the request but no prisoner record exists.");
            Jail in = jm.getJailPrisonerIsIn(held);
            String id = held.ensureJailId();
            return new Outcome(player == null ? State.JAILED_PENDING_PLACEMENT : State.JAILED, id, in == null ? jail.getName() : in.getName(),
                    player == null ? "Registered; the player is placed when they next log in." : "Jailed.");
        } catch (Exception e) {
            log.warning("JailReloaded jail() failed: " + e);
            return Outcome.of(State.ERROR, "JailReloaded error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Jail pickJail(JailManager jm, String requested) {
        if (requested != null && !requested.isBlank()) return jm.getJail(requested);
        String def = plugin.getConfig().getString(Settings.DEFAULTJAIL.getPath());
        if (def != null && !def.isBlank() && !def.equalsIgnoreCase("nearest") && jm.isValidJail(def)) return jm.getJail(def);
        for (Jail j : jm.getJails()) {
            if (jm.unusableMessage(j) == null) return j;
        }
        return null;
    }

    // ------------------------------------------------------------------ release
    @Override
    public Outcome release(UUID uuid, String expectedId, String actorName) {
        try {
            JailManager jm = jails();
            Prisoner pr = jm.getPrisoner(uuid);
            if (pr == null) return Outcome.of(State.NOT_JAILED, "Not held in JailReloaded.");
            String id = pr.ensureJailId();
            if (expectedId != null && !expectedId.equals(id)) {
                return Outcome.of(State.NOT_OURS, "The JailReloaded record for this player is not the one created for the case; it was left untouched.");
            }
            Jail jail = jm.getJailPrisonerIsIn(pr);
            if (jail == null) return Outcome.of(State.ERROR, "The prisoner's jail could not be found.");
            CommandSender console = Bukkit.getConsoleSender();
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                plugin.console().rememberRelease(pr, console, "admin_release");
                prisoners().unJail(jail, jail.getCellPrisonerIsIn(uuid), player, pr, console);
                // unJail may finish after a teleport; the record is gone when it is complete.
                return jm.getPrisoner(uuid) == null ? Outcome.of(State.RELEASED, "Released.")
                        : Outcome.of(State.RELEASE_PENDING, "Release in progress.");
            }
            if (pr.isOfflinePending() && pr.getRemainingTime() != 0) {
                // Never placed (still pending): nothing to restore, remove the record.
                prisoners().forceUnJail(jail, jail.getCellPrisonerIsIn(uuid), null, pr, console);
                return jm.getPrisoner(uuid) == null ? Outcome.of(State.RELEASED, "Released (was never placed).")
                        : Outcome.of(State.ERROR, "JailReloaded did not remove the pending record.");
            }
            // Offline and placed: exactly what /unjail does - completes at the player's next login.
            plugin.console().rememberRelease(pr, console, "admin_release");
            long oldTime = pr.getRemainingTime();
            boolean oldPending = pr.isOfflinePending();
            pr.setRemainingTime(0);
            pr.setOfflinePending(true);
            try {
                plugin.getJailIO().saveJailsStrict(jail);
            } catch (RuntimeException e) {
                pr.setRemainingTime(oldTime);
                pr.setOfflinePending(oldPending);
                return Outcome.of(State.ERROR, "JailReloaded could not save the release: " + e.getMessage());
            }
            return Outcome.of(State.RELEASE_PENDING, "Release accepted; it completes when the player next logs in.");
        } catch (Exception e) {
            log.warning("JailReloaded release() failed: " + e);
            return Outcome.of(State.ERROR, "JailReloaded error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ time change (pretrial -> sentence)
    @Override
    public Outcome setRemaining(UUID uuid, String expectedId, long remainingMs, String actorName) {
        try {
            if (remainingMs <= 0) return Outcome.of(State.ERROR, "Remaining time must be positive.");
            JailManager jm = jails();
            Prisoner pr = jm.getPrisoner(uuid);
            if (pr == null) return Outcome.of(State.NOT_JAILED, "Not held in JailReloaded.");
            String id = pr.ensureJailId();
            if (expectedId != null && !expectedId.equals(id)) {
                return Outcome.of(State.NOT_OURS, "The JailReloaded record for this player is not the one created for the case; it was left untouched.");
            }
            Jail jail = jm.getJailPrisonerIsIn(pr);
            if (jail == null) return Outcome.of(State.ERROR, "The prisoner's jail could not be found.");
            long old = pr.getRemainingTime();
            pr.setRemainingTime(remainingMs);
            try {
                plugin.getJailIO().saveJailsStrict(jail);
            } catch (RuntimeException e) {
                pr.setRemainingTime(old);
                return Outcome.of(State.ERROR, "JailReloaded could not save the new time: " + e.getMessage());
            }
            if (pr.isJailedForever() || Math.abs(pr.getRemainingTime() - remainingMs) > 60_000) {
                return Outcome.of(State.ERROR, "JailReloaded did not apply the new sentence time.");
            }
            return new Outcome(State.UPDATED, id, jail.getName(), "Sentence time set.");
        } catch (Exception e) {
            log.warning("JailReloaded setRemaining() failed: " + e);
            return Outcome.of(State.ERROR, "JailReloaded error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ queries
    @Override
    public Optional<PrisonerState> find(UUID uuid) {
        try {
            Prisoner pr = jails().getPrisoner(uuid);
            return pr == null ? Optional.empty() : Optional.of(state(pr));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public List<PrisonerState> allPrisoners() {
        List<PrisonerState> out = new ArrayList<>();
        try {
            for (Prisoner pr : jails().getAllPrisoners().values()) out.add(state(pr));
        } catch (Exception e) {
            log.warning("Could not read JailReloaded prisoners: " + e);
        }
        return out;
    }

    private PrisonerState state(Prisoner pr) {
        Jail jail = jails().getJailPrisonerIsIn(pr);
        return new PrisonerState(pr.getUUID(), pr.getLastKnownName(), pr.ensureJailId(), jail == null ? null : jail.getName(),
                pr.getRemainingTime(), pr.isJailedForever(), pr.isOfflinePending(), pr.getReason(), pr.getJailer());
    }

    @Override
    public List<String> jailNames() {
        try {
            return List.of(jails().getJailNames());
        } catch (Exception e) {
            return List.of();
        }
    }

    public String version() {
        return version;
    }
}
