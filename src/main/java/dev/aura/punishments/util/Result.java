package dev.aura.punishments.util;

/**
 * Outcome of a service operation. Failures carry a machine-readable {@link Code} and a
 * message that is safe to show to staff. An operation is never reported as successful
 * unless the underlying action (database write, JailReloaded call) actually succeeded.
 */
public record Result<T>(T value, Code code, String message) {

    public enum Code {
        OK, NO_PERMISSION, NOT_FOUND, INVALID, CONFLICT, HIERARCHY, LIMIT, STATE, EVIDENCE,
        EXTERNAL, UNAVAILABLE, DB_ERROR, DUPLICATE
    }

    public static <T> Result<T> ok(T value) {
        return new Result<>(value, Code.OK, null);
    }

    public static Result<Void> ok() {
        return new Result<>(null, Code.OK, null);
    }

    public static <T> Result<T> fail(Code code, String message) {
        return new Result<>(null, code, message);
    }

    /** Re-type a failure without losing its code and message. */
    public <U> Result<U> cast() {
        return new Result<>(null, code, message);
    }

    public boolean isOk() {
        return code == Code.OK;
    }

    public T orThrow() {
        if (!isOk()) throw new IllegalStateException(code + ": " + message);
        return value;
    }
}
