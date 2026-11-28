package dev.aura.punishments.support;

import dev.aura.punishments.config.OffenseCatalog;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Database;
import dev.aura.punishments.model.Simple.EvidenceType;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.PlayerResolver.Target;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** Test fixture: real services over a real SQLite database with a fake JailReloaded and a controllable clock. */
public final class Fx implements AutoCloseable {
    public final Database db;
    public final Core core;
    public final FakeJail jail = new FakeJail();
    public final PunishmentService punishments;
    public final ReviewService reviews;
    public final CustodyService custody;
    public final JailService jails;
    public final PunishmentRecords records;
    public final PunishmentAppeals appeals;
    public final CaseService cases;
    public final JudicialService judicial;
    public final DirectService direct;
    public final List<DomainEvent> events = new ArrayList<>();
    public final Map<UUID, LevelResolver.TargetAuth> auth = new HashMap<>();
    public final Set<UUID> judges = new HashSet<>();
    /** Players the fake server considers connected. */
    public final Set<UUID> online = new HashSet<>();
    public long now = 1_800_000_000_000L;

    public Fx() throws Exception { this(null, new Mutator()); }

    public static final class Mutator {
        public String config = null;
        /** Config overrides applied on top of the shipped config.yml (path -> value). */
        public final Map<String, Object> set = new HashMap<>();
        public String serverName = null;
    }

