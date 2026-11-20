package dev.aura.punishments.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes inventory events to menus and enforces the GUI's safety rules:
 * <ul>
 *   <li>every click/drag inside an AuraPunishments menu is cancelled - items can never be moved, taken,
 *       shift-clicked in, number-keyed or dropped;</li>
 *   <li>only the player's CURRENT menu accepts clicks (stale or duplicated windows are closed);</li>
 *   <li>per-player click cooldown and an in-flight lock stop duplicate/repeated actions;</li>
 *   <li>permissions are never trusted from the GUI - services re-check on execution.</li>
 * </ul>
 */
public final class GuiManager implements Listener {
    private final Gui gui;
    private final Map<UUID, Menu> current = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private final Map<UUID, Long> busySince = new ConcurrentHashMap<>();
    /** Safety valve: a lock older than this is released so a lost callback can never wedge a player. */
    private static final long BUSY_TIMEOUT_MS = 20_000;

    public GuiManager(Gui gui) {
        this.gui = gui;
    }

    void show(Player p, Menu m) {
        Menu old = current.put(p.getUniqueId(), m);
        if (old != null && old != m) old.closed = true;
        p.openInventory(m.getInventory());
        // openInventory fires a close event for the previous inventory (which can be this same menu being
        // refreshed); re-assert the current menu and its open state afterwards.
        m.closed = false;
        current.put(p.getUniqueId(), m);
    }

    public Menu currentOf(Player p) {
        return current.get(p.getUniqueId());
    }

    public boolean hasOpenMenu(Player p) {
        return current.containsKey(p.getUniqueId());
    }

    // ---- in-flight lock ------------------------------------------------------
    public boolean tryLock(Player p) {
        long now = System.currentTimeMillis();
        Long since = busySince.get(p.getUniqueId());
        if (since != null && now - since < BUSY_TIMEOUT_MS) return false;
        busySince.put(p.getUniqueId(), now);
        return true;
    }

    public void unlock(Player p) {
        busySince.remove(p.getUniqueId());
    }

    public boolean isBusy(Player p) {
        Long since = busySince.get(p.getUniqueId());
        return since != null && System.currentTimeMillis() - since < BUSY_TIMEOUT_MS;
    }

    public void closeAll() {
        for (UUID id : current.keySet()) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null) p.closeInventory();
        }
        current.clear();
    }

    /** Close a player's menu (permission revoked, plugin disabled, ...). */
    public void close(Player p) {
        if (current.containsKey(p.getUniqueId())) p.closeInventory();
    }

    // ---- events ---------------------------------------------------------------
    private static Menu holder(Inventory inv) {
        return inv != null && inv.getHolder(false) instanceof Menu m ? m : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        Menu top = holder(e.getView().getTopInventory());
        if (top == null) return;
        // Cancel EVERYTHING while one of our menus is on screen: clicks in the top inventory, shift-clicks
        // from the bottom inventory, number keys, double-click collect, drops, offhand swaps.
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Menu cur = current.get(p.getUniqueId());
        if (cur == null || cur != top || top.closed) {
            // Stale window (for example opened before the menu was replaced): close it.
            current.remove(p.getUniqueId(), top);
            gui.plugin().getServer().getScheduler().runTask(gui.plugin(), () -> p.closeInventory());
            return;
        }
        if (e.getClickedInventory() == null || holder(e.getClickedInventory()) != top) return; // click in own inventory: ignored
        if (e.getAction() == InventoryAction.NOTHING) return;
        int cooldown = gui.settings().gui.clickCooldownMs();
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < cooldown) return;
        lastClick.put(p.getUniqueId(), now);
        if (isBusy(p)) {
            gui.messages().send(p, "command.busy");
            return;
        }
        Menu.Click handler = top.handler(e.getSlot());
        if (handler == null) return;
        gui.sound(p, "click");
        try {
            handler.on(p, e.getClick());
        } catch (RuntimeException ex) {
            gui.plugin().getLogger().severe("GUI click failed: " + ex);
            gui.messages().sendError(p, "That action failed unexpectedly. Nothing was changed; see the console.");
            if (gui.settings().debug) ex.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (holder(e.getView().getTopInventory()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(InventoryInteractEvent e) {
        if (holder(e.getView().getTopInventory()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (holder(e.getSource()) != null || holder(e.getDestination()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (current.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Menu m = holder(e.getInventory());
        if (m == null || !(e.getPlayer() instanceof Player p)) return;
        // Only forget the menu if it is still the current one (a new menu may already have replaced it).
        if (current.get(p.getUniqueId()) == m) current.remove(p.getUniqueId());
        m.closed = true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        current.remove(e.getPlayer().getUniqueId());
        lastClick.remove(e.getPlayer().getUniqueId());
        busySince.remove(e.getPlayer().getUniqueId());
    }

    public Map<UUID, Menu> snapshot() {
        return Map.copyOf(current);
    }
}
