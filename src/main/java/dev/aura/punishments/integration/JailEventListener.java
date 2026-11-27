package dev.aura.punishments.integration;

import com.graywolf336.jail.events.PrisonerReleasedEvent;
import dev.aura.punishments.service.CustodyService;
import dev.aura.punishments.service.JailService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Keeps custody records truthful when JailReloaded releases someone (timer, login completion or a direct command). */
public final class JailEventListener implements Listener {
    private final CustodyService custody;
    private final JailService jails;

    public JailEventListener(CustodyService custody, JailService jails) {
        this.custody = custody;
        this.jails = jails;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReleased(PrisonerReleasedEvent e) {
        String id = e.getPrisoner().ensureJailId();
        custody.onPrisonerReleased(e.getPrisoner().getUUID(), id);
        jails.onPrisonerReleased(e.getPrisoner().getUUID(), id);
    }
}
