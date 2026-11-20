package dev.aura.punishments.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class of every screen. A menu is its own {@link InventoryHolder}, which is how clicks are
 * attributed to it. Buttons are redrawn from scratch on every refresh and every click is validated by
 * {@link GuiManager} (current menu, cooldown, in-flight lock) and by the service layer (permissions).
 */
public abstract class Menu implements InventoryHolder {
    private static final AtomicLong IDS = new AtomicLong();

    @FunctionalInterface
    public interface Click {
        void on(Player p, ClickType type);
    }

    protected final Gui gui;
    protected final Player viewer;
    protected final Menu parent;
    private final long id = IDS.incrementAndGet();
    private final Map<Integer, Click> handlers = new HashMap<>();
    private Inventory inv;
    boolean closed;

    protected Menu(Gui gui, Player viewer, Menu parent) {
        this.gui = gui;
        this.viewer = viewer;
        this.parent = parent;
    }

    public long id() {
        return id;
    }

    protected abstract Component title();

    protected int rows() {
        return 6;
    }

    /** Fill the inventory via {@link #set}. Called on open and on every refresh. */
    protected abstract void render();

    @Override
    public Inventory getInventory() {
        if (inv == null) inv = Bukkit.createInventory(this, rows() * 9, title());
        return inv;
    }

    /** (Re)draw and show to the viewer. */
    public final void open() {
        if (!viewer.isOnline()) return;
        closed = false;
        redraw();
        gui.manager().show(viewer, this);
    }

    /** Redraw in place without reopening (keeps the cursor position). */
    public final void refresh() {
        if (closed || !viewer.isOnline()) return;
        redraw();
    }

    private void redraw() {
        handlers.clear();
        getInventory().clear();
        render();
    }

    protected final void set(int slot, ItemStack item, Click click) {
        if (slot < 0 || slot >= rows() * 9) return;
        getInventory().setItem(slot, item);
        if (click != null) handlers.put(slot, click);
    }

    protected final void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected final void fill(int from, int toInclusive) {
        for (int i = from; i <= toInclusive; i++) getInventory().setItem(i, Items.filler());
    }

    Click handler(int slot) {
        return handlers.get(slot);
    }

    /** Draw an action, hidden or locked (per config) when the viewer lacks permission. Services re-check on execution. */
    protected final void action(int slot, boolean allowed, Material m, String name, java.util.List<String> lore, Click click) {
        if (allowed) {
            java.util.List<Component> l = new java.util.ArrayList<>();
            for (String s : lore) l.add(Items.t(s));
            set(slot, Items.item(m, Items.t(name), l), click);
        } else if (!gui.settings().gui.hideLocked()) {
            set(slot, Items.item(Material.GRAY_DYE, Items.t("<dark_gray><st>" + name.replaceAll("<[^>]*>", "")),
                    java.util.List.of(Items.t("<red>Locked <gray>- not available to you"))),
                    (p, t) -> gui.messages().sendError(p, "You do not have permission for that action."));
        }
    }

    /** Standard bottom-row navigation: back (slot 45) and close (49). */
    protected final void navigation() {
        fill(45, 53);
        if (parent != null) {
            set(45, Items.item(Material.ARROW, "<yellow>Back", "<gray>Return to the previous screen"), (p, t) -> parent.open());
        }
        set(49, Items.item(Material.BARRIER, "<red>Close"), (p, t) -> p.closeInventory());
    }

    protected final void back() {
        if (parent != null) parent.open();
        else viewer.closeInventory();
    }
}
