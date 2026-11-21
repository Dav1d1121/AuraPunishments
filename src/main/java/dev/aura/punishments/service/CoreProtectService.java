package dev.aura.punishments.service;

import dev.aura.punishments.model.Records.Evidence;
import dev.aura.punishments.model.Simple.EvidenceType;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import dev.aura.punishments.util.Text;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CoreProtect as an evidence source. A lookup is read-only; attaching it stores a snapshot of what CoreProtect reported
 * as ordinary evidence on the case. It never rolls anything back and never influences a verdict: staff weigh it like any
 * other evidence.
 */
public final class CoreProtectService extends Svc {
    private final CaseService cases;
    private volatile CoreProtectPort port = CoreProtectPort.NONE;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AuraPunishments-CoreProtect");
        t.setDaemon(true);
        return t;
    });

    public CoreProtectService(Core core, CaseService cases) {
        super(core);
        this.cases = cases;
    }

    public void port(CoreProtectPort p) {
        this.port = p == null ? CoreProtectPort.NONE : p;
    }

    public boolean available() {
        return core.settings.coreProtect.enabled() && port.available();
    }

    public String status() {
        return core.settings.coreProtect.enabled() ? port.status() : "disabled in config";
    }

    public CompletableFuture<Result<CoreProtectPort.Result>> lookup(Actor actor, CoreProtectPort.Query raw) {
        Result<Void> perm = core.auth.require(actor, Perms.COREPROTECT, "run CoreProtect lookups");
        if (!perm.isOk()) return done(perm.cast());
        if (!available()) return done(Result.fail(Code.UNAVAILABLE, "CoreProtect lookups are not available (" + status() + ")."));
        var cfg = core.settings.coreProtect;
        long seconds = Math.max(60, Math.min(raw.seconds(), cfg.maxTime() / 1000));
        int radius = Math.max(1, Math.min(raw.radius(), cfg.maxRadius()));
        if (raw.player() == null || !raw.player().matches("[A-Za-z0-9_.]{1,32}")) return done(Result.fail(Code.INVALID, "Enter a valid player name."));
        CoreProtectPort.Query q = new CoreProtectPort.Query(raw.player(), raw.kind(), seconds, raw.world(), raw.x(), raw.y(), raw.z(), radius);
        return CompletableFuture.supplyAsync(() -> {
            try {
                CoreProtectPort.Result r = port.lookup(q);
                return Result.ok(new CoreProtectPort.Result(r.lines().size() > cfg.sampleRows() ? r.lines().subList(0, cfg.sampleRows()) : r.lines(),
                        r.total(), r.truncated() || r.lines().size() > cfg.sampleRows()));
            } catch (Throwable t) {
                core.log.warning("CoreProtect lookup failed: " + t);
                return Result.<CoreProtectPort.Result>fail(Code.EXTERNAL, "CoreProtect could not complete the lookup (" + t.getClass().getSimpleName() + "). Nothing was changed.");
            }
        }, exec);
    }

    /** Store a lookup snapshot as COREPROTECT evidence on the case. */
    public CompletableFuture<Result<Evidence>> attach(Actor actor, long caseId, CoreProtectPort.Query q, CoreProtectPort.Result res) {
        StringBuilder ref = new StringBuilder("CoreProtect ").append(q.kind().name().toLowerCase()).append(" lookup (read-only snapshot): ")
                .append(q.player()).append(", last ").append(Durations.format(q.seconds() * 1000));
        if (q.kind() == CoreProtectPort.Kind.BLOCKS) ref.append(", r=").append(q.radius()).append(" @").append(q.world()).append(' ').append(q.x()).append(',').append(q.y()).append(',').append(q.z());
        ref.append(" - ").append(res.total()).append(" result(s)");
        for (String l : res.lines()) ref.append(" | ").append(l);
        String text = Text.truncate(ref.toString(), core.settings.cases.maxEvidenceRef());
        return cases.addEvidence(actor, caseId, new EvidenceValidator.Input(EvidenceType.COREPROTECT, text, "Snapshot only; weigh like any evidence."));
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
