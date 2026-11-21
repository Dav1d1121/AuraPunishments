package dev.aura.punishments.util;

import java.net.URI;
import java.util.Locale;

/** Input sanitisation shared by every text-entry path (GUI prompts, commands, evidence). */
public final class Text {
    private Text() {}

    /** Strip control characters and MiniMessage tag openers, collapse whitespace, trim. */
    public static String clean(String in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(in.length());
        boolean space = false;
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (Character.isISOControl(c) || c == '§' || Character.isWhitespace(c)) {
                if (!space && sb.length() > 0) sb.append(' ');
                space = true;
                continue;
            }
            space = false;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Only http(s) URLs with a host are accepted as link evidence. */
    public static boolean isHttpUrl(String s) {
        if (s == null || s.length() > 512) return false;
        try {
            URI u = new URI(s.trim());
            String scheme = u.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && u.getHost() != null && !u.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private static final java.time.format.DateTimeFormatter WHEN = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(java.time.ZoneId.systemDefault());

    /** Server-local date and time for player-facing messages. */
    public static String dateTime(long millis) {
        return WHEN.format(java.time.Instant.ofEpochMilli(millis));
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** Escape text so it cannot inject MiniMessage tags when placed in a message. */
    public static String escapeMini(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("<", "\\<");
    }
}
