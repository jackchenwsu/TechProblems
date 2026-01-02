import java.time.Instant;

/**
 * Immutable value object representing the complete state of a Circuit Breaker.
 *
 * This class is designed to be used with {@link java.util.concurrent.atomic.AtomicReference}
 * and Compare-And-Swap (CAS) operations for lock-free thread safety. By combining all
 * state fields into a single immutable object, we can atomically update the entire
 * state in one CAS operation.
 *
 * Immutability guarantees:
 * - All fields are final
 * - No setters exist
 * - State transitions create new instances via factory methods
 *
 * Thread-safety: This class is immutable and therefore inherently thread-safe.
 */
public final class CircuitState {

    /** Current state of the circuit breaker */
    private final State state;

    /** Number of consecutive failures (only meaningful when state is CLOSED) */
    private final int failureCount;

    /** Timestamp when the circuit transitioned to OPEN state (null when not OPEN) */
    private final Instant openedAt;

    /**
     * Private constructor - use factory methods to create instances.
     *
     * @param state the circuit state
     * @param failureCount number of consecutive failures
     * @param openedAt timestamp when circuit opened (null if not applicable)
     */
    private CircuitState(State state, int failureCount, Instant openedAt) {
        this.state = state;
        this.failureCount = failureCount;
        this.openedAt = openedAt;
    }

    /**
     * Creates a new CircuitState in the CLOSED state with zero failures.
     * This is the initial state for a new Circuit Breaker.
     *
     * @return a new CircuitState in CLOSED state
     */
    public static CircuitState closed() {
        return new CircuitState(State.CLOSED, 0, null);
    }

    /**
     * @return the current state (CLOSED, OPEN, or HALF_OPEN)
     */
    public State getState() {
        return state;
    }

    /**
     * @return the number of consecutive failures
     */
    public int getFailureCount() {
        return failureCount;
    }

    /**
     * @return the timestamp when the circuit opened, or null if not in OPEN state
     */
    public Instant getOpenedAt() {
        return openedAt;
    }

    /**
     * Creates a new state with the failure count incremented by one.
     * Used when a request fails while in CLOSED state.
     *
     * @return a new CircuitState with failureCount + 1
     */
    public CircuitState withFailure() {
        return new CircuitState(State.CLOSED, failureCount + 1, null);
    }

    /**
     * Creates a new state with the failure count reset to zero.
     * Used when a request succeeds while in CLOSED state.
     *
     * @return a new CircuitState with failureCount = 0
     */
    public CircuitState withSuccess() {
        return new CircuitState(State.CLOSED, 0, null);
    }

    /**
     * Transitions to the OPEN state, recording the current timestamp.
     * Used when failure threshold is reached in CLOSED state, or when
     * the test request fails in HALF_OPEN state.
     *
     * @param now the current timestamp to record as openedAt
     * @return a new CircuitState in OPEN state
     */
    public CircuitState toOpen(Instant now) {
        return new CircuitState(State.OPEN, 0, now);
    }

    /**
     * Transitions to the CLOSED state with zero failures.
     * Used when the test request succeeds in HALF_OPEN state.
     *
     * @return a new CircuitState in CLOSED state
     */
    public CircuitState toClosed() {
        return new CircuitState(State.CLOSED, 0, null);
    }

    /**
     * Transitions to the HALF_OPEN state.
     * Used when the recovery timeout expires while in OPEN state.
     * Preserves the openedAt timestamp for potential use.
     *
     * @return a new CircuitState in HALF_OPEN state
     */
    public CircuitState toHalfOpen() {
        return new CircuitState(State.HALF_OPEN, 0, openedAt);
    }
}
