package com.portfolio.singleflight.coordinator.exception;

/**
 * Thrown when the wall-clock deadline expires before the owner's operation
 * settles.
 *
 * <p><b>Important nuance:</b> this exception propagates to all coalesced
 * callers (owner + waiters) at the {@link java.util.concurrent.CompletableFuture}
 * level — i.e. callers see this as their own future's failure.
 *
 * <p>However, the <i>underlying</i> work (e.g. a Playwright session, a Redis
 * call) is NOT cancelled. The orphaned operation may continue running.
 * Resource cleanup is the operation author's responsibility (try-finally,
 * {@code whenComplete}, etc.).
 *
 * <p>See DESIGN.md §"Deadline" for the timeout vs cancellation discussion.
 */
public final class DeadlineExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;
    private final long deadlineMs;
    private final String telemetryTag;

    public DeadlineExceededException(String key, long deadlineMs, String telemetryTag) {
        super(buildMessage(key, deadlineMs, telemetryTag));
        this.key = key;
        this.deadlineMs = deadlineMs;
        this.telemetryTag = telemetryTag;
    }

    public DeadlineExceededException(String key, long deadlineMs, String telemetryTag, Throwable cause) {
        super(buildMessage(key, deadlineMs, telemetryTag), cause);
        this.key = key;
        this.deadlineMs = deadlineMs;
        this.telemetryTag = telemetryTag;
    }

    private static String buildMessage(String key, long deadlineMs, String tag) {
        StringBuilder sb = new StringBuilder("[SingleFlight] deadline exceeded key=")
                .append(key)
                .append(" deadlineMs=").append(deadlineMs);
        if (tag != null) {
            sb.append(" tag=").append(tag);
        }
        return sb.toString();
    }

    public String key() {
        return key;
    }

    public long deadlineMs() {
        return deadlineMs;
    }

    public String telemetryTag() {
        return telemetryTag;
    }
}
