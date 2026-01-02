/**
 * Represents the three possible states of a Circuit Breaker.
 *
 * State transitions:
 * - CLOSED -> OPEN: When failure count reaches threshold
 * - OPEN -> HALF_OPEN: When recovery timeout expires
 * - HALF_OPEN -> CLOSED: When test request succeeds
 * - HALF_OPEN -> OPEN: When test request fails
 */
public enum State {
    /**
     * Normal operating state. Requests are allowed through.
     * Failures are counted, and if the threshold is reached,
     * the circuit transitions to OPEN.
     */
    CLOSED,

    /**
     * Circuit is broken. All requests are blocked immediately
     * with a CircuitOpenException. After the recovery timeout
     * expires, the circuit transitions to HALF_OPEN.
     */
    OPEN,

    /**
     * Test state. One request is allowed through to test if
     * the service has recovered. If successful, transitions
     * to CLOSED. If it fails, transitions back to OPEN.
     */
    HALF_OPEN
}
