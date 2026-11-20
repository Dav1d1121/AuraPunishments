package dev.aura.punishments.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing and formatting of human durations such as "1d12h" or "30m". Values are milliseconds. */
public final class Durations {
    private Durations() {}

    public static final long SECOND = 1000L;
    public static final long MINUTE = 60 * SECOND;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;
    public static final long WEEK = 7 * DAY;
    public static final long MONTH = 30 * DAY;
    public static final long YEAR = 365 * DAY;

    /** Sentinel meaning "no end". */
    public static final long PERMANENT = Long.MAX_VALUE;

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(years?|y|months?|mo|weeks?|w|days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)");

    public static boolean isPermanentWord(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.equals("perm") || t.equals("permanent") || t.equals("forever") || t.equals("permanently") || t.equals("-1");
    }

    /** @return milliseconds, {@link #PERMANENT}, or -2 when the text is not a valid duration. */
    public static long parse(String text) {
        if (text == null) return -2;
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return -2;
        if (isPermanentWord(t)) return PERMANENT;
        Matcher m = PART.matcher(t);
        long total = 0;
        int end = 0;
        boolean any = false;
        while (m.find()) {
            if (!t.substring(end, m.start()).isBlank()) return -2;
            end = m.end();
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -2;
            }
            long unit = unitOf(m.group(2));
            if (n > Long.MAX_VALUE / unit) return -2;
            long add = n * unit;
            if (total > PERMANENT - add - 1) return -2;
            total += add;
            any = true;
        }
        if (!any || !t.substring(end).isBlank()) return -2;
        return total <= 0 ? -2 : total;
    }

    private static long unitOf(String u) {
        if (u.startsWith("mo")) return MONTH;
        if (u.startsWith("y")) return YEAR;
        if (u.startsWith("w")) return WEEK;
        if (u.startsWith("d")) return DAY;
        if (u.startsWith("h")) return HOUR;
        if (u.startsWith("m")) return MINUTE;
        return SECOND;
    }

    public static String format(long ms) {
        if (ms == PERMANENT) return "permanent";
        if (ms <= 0) return "0s";
        StringBuilder sb = new StringBuilder();
        long rest = ms;
        long[] units = {YEAR, MONTH, WEEK, DAY, HOUR, MINUTE, SECOND};
        String[] names = {"y", "mo", "w", "d", "h", "m", "s"};
        int shown = 0;
        for (int i = 0; i < units.length && shown < 3; i++) {
            long n = rest / units[i];
            if (n > 0) {
                sb.append(n).append(names[i]);
                rest -= n * units[i];
                shown++;
                if (shown > 0 && i >= 5) break;
            }
        }
        return sb.isEmpty() ? "0s" : sb.toString();
    }
}
