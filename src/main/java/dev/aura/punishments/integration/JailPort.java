package dev.aura.punishments.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONLY door to JailReloaded. AuraPunishments never implements jailing itself; it asks
 * JailReloaded through this port and verifies the result. All methods must be called on the
 * server main thread (JailReloaded's state is not thread-safe).
 *
 * <p>Implementations: {@link JailReloadedAdapter} (real) and {@link UnavailableJailPort}
 * (JailReloaded missing or incompatible: every call fails safely with a clear message).
 */
public interface JailPort {

    enum State {
        /** Prisoner is held in JailReloaded for this request. */
        JAILED,
        /** Prisoner is registered but offline; JailReloaded places them at next login. */
        JAILED_PENDING_PLACEMENT,
        ALREADY_JAILED,
        REFUSED,
        RELEASED,
        /** Release accepted; JailReloaded completes it (offline prisoner, next login). */
        RELEASE_PENDING,
        NOT_JAILED,
        /** The jail record is not the one this case created; nothing was touched. */
        NOT_OURS,
        UPDATED,
        ERROR
    }

    record Outcome(State state, String jailPrisonerId, String jailName, String message) {
        public boolean ok() {
            return state == State.JAILED || state == State.JAILED_PENDING_PLACEMENT || state == State.RELEASED
                    || state == State.RELEASE_PENDING || state == State.UPDATED;
        }

        public static Outcome of(State s, String message) {
            return new Outcome(s, null, null, message);
        }
    }

    /**
     * @param durationMs -1 = until released (pretrial custody), otherwise sentence length in ms
     * @param tag        short case tag written into the JailReloaded reason, e.g. "AP-0012"
     */
    record JailRequest(UUID uuid, String name, long caseId, String tag, String reason, String jailer,
                       long durationMs, String jailName, String cellName, boolean mute) {}

    record PrisonerState(UUID uuid, String name, String jailPrisonerId, String jailName, long remainingMs,
                         boolean forever, boolean offlinePending, String reason, String jailer) {}

    boolean available();

    /** Human-readable status for diagnostics: detected version, compatibility, last problem. */
    String status();

    Outcome jail(JailRequest request);

    /** Release only if the held record is the one identified by {@code expectedJailPrisonerId}. */
    Outcome release(UUID uuid, String expectedJailPrisonerId, String actorName);

    /** Change the remaining time of the held record (pretrial -> timed sentence). */
    Outcome setRemaining(UUID uuid, String expectedJailPrisonerId, long remainingMs, String actorName);

    Optional<PrisonerState> find(UUID uuid);

    /** All current prisoners (for reconciliation). */
    List<PrisonerState> allPrisoners();

    List<String> jailNames();
}
