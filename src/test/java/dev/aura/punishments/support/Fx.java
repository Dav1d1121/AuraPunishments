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
    public final CustodyService custody;
    public final CaseService cases;
    public final JudicialService judicial;
    public final DirectService direct;
    public final List<DomainEvent> events = new ArrayList<>();
    public final Map<UUID, LevelResolver.TargetAuth> auth = new HashMap<>();
    public final Set<UUID> judges = new HashSet<>();
    public long now = 1_800_000_000_000L;

    public Fx() throws Exception { this(null, new Mutator()); }

    public static final class Mutator {
        public String config = null;
    }

    public Fx(Path file, Mutator m) throws Exception {
        db = new Database(file, Logger.getLogger("test"));
        db.open();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(Fx.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        YamlConfiguration off = YamlConfiguration.loadConfiguration(new InputStreamReader(Fx.class.getResourceAsStream("/offenses.yml"), StandardCharsets.UTF_8));
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
        punishments = new PunishmentService(core);
        custody = new CustodyService(core, punishments);
        cases = new CaseService(core, custody);
        judicial = new JudicialService(core, custody, punishments);
        direct = new DirectService(core, punishments);
        punishments.loadCache().get();
    }

    /** A staff actor holding exactly these nodes. */
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
    }
}