    /** Set with -Dap.test.db=mariadb (gradle testMariadb): the same tests run against a real MariaDB/MySQL server, one throw-away database each. */
    public static final String BACKEND = System.getProperty("ap.test.db", "sqlite");
    private String sharedDatabaseName;
    private boolean dropOnClose = true;
    /** file path -> database name: a "restart" (a second fixture over the same file) must see the first one's data. */
    private static final Map<String, String> SHARED_BY_FILE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> CREATED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> CREATED.forEach(Fx::dropDatabase)));
    }

    public Fx(Path file, Mutator m) throws Exception {
        if (BACKEND.equals("sqlite")) {
            db = new Database(file, Logger.getLogger("test"), m.serverName);
        } else {
            String key = file == null ? null : file.toAbsolutePath().toString();
            sharedDatabaseName = key == null ? null : SHARED_BY_FILE.get(key);
            if (sharedDatabaseName == null) {
                sharedDatabaseName = "aura_t_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                if (key != null) SHARED_BY_FILE.put(key, sharedDatabaseName);
            }
            dropOnClose = key == null; // a database a later restart needs is dropped when the JVM ends
            CREATED.add(sharedDatabaseName);
            String host = System.getProperty("ap.test.db.host", "127.0.0.1");
            db = Database.shared(new dev.aura.punishments.db.StorageConfig(BACKEND, host, Integer.getInteger("ap.test.db.port", 3306), sharedDatabaseName,
                    System.getProperty("ap.test.db.user", "aura"), System.getProperty("ap.test.db.password", "aurapass"), false, true, true, 4, 1,
                    10_000, 300_000, 1_500_000, 60_000), Logger.getLogger("test"), m.serverName == null ? "testsrv" : m.serverName);
        }
        db.open();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(Fx.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        YamlConfiguration off = YamlConfiguration.loadConfiguration(new InputStreamReader(Fx.class.getResourceAsStream("/offenses.yml"), StandardCharsets.UTF_8));
        cfg.set("review.enabled", true); // the shipped file has owner review off for fresh installs (1.3.2); the existing review tests exercise it
        cfg.set("judicial.enabled", true); // the shipped file is judicial-off for fresh installs; most fixtures exercise the judicial system
        m.set.forEach(cfg::set);
        Settings s = Settings.parse(cfg);
        OffenseCatalog oc = OffenseCatalog.parse(off);
        LevelResolver lr = new LevelResolver() {
            @Override public CompletableFuture<TargetAuth> resolve(UUID uuid) {
                return CompletableFuture.completedFuture(auth.getOrDefault(uuid, new TargetAuth(0, false)));
            }
            @Override public CompletableFuture<Boolean> hasPermission(UUID uuid, String node) {
                return CompletableFuture.completedFuture(judges.contains(uuid) && node.equals(Perms.JUDICIAL_VERDICT));
            }
        };
        core = new Core(db, () -> now, Logger.getLogger("test"), MainThread.INLINE, s, oc, jail, lr, events::add);
        core.enforcement = new Enforcement() {
            @Override public boolean isOnline(UUID uuid) { return online.contains(uuid); }
            @Override public void disconnect(UUID uuid, dev.aura.punishments.model.Records.Punishment p) { }
            @Override public void noticeMute(UUID uuid, dev.aura.punishments.model.Records.Punishment p) { }
            @Override public void noticeUnmute(UUID uuid) { }
            @Override public boolean deliverNotification(UUID uuid, dev.aura.punishments.model.Records.Notification n, boolean late) { if (!online.contains(uuid)) return false; delivered.add(n); return true; }
        };
        punishments = new PunishmentService(core);
        reviews = new ReviewService(core, punishments);
        custody = new CustodyService(core, punishments);
        jails = new JailService(core, punishments);
        punishments.bind(jails);
        cases = new CaseService(core, custody);
        punishments.bind(cases);
        records = new PunishmentRecords(core, cases);
        appeals = new PunishmentAppeals(core, punishments);
        judicial = new JudicialService(core, custody, punishments);
        direct = new DirectService(core, punishments);
        punishments.loadCache().get();
    }

    /** A staff actor holding exactly these nodes. */
    /** Notifications that were actually shown to an online player (in order). */
    public final List<dev.aura.punishments.model.Records.Notification> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static Actor staff(String name, int level, String... nodes) {
        Set<String> n = new HashSet<>(List.of(nodes));
        return Actor.player(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name, new Actor.PermView() {
            @Override public boolean has(String node) { return n.contains(node); }
            @Override public int level() { return level; }
        });
    }

    public static final String[] JR_MOD = {Perms.PUNISH, Perms.PUNISH_OTHERS, Perms.CASE_CREATE, Perms.CASE_VIEW_ALL, Perms.CASE_VIEW_OWN,
            Perms.EVIDENCE_ADD, Perms.EVIDENCE_VIEW, Perms.NOTE_ADD, Perms.NOTE_VIEW, Perms.CUSTODY_INITIATE, Perms.CUSTODY_VIEW,
            Perms.HISTORY_VIEW, Perms.punish(dev.aura.punishments.model.PunishType.KICK), Perms.punish(dev.aura.punishments.model.PunishType.MUTE),
            Perms.limit(dev.aura.punishments.model.PunishType.MUTE, "short")};

    public static String[] concat(String[] a, String... b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    public static Actor owner() {
        return Actor.player(UUID.nameUUIDFromBytes("Owner".getBytes(StandardCharsets.UTF_8)), "Owner", new Actor.PermView() {
            @Override public boolean has(String node) { return true; }
            @Override public int level() { return 1000; }
        });
    }

    public static Target target(String name) {
        return new Target(UUID.nameUUIDFromBytes(("target-" + name).getBytes(StandardCharsets.UTF_8)), name, false);
    }

    public static EvidenceValidator.Input ev(String ref) {
        return new EvidenceValidator.Input(EvidenceType.EXTERNAL_LINK, ref, "");
    }

    public static EvidenceValidator.Input ev() {
        return ev("https://example.com/proof.png");
    }

    @Override
    public void close() {
        db.close();
        if (sharedDatabaseName != null && dropOnClose) {
            dropDatabase(sharedDatabaseName);
            CREATED.remove(sharedDatabaseName);
        }
    }

    private static void dropDatabase(String name) {
        try (java.sql.Connection c = new org.mariadb.jdbc.Driver().connect("jdbc:mariadb://" + System.getProperty("ap.test.db.host", "127.0.0.1") + ":"
                + Integer.getInteger("ap.test.db.port", 3306) + "/", propertiesOf());
             java.sql.Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + name + "`");
        } catch (java.sql.SQLException ignored) {
            // a leftover throw-away test database is harmless
        }
    }

    private static java.util.Properties propertiesOf() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("user", System.getProperty("ap.test.db.user", "aura"));
        p.setProperty("password", System.getProperty("ap.test.db.password", "aurapass"));
        return p;
    }
}
