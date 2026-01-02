import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A thread-safe Circuit Breaker implementation using lock-free CAS operations.
 *
 * <h2>Overview</h2>
 * The Circuit Breaker pattern prevents cascading failures in distributed systems
 * by wrapping calls to external services and monitoring for failures. When failures
 * exceed a threshold, the circuit "opens" and blocks further requests, giving the
 * failing service time to recover.
 *
 * <h2>Thread Safety</h2>
 * This implementation uses {@link AtomicReference} with Compare-And-Swap (CAS)
 * operations instead of locks for better performance under high contention.
 * The entire state is encapsulated in an immutable {@link CircuitState} object,
 * allowing atomic state transitions.
 *
 * <h2>State Machine</h2>
 * <pre>
 *     CLOSED ──(failures >= threshold)──> OPEN
 *        ^                                  │
 *        │                                  │
 *    (success)                        (timeout expires)
 *        │                                  │
 *        └──────── HALF_OPEN <──────────────┘
 *                     │
 *                 (failure)
 *                     │
 *                     └──────> OPEN
 * </pre>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * CircuitBreaker&lt;String&gt; breaker = new CircuitBreaker&lt;&gt;(3, Duration.ofSeconds(5));
 *
 * try {
 *     String result = breaker.call(() -&gt; externalService.getData());
 * } catch (CircuitOpenException e) {
 *     // Circuit is open, handle gracefully
 * } catch (ServiceException e) {
 *     // Service call failed, exception propagated
 * }
 * </pre>
 *
 * @param <T> the type of result returned by the protected operation
 */
public class CircuitBreaker<T> {

    /**
     * Atomic reference holding the current circuit state.
     * All state transitions are performed using CAS operations on this reference.
     */
    private final AtomicReference<CircuitState> stateRef;

    /** Number of consecutive failures required to open the circuit */
    private final int failureThreshold;

    /** Time to wait in OPEN state before transitioning to HALF_OPEN */
    private final Duration recoveryTimeout;

    /** Clock used for time-based operations (injectable for testing) */
    private final Clock clock;

    /**
     * Creates a new Circuit Breaker with the specified configuration.
     *
     * @param failureThreshold number of consecutive failures to open the circuit (1-100)
     * @param recoveryTimeout time to wait before attempting recovery (1ms to 1 hour)
     * @throws IllegalArgumentException if parameters are out of valid range
     */
    public CircuitBreaker(int failureThreshold, Duration recoveryTimeout) {
        this(failureThreshold, recoveryTimeout, Clock.systemUTC());
    }

    /**
     * Package-private constructor for testing with injectable clock.
     *
     * @param failureThreshold number of consecutive failures to open the circuit
     * @param recoveryTimeout time to wait before attempting recovery
     * @param clock clock instance for time operations
     */
    CircuitBreaker(int failureThreshold, Duration recoveryTimeout, Clock clock) {
        // Validate failure threshold (1-100)
        if (failureThreshold < 1 || failureThreshold > 100) {
            throw new IllegalArgumentException("failureThreshold must be between 1 and 100");
        }
        // Validate recovery timeout (1ms to 1 hour)
        if (recoveryTimeout.toMillis() < 1 || recoveryTimeout.toHours() > 1) {
            throw new IllegalArgumentException("recoveryTimeout must be between 1ms and 1 hour");
        }
        Objects.requireNonNull(clock, "clock must not be null");

        this.failureThreshold = failureThreshold;
        this.recoveryTimeout = recoveryTimeout;
        this.clock = clock;
        this.stateRef = new AtomicReference<>(CircuitState.closed());
    }

    /**
     * Executes the given supplier through the circuit breaker.
     *
     * <p>Behavior depends on the current state:</p>
     * <ul>
     *   <li><b>CLOSED:</b> Executes the supplier. On success, resets failure count.
     *       On failure, increments failure count and may open circuit.</li>
     *   <li><b>OPEN:</b> If timeout expired, transitions to HALF_OPEN and executes
     *       as a test. Otherwise, throws CircuitOpenException.</li>
     *   <li><b>HALF_OPEN:</b> If this thread won the CAS race, executes as a test.
     *       Otherwise, throws CircuitOpenException (fail-fast).</li>
     * </ul>
     *
     * @param supplier the operation to execute
     * @return the result from the supplier
     * @throws CircuitOpenException if the circuit is open or half-open (and not testing)
     * @throws NullPointerException if supplier is null
     * @throws RuntimeException if the supplier throws an exception (propagated)
     */
    public T call(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");

        // Main loop for CAS retry on state transitions
        while (true) {
            CircuitState current = stateRef.get();
            boolean isTestingThread = false;

            switch (current.getState()) {
                case CLOSED:
                    // Normal operation - execute and track failures
                    return handleClosed(current, supplier);

                case OPEN:
                    Instant now = clock.instant();
                    Instant openedAt = current.getOpenedAt();

                    // Check if recovery timeout has expired
                    if (now.isBefore(openedAt.plus(recoveryTimeout))) {
                        // Timeout not expired - block the request
                        throw new CircuitOpenException("Circuit is OPEN. Request blocked.", State.OPEN);
                    }

                    // Timeout expired - attempt to transition to HALF_OPEN
                    // Use CAS to ensure only ONE thread becomes the tester
                    CircuitState halfOpen = current.toHalfOpen();
                    if (stateRef.compareAndSet(current, halfOpen)) {
                        // CAS succeeded - this thread is the tester
                        isTestingThread = true;
                        return handleHalfOpen(halfOpen, supplier, isTestingThread);
                    }
                    // CAS failed - another thread modified state, retry loop
                    continue;

                case HALF_OPEN:
                    // Another thread is already testing - fail fast
                    throw new CircuitOpenException("Circuit is HALF_OPEN. Request blocked.", State.HALF_OPEN);

                default:
                    throw new IllegalStateException("Unknown state: " + current.getState());
            }
        }
    }

