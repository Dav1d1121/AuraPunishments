package dev.aura.punishments.support;

import dev.aura.punishments.integration.JailPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory JailPort with failure injection, used to test the custody and sentencing sagas. */
public final class FakeJail implements JailPort {
    public final Map<UUID, PrisonerState> prisoners = new LinkedHashMap<>();
    public boolean available = true;
    public String failNext = null;          // message to fail the next mutating call with
    public boolean releasePendingOffline = false;
    public final List<String> calls = new ArrayList<>();
    private int seq = 0;

    @Override public boolean available() { return available; }
    @Override public String status() { return available ? "FAKE 1.0" : "FAKE (down)"; }

    @Override
    public Outcome jail(JailRequest r) {
        calls.add("jail:" + r.name() + ":" + r.durationMs());
        if (failNext != null) { String m = failNext; failNext = null; return Outcome.of(State.ERROR, m); }
        if (prisoners.containsKey(r.uuid())) return Outcome.of(State.ALREADY_JAILED, r.name() + " is already jailed");
        String id = "jid-" + (++seq);
        prisoners.put(r.uuid(), new PrisonerState(r.uuid(), r.name(), id, "main", r.durationMs(), r.durationMs() < 0, false,
                "[" + r.tag() + "] " + r.reason(), r.jailer()));
        return new Outcome(State.JAILED, id, "main", "ok");
    }

    @Override
    public Outcome release(UUID uuid, String expectedId, String actor) {
        calls.add("release:" + uuid);
        if (failNext != null) { String m = failNext; failNext = null; return Outcome.of(State.ERROR, m); }
        PrisonerState p = prisoners.get(uuid);
        if (p == null) return Outcome.of(State.NOT_JAILED, "not jailed");
        if (expectedId != null && !expectedId.equals(p.jailPrisonerId())) return Outcome.of(State.NOT_OURS, "different record");
        if (releasePendingOffline) {
            prisoners.put(uuid, new PrisonerState(uuid, p.name(), p.jailPrisonerId(), p.jailName(), 0, false, true, p.reason(), p.jailer()));
            return Outcome.of(State.RELEASE_PENDING, "pending");
        }
        prisoners.remove(uuid);
        return Outcome.of(State.RELEASED, "released");
    }

    @Override
    public Outcome setRemaining(UUID uuid, String expectedId, long remainingMs, String actor) {
        calls.add("setRemaining:" + uuid + ":" + remainingMs);
        if (failNext != null) { String m = failNext; failNext = null; return Outcome.of(State.ERROR, m); }
        PrisonerState p = prisoners.get(uuid);
        if (p == null) return Outcome.of(State.NOT_JAILED, "not jailed");
        if (expectedId != null && !expectedId.equals(p.jailPrisonerId())) return Outcome.of(State.NOT_OURS, "different record");
        prisoners.put(uuid, new PrisonerState(uuid, p.name(), p.jailPrisonerId(), p.jailName(), remainingMs, false, false, p.reason(), p.jailer()));
        return new Outcome(State.UPDATED, p.jailPrisonerId(), p.jailName(), "updated");
    }

    @Override public Optional<PrisonerState> find(UUID uuid) { return Optional.ofNullable(prisoners.get(uuid)); }
    @Override public List<PrisonerState> allPrisoners() { return new ArrayList<>(prisoners.values()); }
    @Override public List<String> jailNames() { return List.of("main"); }
}
