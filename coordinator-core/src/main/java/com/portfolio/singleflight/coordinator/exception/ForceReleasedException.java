package com.portfolio.singleflight.coordinator.exception;

/**
 * Thrown when {@link com.portfolio.singleflight.coordinator.SingleFlightCoordinator#forceRelease}
 * is invoked — ops kill-switch.
 *
 * <p>Owner + all attached waiters receive this exception. The underlying
 * operation is NOT cancelled (same nuance as {@link DeadlineExceededException}).
 *
 * <p>Used in operational scenarios:
 * <ul>
 *   <li>Stuck owner (heartbeat detected but deadline not yet hit) — manual intervention</li>
 *   <li>Operator-initiated cleanup of inflight state</li>
 *   <li>Test isolation (ensure no leftover entries between tests)</li>
 * </ul>
 */
public final class ForceReleasedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;
    private final String releaseReason;

    public ForceReleasedException(String key, String releaseReason) {
        super(buildMessage(key, releaseReason));
        this.key = key;
        this.releaseReason = releaseReason;
    }

    private static String buildMessage(String key, String reason) {
        return "[SingleFlight] force released key=" + key + " reason=" + reason;
    }

    public String key() {
        return key;
    }

    public String releaseReason() {
        return releaseReason;
    }
}
