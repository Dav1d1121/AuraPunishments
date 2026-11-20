package dev.aura.punishments.integration;

import com.graywolf336.jail.events.PrisonerReleasedEvent;
import dev.aura.punishments.service.CustodyService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Keeps custody records truthful when JailReloaded releases someone (timer, login completion or a direct command). */
public final class JailEventListener implements Listener {
    private final CustodyService custody;

    public JailEventListener(CustodyService custody) {
        this.custody = custody;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReleased(PrisonerReleasedEvent e) {
        custody.onPrisonerReleased(e.getPrisoner().getUUID(), e.getPrisoner().ensureJailId());
    }
}
