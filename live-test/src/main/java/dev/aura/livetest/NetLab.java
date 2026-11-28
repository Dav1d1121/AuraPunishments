package dev.aura.livetest;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The multi-server lab's hands: a console command ({@code netlab <id> <op> ...}) that makes THIS backend open real protocol clients
 * (through the proxy), type chat and commands as them and report what they saw. Every answer is one line in plugins/APLiveTest/net.log:
 * {@code <id>\t<OK|FAIL>\t<text>}; the orchestrator (tools/network-lab/lab.py) reads it. Nothing here touches AuraPunishments itself - the
 * plugin is exercised only through what a player or the console can do.
 */
final class NetLab implements CommandExecutor {
    private final JavaPlugin plugin;
    private final File log;
    private final Map<String, McClient> bots = new ConcurrentHashMap<>();

    NetLab(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = new File(plugin.getDataFolder(), "net.log");
    }

    void start() {
        plugin.getCommand("netlab").setExecutor(this);
        answer("ready", "OK", "NetLab on " + Bukkit.getVersion() + " port " + Bukkit.getPort());
    }

    static UUID offlineId(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void answer(String id, String status, String text) {
        try {
            Files.writeString(log.toPath(), id + "\t" + status + "\t" + text.replace('\n', ' ') + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            plugin.getLogger().warning("net.log: " + e);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length < 2) return false;
        String id = a[0];
        String op = a[1].toLowerCase();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                handle(id, op, a);
            } catch (Throwable t) {
                answer(id, "FAIL", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
        return true;
    }

    private String rest(String[] a, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(a, from, a.length));
    }

    private void handle(String id, String op, String[] a) throws Exception {
        switch (op) {
            case "join" -> { // join <name> <host> <port>
                McClient c = McClient.connect(a[2], offlineId(a[2]), a[3], Integer.parseInt(a[4]));
                bots.put(a[2], c);
                answer(id, "OK", "joined " + a[2]);
            }
            case "say" -> {
                bots.get(a[2]).chat(rest(a, 3));
                answer(id, "OK", "said");
            }
            case "cmd" -> {
                bots.get(a[2]).command(rest(a, 3));
                answer(id, "OK", "ran");
            }
            case "quit" -> {
                McClient c = bots.remove(a[2]);
                if (c != null) c.close();
                answer(id, "OK", "closed");
            }
            case "info" -> {
                McClient c = bots.get(a[2]);
                answer(id, "OK", c == null ? "no such bot" : "playing=" + c.playing + " switches=" + c.switches + " disconnected=" + c.disconnected + " packets=" + c.packets.size());
            }
            case "saw" -> {
                McClient c = bots.get(a[2]);
                answer(id, "OK", c != null && c.saw(rest(a, 3)) ? "YES" : "NO");
            }
            case "dump" -> {
                McClient c = bots.get(a[2]);
                var s = c == null ? java.util.List.<String>of() : c.snapshot();
                answer(id, "OK", String.join(" || ", s.subList(Math.max(0, s.size() - 12), s.size())));
            }
            case "clear" -> {
                McClient c = bots.get(a[2]);
                if (c != null) c.clear();
                answer(id, "OK", "cleared");
            }
            case "here" -> { // is this player connected to THIS backend?
                Player p = Bukkit.getPlayerExact(a[2]);
                answer(id, "OK", p == null ? "NO" : "YES");
            }
            case "setupjail" -> { // build the jail JailReloaded needs (the jail backend only): setupjail <name>
                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    com.graywolf336.jail.JailMain jm = (com.graywolf336.jail.JailMain) Bukkit.getPluginManager().getPlugin("JailReloaded");
                    org.bukkit.World w = Bukkit.getWorlds().get(0);
                    if (jm.getJailManager().getJail(a[2]) == null) {
                        com.graywolf336.jail.beans.Jail j = new com.graywolf336.jail.beans.Jail(jm, a[2]);
                        j.setBounds(w.getName(), -10, 60, -10, 10, 90, 10);
                        j.setTeleportIn(new org.bukkit.Location(w, 0, 70, 0));
                        j.setTeleportFree(new org.bukkit.Location(w, 20, 70, 20));
                        j.setEnabled(true);
                        jm.getJailManager().addJail(j, true);
                    }
                    jm.getConfig().set(com.graywolf336.jail.enums.Settings.ALLOWJAILINGNEVERPLAYEDBEFOREPLAYERS.getPath(), true);
                    return null;
                }).get();
                answer(id, "OK", "jail " + a[2] + " ready");
            }
            case "prisoner" -> { // is this player a JailReloaded prisoner on THIS backend?
                Boolean held = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    var jm = com.graywolf336.jail.JailsAPI.getJailManager();
                    return jm != null && jm.getPrisoner(offlineId(a[2])) != null;
                }).get();
                answer(id, "OK", held ? "YES" : "NO");
            }
            case "ping" -> answer(id, "OK", "pong " + Bukkit.getOnlinePlayers().size());
            default -> answer(id, "FAIL", "unknown op " + op);
        }
    }
}
