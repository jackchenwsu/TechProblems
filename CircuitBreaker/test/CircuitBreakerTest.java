import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CircuitBreaker Unit Tests")
class CircuitBreakerTest {

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.now());
    }

    @Test
    @DisplayName("Should start in CLOSED state")
    void shouldStartInClosedState() {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(3, Duration.ofSeconds(5), clock);

        assertEquals("CLOSED", breaker.getState());
    }

    @Test
    @DisplayName("Should return supplier result when CLOSED")
    void shouldReturnSupplierResult() {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(3, Duration.ofSeconds(5), clock);

        String result = breaker.call(() -> "Hello World");

        assertEquals("Hello World", result);
        assertEquals("CLOSED", breaker.getState());
    }

    @Test
    @DisplayName("Should pass through supplier exception when CLOSED")
    void shouldPassThroughSupplierException() {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(3, Duration.ofSeconds(5), clock);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
            breaker.call(() -> {
                throw new RuntimeException("Service error");
            })
        );

        assertEquals("Service error", thrown.getMessage());
    }

    @Test
    @DisplayName("Should transition to OPEN after threshold failures")
    void shouldTransitionToOpenAfterThresholdFailures() {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(3, Duration.ofSeconds(5), clock);

        // First 3 failures
        for (int i = 0; i < 3; i++) {
            assertThrows(RuntimeException.class, () ->
                breaker.call(() -> {
                    throw new RuntimeException("Fail");
                })
            );
        }

        assertEquals("OPEN", breaker.getState());
    }

    @Test
    @DisplayName("Should block requests when OPEN")
    void shouldBlockRequestsWhenOpen() {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(1, Duration.ofSeconds(5), clock);

        // Open the circuit
        assertThrows(RuntimeException.class, () ->
            breaker.call(() -> {
                throw new RuntimeException("Fail");
            })
        );

        assertEquals("OPEN", breaker.getState());

        // Next call should be blocked
        CircuitOpenException exception = assertThrows(CircuitOpenException.class, () ->
            breaker.call(() -> "Success")
        );

        assertEquals(State.OPEN, exception.getCircuitState());
        assertTrue(exception.getMessage().contains("OPEN"));
    }

    @Test
    @DisplayName("Should transition to HALF_OPEN after timeout")
    void shouldTransitionToHalfOpenAfterTimeout() {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(1, Duration.ofSeconds(5), clock);

        // Open the circuit
        assertThrows(RuntimeException.class, () ->
            breaker.call(() -> {
                throw new RuntimeException("Fail");
            })
        );

        assertEquals("OPEN", breaker.getState());

        // Advance time past timeout
        clock.advance(Duration.ofSeconds(6));

        // Next call should transition to HALF_OPEN and execute
        String result = breaker.call(() -> "Recovered");

        assertEquals("Recovered", result);
        assertEquals("CLOSED", breaker.getState());
    }

    @Test
    @DisplayName("Should transition to CLOSED on successful test in HALF_OPEN")
    void shouldTransitionToClosedOnSuccessfulTest() {
        CircuitBreaker<Integer> breaker = new CircuitBreaker<>(1, Duration.ofSeconds(2), clock);

        // Open the circuit
        assertThrows(RuntimeException.class, () ->
            breaker.call(() -> {
                throw new RuntimeException("Fail");
            })
        );

        // Wait for timeout
        clock.advance(Duration.ofSeconds(3));

        // Successful test should close circuit
        Integer result = breaker.call(() -> 42);

        assertEquals(42, result);
        assertEquals("CLOSED", breaker.getState());
    }

    @Test
    @DisplayName("Should transition back to OPEN on failed test in HALF_OPEN")
    void shouldTransitionBackToOpenOnFailedTest() {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(1, Duration.ofSeconds(1), clock);

        // Open the circuit
        assertThrows(RuntimeException.class, () ->
            breaker.call(() -> {
                throw new RuntimeException("Fail");
            })
        );

        // Wait for timeout
        clock.advance(Duration.ofSeconds(2));

        // Failed test should reopen circuit
        assertThrows(RuntimeException.class, () ->
            breaker.call(() -> {
                throw new RuntimeException("Still failing");
            })
        );

        assertEquals("OPEN", breaker.getState());
    }

    @Test
    @DisplayName("Should reset failure count on success in CLOSED state")
    void shouldResetFailureCountOnSuccess() {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(3, Duration.ofSeconds(5), clock);

        // 2 failures
        for (int i = 0; i < 2; i++) {
            assertThrows(RuntimeException.class, () ->
                breaker.call(() -> {
                    throw new RuntimeException("Fail");
                })
            );
        }

        assertEquals("CLOSED", breaker.getState());

        // 1 success - should reset count
        breaker.call(() -> "Success");

        // 2 more failures - should NOT open (count was reset)
        for (int i = 0; i < 2; i++) {
            assertThrows(RuntimeException.class, () ->
                breaker.call(() -> {
                    throw new RuntimeException("Fail");
                })
            );
        }

        assertEquals("CLOSED", breaker.getState());
    }

    @Test
    @DisplayName("Should handle null return from supplier")
    void shouldHandleNullReturnFromSupplier() {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(3, Duration.ofSeconds(5), clock);

        String result = breaker.call(() -> null);

        assertNull(result);
        assertEquals("CLOSED", breaker.getState());
    }

    @Test
    @DisplayName("Should throw exception for null supplier")
    void shouldThrowExceptionForNullSupplier() {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(3, Duration.ofSeconds(5), clock);

        assertThrows(NullPointerException.class, () ->
            breaker.call(null)
        );
    }

    @Test
    @DisplayName("Should validate failureThreshold bounds")
    void shouldValidateFailureThresholdBounds() {
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitBreaker<>(0, Duration.ofSeconds(5))
        );

        assertThrows(IllegalArgumentException.class, () ->
            new CircuitBreaker<>(101, Duration.ofSeconds(5))
        );

        // Valid bounds should not throw
        assertDoesNotThrow(() -> new CircuitBreaker<>(1, Duration.ofSeconds(5)));
        assertDoesNotThrow(() -> new CircuitBreaker<>(100, Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("Should validate recoveryTimeout bounds")
    void shouldValidateRecoveryTimeoutBounds() {
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitBreaker<>(3, Duration.ofMillis(0))
        );

        assertThrows(IllegalArgumentException.class, () ->
            new CircuitBreaker<>(3, Duration.ofHours(2))
        );

        // Valid bounds should not throw
        assertDoesNotThrow(() -> new CircuitBreaker<>(3, Duration.ofMillis(1)));
        assertDoesNotThrow(() -> new CircuitBreaker<>(3, Duration.ofHours(1)));
    }

    @Test
    @DisplayName("Should not transition to HALF_OPEN before timeout")
    void shouldNotTransitionToHalfOpenBeforeTimeout() {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(1, Duration.ofSeconds(5), clock);

        // Open the circuit
        assertThrows(RuntimeException.class, () ->
            breaker.call(() -> {
                throw new RuntimeException("Fail");
            })
        );

        // Advance time but not past timeout
        clock.advance(Duration.ofSeconds(3));

        // Should still be blocked
        CircuitOpenException exception = assertThrows(CircuitOpenException.class, () ->
            breaker.call(() -> "Success")
        );

        assertEquals(State.OPEN, exception.getCircuitState());
    }

    @Test
    @DisplayName("Should work with different generic types")
    void shouldWorkWithDifferentGenericTypes() {
        CircuitBreaker<Integer> intBreaker = new CircuitBreaker<>(3, Duration.ofSeconds(5), clock);
        CircuitBreaker<Double> doubleBreaker = new CircuitBreaker<>(3, Duration.ofSeconds(5), clock);
        CircuitBreaker<Object> objectBreaker = new CircuitBreaker<>(3, Duration.ofSeconds(5), clock);

        assertEquals(Integer.valueOf(42), intBreaker.call(() -> 42));
        assertEquals(Double.valueOf(3.14), doubleBreaker.call(() -> 3.14));

        Object obj = new Object();
        assertSame(obj, objectBreaker.call(() -> obj));
    }
}
