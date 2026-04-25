package com.portfolio.singleflight.coordinator.exception;

/**
 * Thrown when the per-key waiter count would exceed the configured cap.
 *
 * <p>Already-attached waiters are unaffected — only new attaches are
 * rejected. This is advisory load-shedding for UI-spam protection
 * (cmong-admin Batch Reply UI 같은 시나리오).
 *
 * <p>Enforcement is atomic — the check and the would-be increment happen
 * inside the base adapter's {@code compute()} block, so race-free.
 */
public final class CongestionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;
    private final int currentWaiters;
    private final int maxWaiters;
    private final String telemetryTag;

    public CongestionException(String key, int currentWaiters, int maxWaiters, String telemetryTag) {
        super(buildMessage(key, currentWaiters, maxWaiters, telemetryTag));
        this.key = key;
        this.currentWaiters = currentWaiters;
        this.maxWaiters = maxWaiters;
        this.telemetryTag = telemetryTag;
    }

    private static String buildMessage(String key, int current, int max, String tag) {
        StringBuilder sb = new StringBuilder("[SingleFlight] congestion rejected key=")
                .append(key)
                .append(" waiterCount=").append(current)
                .append(" maxWaiters=").append(max);
        if (tag != null) {
            sb.append(" tag=").append(tag);
        }
        return sb.toString();
    }

    public String key() {
        return key;
    }

    public int currentWaiters() {
        return currentWaiters;
    }

    public int maxWaiters() {
        return maxWaiters;
    }

    public String telemetryTag() {
        return telemetryTag;
    }
}
