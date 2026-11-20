package dev.aura.punishments.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * messages.yml access. Values are MiniMessage. Placeholders passed from code are UNPARSED, so a
 * player name or a typed reason can never inject formatting or click events. A key missing from the
 * server's file falls back to the bundled default, so upgrades never show raw keys.
 */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlConfiguration user;
    private final YamlConfiguration defaults;

    public Messages(YamlConfiguration user, YamlConfiguration defaults) {
        this.user = user;
        this.defaults = defaults;
    }

    /** Validate that every message parses as MiniMessage. */
    public static Messages parse(YamlConfiguration user, YamlConfiguration defaults) throws ConfigException {
        List<String> problems = new ArrayList<>();
        for (String key : user.getKeys(true)) {
            Object v = user.get(key);
            if (v instanceof String s) {
                try {
                    MM.deserialize(s.replace("<prefix>", ""), TagResolver.empty());
                } catch (RuntimeException e) {
                    problems.add("messages.yml " + key + ": not valid MiniMessage (" + e.getMessage() + ")");
                }
            }
        }
        if (!problems.isEmpty()) throw new ConfigException(problems);
        return new Messages(user, defaults);
    }

    public String raw(String key) {
        String v = user.getString(key);
        if (v == null) v = defaults.getString(key);
        return v == null ? "<red>[missing message: " + key + "]</red>" : v;
    }

    public List<String> rawList(String key) {
        List<String> l = user.isList(key) ? user.getStringList(key) : defaults.getStringList(key);
        if (l.isEmpty()) {
            String single = user.getString(key, defaults.getString(key));
            if (single != null) return List.of(single);
        }
        return l;
    }

    private TagResolver resolver(String[] kv) {
        TagResolver.Builder b = TagResolver.builder();
        b.resolver(Placeholder.parsed("prefix", rawOrEmpty("prefix")));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.resolver(Placeholder.unparsed(kv[i], kv[i + 1] == null ? "" : kv[i + 1]));
        }
        return b.build();
    }

    private String rawOrEmpty(String key) {
        String v = user.getString(key, defaults.getString(key, ""));
        return v == null ? "" : v;
    }

    /** @param kv alternating placeholder name / value pairs */
    public Component get(String key, String... kv) {
        return MM.deserialize(raw(key), resolver(kv));
    }

    public List<Component> lines(String key, String... kv) {
        List<Component> out = new ArrayList<>();
        for (String l : rawList(key)) out.add(MM.deserialize(l, resolver(kv)));
        return out;
    }

    /** Join a multi-line message into one component with newlines (for kick/disconnect screens). */
    public Component screen(String key, String... kv) {
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines(key, kv));
    }

    public void send(Audience to, String key, String... kv) {
        to.sendMessage(get(key, kv));
    }

    /** Plain error/info text coming from services, shown with the standard prefix. */
    public void sendError(Audience to, String text) {
        to.sendMessage(get("error", "message", text));
    }

    public void sendInfo(Audience to, String text) {
        to.sendMessage(get("info", "message", text));
    }

    public static MiniMessage mini() {
        return MM;
    }
}