    /**
     * Handles request execution in CLOSED state.
     *
     * <p>On success: Attempts to reset failure count via CAS. Even if CAS fails
     * (another thread updated), we still return our successful result.</p>
     *
     * <p>On failure: Increments failure count and may open circuit if threshold reached.</p>
     *
     * @param current the current circuit state
     * @param supplier the operation to execute
     * @return the result from the supplier
     */
    private T handleClosed(CircuitState current, Supplier<T> supplier) {
        try {
            T result = supplier.get();

            // Success - reset failure count
            // CAS may fail if another thread updated, but our result is still valid
            CircuitState next = current.withSuccess();
            stateRef.compareAndSet(current, next);
            return result;

        } catch (Exception e) {
            // Failure - increment count and possibly open circuit
            handleFailureInClosed(current);
            throw e;
        }
    }

    /**
     * Handles a failure while in CLOSED state using CAS retry loop.
     *
     * <p>This method uses a CAS retry loop because multiple threads may be
     * failing concurrently. Each thread attempts to increment the failure count,
     * and one may trigger the transition to OPEN state.</p>
     *
     * <p>If the state is no longer CLOSED when we retry, another thread has
     * already handled the state change, so we return without action.</p>
     *
     * @param current the state when failure occurred (may be stale)
     */
    private void handleFailureInClosed(CircuitState current) {
        while (true) {
            // Get fresh snapshot - original 'current' may be stale
            CircuitState snapshot = stateRef.get();

            // If state changed from CLOSED, another thread handled it
            if (snapshot.getState() != State.CLOSED) {
                return;
            }

            // Increment failure count
            CircuitState next = snapshot.withFailure();

            // Check if we should open the circuit
            if (next.getFailureCount() >= failureThreshold) {
                next = next.toOpen(clock.instant());
            }

            // Attempt atomic update
            if (stateRef.compareAndSet(snapshot, next)) {
                return; // Success - state updated
            }
            // CAS failed - another thread modified state, retry with fresh snapshot
        }
    }

    /**
     * Handles request execution in HALF_OPEN state (test request).
     *
     * <p>Only the thread that successfully CAS'd from OPEN to HALF_OPEN should
     * call this method with isTestingThread=true. Other threads should receive
     * a CircuitOpenException before reaching here.</p>
     *
     * <p>On success: Transitions to CLOSED state.</p>
     * <p>On failure: Transitions back to OPEN state with fresh timestamp.</p>
     *
     * @param current the current HALF_OPEN state
     * @param supplier the operation to execute
     * @param isTestingThread true if this thread won the CAS race
     * @return the result from the supplier
     */
    private T handleHalfOpen(CircuitState current, Supplier<T> supplier, boolean isTestingThread) {
        // Double-check: only testing thread should execute
        if (!isTestingThread) {
            throw new CircuitOpenException("Circuit is HALF_OPEN. Request blocked.", State.HALF_OPEN);
        }

        try {
            T result = supplier.get();

            // Success - transition to CLOSED
            CircuitState closed = current.toClosed();
            // CAS loop to ensure state is updated (should succeed since we're the only tester)
            while (true) {
                CircuitState snapshot = stateRef.get();
                if (stateRef.compareAndSet(snapshot, closed)) {
                    break;
                }
            }
            return result;

        } catch (Exception e) {
            // Failure - transition back to OPEN with new timestamp
            CircuitState open = current.toOpen(clock.instant());
            // CAS loop to ensure state is updated
            while (true) {
                CircuitState snapshot = stateRef.get();
                if (stateRef.compareAndSet(snapshot, open)) {
                    break;
                }
            }
            throw e;
        }
    }

    /**
     * Returns the current state of the circuit breaker as a string.
     *
     * @return "CLOSED", "OPEN", or "HALF_OPEN"
     */
    public String getState() {
        return stateRef.get().getState().name();
    }
}
