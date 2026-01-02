/**
 * Exception thrown when a request is blocked by the Circuit Breaker.
 *
 * This exception is thrown in two scenarios:
 * 1. When the circuit is in OPEN state and the recovery timeout has not expired
 * 2. When the circuit is in HALF_OPEN state and another thread is already testing
 *
 * This is an unchecked exception (extends RuntimeException) because circuit
 * breaker failures are typically transient infrastructure issues that callers
 * may want to handle but shouldn't be forced to declare.
 *
 * Example usage:
 * <pre>
 * try {
 *     String result = breaker.call(() -> externalService.getData());
 * } catch (CircuitOpenException e) {
 *     // Handle circuit open - maybe return cached data or show error
 *     log.warn("Circuit is {}, request blocked", e.getCircuitState());
 * }
 * </pre>
 */
public class CircuitOpenException extends RuntimeException {

    /** The state of the circuit when this exception was thrown (OPEN or HALF_OPEN) */
    private final State circuitState;

    /**
     * Creates a new CircuitOpenException.
     *
     * @param message descriptive message about why the request was blocked
     * @param circuitState the current state of the circuit (OPEN or HALF_OPEN)
     */
    public CircuitOpenException(String message, State circuitState) {
        super(message);
        this.circuitState = circuitState;
    }

    /**
     * Returns the state of the circuit when this exception was thrown.
     * This can be used to determine if the circuit was fully open or
     * in a testing state.
     *
     * @return the circuit state (OPEN or HALF_OPEN)
     */
    public State getCircuitState() {
        return circuitState;
    }
}
