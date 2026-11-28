package dev.aura.punishments.integration;

import dev.aura.punishments.service.NetworkJail;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JailReloaded as seen from a backend that does not have it: the jail lives on the network's jail backend, so a request made here is
 * <b>queued</b>, never executed. The punishment row (written in the same transaction as the request) is the durable queue; the jail
 * backend carries it out and records the result. Nothing is ever reported as "jailed" by this port: a placement is answered with
 * {@link State#JAILED_PENDING_PLACEMENT} and the placeholder prisoner id {@value #PLACEHOLDER_PREFIX}&lt;tag&gt;, which the jail backend
 * replaces with JailReloaded's real id once it has confirmed the jail.
 *
 * <p>It is {@link #available() available} only while the jail backend is running (its heartbeat is fresh): a jail that nobody could carry
 * out is refused when staff try to issue it.
 */
public final class RemoteJailPort implements JailPort {
    public static final String PLACEHOLDER_PREFIX = "net:";
    private final NetworkJail net;

    public RemoteJailPort(NetworkJail net) {
        this.net = net;
    }

    public static boolean placeholder(String prisonerId) {
        return prisonerId != null && prisonerId.startsWith(PLACEHOLDER_PREFIX);
    }

    @Override
    public boolean available() {
        return net.backendRunning();
    }

    @Override
    public boolean authoritative() {
        return false;
    }

    @Override
    public String status() {
        return net.backendRunning() ? "network jail: handled by the jail backend '" + net.backendName() + "'"
                : "UNAVAILABLE - the jail backend '" + net.backendName() + "' is not running (no heartbeat)";
    }

    @Override
    public Outcome jail(JailRequest r) {
        return new Outcome(State.JAILED_PENDING_PLACEMENT, PLACEHOLDER_PREFIX + r.tag(), net.backendName(), "queued for the jail backend '" + net.backendName() + "'");
    }

    @Override
    public Outcome release(UUID uuid, String expectedJailPrisonerId, String actorName) {
        return Outcome.of(State.RELEASE_PENDING, "queued for the jail backend '" + net.backendName() + "'");
    }

    @Override
    public Outcome setRemaining(UUID uuid, String expectedJailPrisonerId, long remainingMs, String actorName) {
        return Outcome.of(State.UPDATED, "queued for the jail backend '" + net.backendName() + "'");
    }

    @Override
    public Optional<PrisonerState> find(UUID uuid) {
        return Optional.empty();
    }

    @Override
    public List<PrisonerState> allPrisoners() {
        return List.of();
    }

    @Override
    public List<String> jailNames() {
        return List.of();
    }
}
