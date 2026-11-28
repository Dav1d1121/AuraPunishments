package dev.aura.punishments.gui;

import dev.aura.punishments.AuraPunishmentsPlugin;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.support.FakeJail;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** MockBukkit fixture: a server with the plugin loaded, a fake JailReloaded and helpers to drive the GUI. */
public final class McFx implements AutoCloseable {
    public final ServerMock server;
    public final AuraPunishmentsPlugin plugin;
    public final Gui gui;
    public final FakeJail jail = new FakeJail();
    /** The text-entry dialog each player currently has open (what the native Minecraft dialog would show). */
    public final java.util.Map<java.util.UUID, PromptManager.Request> dialogs = new java.util.concurrent.ConcurrentHashMap<>();

    /** The player types into the dialog's text box and presses Submit. */
    public void answer(org.bukkit.entity.Player p, String text) {
        PromptManager.Request r = dialogs.remove(p.getUniqueId()); // a dialog closes when a button is pressed
        if (r == null) throw new AssertionError("no dialog is open for " + p.getName());
        r.onSubmit().accept(text);
    }

    /** The player presses Cancel in the dialog. */
    public void pressCancel(org.bukkit.entity.Player p) {
        PromptManager.Request r = dialogs.remove(p.getUniqueId());
        if (r == null) throw new AssertionError("no dialog is open for " + p.getName());
        r.onCancel().run();
    }

    /** The judicial system on, as in an upgraded 1.0-1.2 install (the shipped file for a FRESH install has it off). */
    public McFx() {
        this(true);
    }

    /** The judicial system off and the case workflow on: the shipped file exactly as a fresh install receives it (1.3.1). */
    public static McFx casework() {
        return new McFx(false, true);
    }

    /** @param judicial true = config with judicial.enabled: true; false = judicial off and the case workflow off (the 1.3.0 "plain off" mode) */
    public McFx(boolean judicial) {
        this(judicial, false);
    }

    private McFx(boolean judicial, boolean casework) {
        server = MockBukkit.mock();
        String version = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(McFx.class.getResourceAsStream("/plugin.yml"), java.nio.charset.StandardCharsets.UTF_8)).getString("version");
        File data = new File(server.getPluginsFolder(), "AuraPunishments-" + version); // MockBukkit names the data folder <name>-<version>
        deleteRecursively(data);
        deleteRecursively(new File(server.getPluginsFolder(), "AuraPunishments"));
        if (judicial || !casework) {
            try (var in = McFx.class.getResourceAsStream("/config.yml")) {
                String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                String patched = text;
                if (judicial) {
                    patched = patched.replace("\n  enabled: false\n  # May the staff member", "\n  enabled: true\n  # May the staff member");
                    if (patched.equals(text)) throw new IllegalStateException("shipped config.yml has no judicial.enabled line to switch on");
                }
                String before = patched;
                patched = patched.replace("\ncasework:\n  enabled: true\n", "\ncasework:\n  enabled: false\n");
                if (patched.equals(before)) throw new IllegalStateException("shipped config.yml has no casework.enabled line");
                data.mkdirs();
                java.nio.file.Files.writeString(new File(data, "config.yml").toPath(), patched);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        plugin = MockBukkit.load(AuraPunishmentsPlugin.class);
        gui = plugin.gui();
        plugin.core().jail = jail;
        gui.prompts().presenter(new PromptManager.Presenter() {
            @Override public void show(org.bukkit.entity.Player p, PromptManager.Request r) { dialogs.put(p.getUniqueId(), r); }
            @Override public void close(org.bukkit.entity.Player p) { dialogs.remove(p.getUniqueId()); }
        });
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
    }

    public PlayerMock staff(String name, String... nodes) {
        PlayerMock p = server.addPlayer(name);
        for (String n : nodes) p.addAttachment(plugin, n, true);
        return p;
    }

    public static String[] basicStaff() {
        return new String[]{Perms.COMMAND, Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.CASE_CREATE, Perms.CASE_VIEW_ALL, Perms.EVIDENCE_ADD, Perms.EVIDENCE_VIEW,
                Perms.NOTE_VIEW, Perms.NOTE_ADD, Perms.HISTORY_VIEW, Perms.CUSTODY_INITIATE, Perms.CUSTODY_VIEW, Perms.punish(PunishType.MUTE),
                Perms.punish(PunishType.KICK), Perms.limit(PunishType.MUTE, "short"), Perms.LEVEL_PREFIX + "20"};
    }

    /** Pump the scheduler until the condition holds (DB work completes on another thread). */
    public boolean await(BooleanSupplier cond) {
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            server.getScheduler().performOneTick();
            if (cond.getAsBoolean()) return true;
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return cond.getAsBoolean();
    }

    /** Wait for a future while pumping the server scheduler (a real main thread is never blocked). */
    public <T> T join(java.util.concurrent.CompletableFuture<T> f) {
        long end = System.currentTimeMillis() + 15000;
        while (!f.isDone() && System.currentTimeMillis() < end) {
            server.getScheduler().performOneTick();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            return f.get(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future did not complete: " + e, e);
        }
    }

    public Menu menuOf(PlayerMock p) {
        return plugin.gui().manager().currentOf(p);
    }

    public boolean awaitMenu(PlayerMock p, Class<? extends Menu> type) {
        return await(() -> type.isInstance(menuOf(p)));
    }

    public String title(PlayerMock p) {
        InventoryView v = p.getOpenInventory();
        Component t = v.title();
        return PlainTextComponentSerializer.plainText().serialize(t);
    }

    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    public List<String> messages(PlayerMock p) {
        List<String> out = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) out.add(plain(c));
        return out;
    }

    public InventoryClickEvent click(PlayerMock p, int slot, ClickType type) {
        InventoryView view = p.getOpenInventory();
        InventoryAction action = type.isShiftClick() ? InventoryAction.MOVE_TO_OTHER_INVENTORY : InventoryAction.PICKUP_ALL;
        InventoryClickEvent e = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, type, action);
        server.getPluginManager().callEvent(e);
        return e;
    }

    public InventoryClickEvent click(PlayerMock p, int slot) {
        return click(p, slot, ClickType.LEFT);
    }

    public Inventory top(PlayerMock p) {
        return p.getOpenInventory().getTopInventory();
    }

    /** Register a command (like EssentialsX/TownyChat would) so alias resolution can be exercised. */
    public void registerCommand(String name, String... aliases) {
        Command c = new Command(name, "test", "/" + name, List.of(aliases)) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        server.getCommandMap().register("test", c);
    }

    @Override
    public void close() {
        MockBukkit.unmock();
    }
}
