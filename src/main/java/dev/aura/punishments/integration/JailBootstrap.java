package dev.aura.punishments.integration;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Detects JailReloaded and builds the port. This class deliberately references no JailReloaded class
 * directly, so a missing or incompatible JailReloaded produces an {@link UnavailableJailPort} with a
 * precise reason instead of a NoClassDefFoundError.
 */
public final class JailBootstrap {
    /** Oldest JailReloaded this plugin accepts; the version the integration was verified against is TESTED. */
    public static final int[] MIN = {1, 2, 0};
    public static final String TESTED = "1.2.2";

    private JailBootstrap() {}

    public static JailPort connect(Server server, Logger log, boolean enabled) {
        if (!enabled) return new UnavailableJailPort("disabled in config.yml (integrations.jailreloaded.enabled)");
        Plugin p = server.getPluginManager().getPlugin("JailReloaded");
        if (p == null) return new UnavailableJailPort("JailReloaded is not installed");
        if (!p.isEnabled()) return new UnavailableJailPort("JailReloaded is installed but failed to enable - check its startup log");
        String version = p.getPluginMeta().getVersion();
        int[] v = parse(version);
        if (v == null) return new UnavailableJailPort("cannot read JailReloaded version '" + version + "'");
        if (compare(v, MIN) < 0) {
            return new UnavailableJailPort("JailReloaded " + version + " is older than the minimum supported " + join(MIN) + " (tested with " + TESTED + ")");
        }
        if (v[0] > MIN[0]) {
            return new UnavailableJailPort("JailReloaded " + version + " is a newer major version than this integration supports (tested with " + TESTED + ")");
        }
        try {
            JailPort adapter = Probe.build(p, version, log);
            if (!version.equals(TESTED)) {
                log.warning("JailReloaded " + version + " differs from the tested version " + TESTED
                        + ". The API probe passed, but run a live custody test before relying on it.");
            }
            return adapter;
        } catch (LinkageError | RuntimeException e) {
            return new UnavailableJailPort("JailReloaded " + version + " is incompatible (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
    }

    /** Isolated so that class loading problems surface only inside connect()'s try block. */
    private static final class Probe {
        static JailPort build(Plugin p, String version, Logger log) {
            com.graywolf336.jail.JailMain main = (com.graywolf336.jail.JailMain) p;
            // API probe: every member the adapter depends on must exist and answer.
            if (com.graywolf336.jail.JailsAPI.getJailManager() == null) throw new IllegalStateException("JailManager unavailable");
            if (com.graywolf336.jail.JailsAPI.getPrisonerManager() == null) throw new IllegalStateException("PrisonerManager unavailable");
            if (main.getJailIO() == null) throw new IllegalStateException("JailIO unavailable");
            return new JailReloadedAdapter(main, version, log);
        }
    }

    static int[] parse(String s) {
        try {
            String[] parts = s.split("[.\\-+ ]");
            int[] out = new int[3];
            for (int i = 0; i < 3 && i < parts.length; i++) out[i] = Integer.parseInt(parts[i].replaceAll("\\D", ""));
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        return 0;
    }

    private static String join(int[] v) {
        return v[0] + "." + v[1] + "." + v[2];
    }
}
