package com.portfolio.singleflight.coordinator;

/**
 * Snapshot of a single inflight coalescing entry — observability DTO.
 *
 * <p>Returned by {@link SingleFlightCoordinator#getInflightState()}.
 * Immutable point-in-time copy; reflects the state at the moment of capture.
 *
 * @param key          the coalescing key
 * @param startedAt    epoch millis when the owner started
 * @param waiterCount  total participants (owner + attached waiters)
 * @param hostId       hostname / instance id, for multi-instance debugging
 */
public record InflightEntry(String key, long startedAt, int waiterCount, String hostId) {

    public InflightEntry {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (hostId == null) {
            throw new NullPointerException("hostId");
        }
        if (waiterCount < 0) {
            throw new IllegalArgumentException("waiterCount must be >= 0, got " + waiterCount);
        }
    }
}
