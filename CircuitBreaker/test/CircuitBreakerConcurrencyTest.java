import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CircuitBreaker Concurrency Tests")
class CircuitBreakerConcurrencyTest {

    @Test
    @DisplayName("Should count all concurrent failures")
    void shouldCountAllConcurrentFailures() throws Exception {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(10, Duration.ofSeconds(5));
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    breaker.call(() -> {
                        throw new RuntimeException("fail");
                    });
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // Release all threads at once
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        // All 10 failures should be counted → circuit OPEN
        assertEquals("OPEN", breaker.getState());
    }

    @Test
    @DisplayName("Should allow only one test in HALF_OPEN state")
    void shouldAllowOnlyOneTestInHalfOpen() throws Exception {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(1, Duration.ofMillis(100));

        // Open the circuit
        try {
            breaker.call(() -> {
                throw new RuntimeException("fail");
            });
        } catch (Exception ignored) {
        }

        assertEquals("OPEN", breaker.getState());

        // Wait for timeout
        Thread.sleep(150);

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger testersCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    breaker.call(() -> {
                        testersCount.incrementAndGet();
                        // Simulate slow operation
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "success";
                    });
                    successCount.incrementAndGet();
                } catch (CircuitOpenException e) {
                    blockedCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        // Only 1 thread should have executed the supplier
        assertEquals(1, testersCount.get(), "Only one thread should test");
        // That thread should have succeeded
        assertEquals(1, successCount.get(), "One thread should succeed");
        // Other threads should have been blocked
        assertEquals(threadCount - 1, blockedCount.get(), "Other threads should be blocked");
        // Circuit should be closed now
        assertEquals("CLOSED", breaker.getState());
    }

    @RepeatedTest(5)
    @DisplayName("Should not corrupt state under high contention")
    void shouldNotCorruptStateUnderHighContention() throws Exception {
        CircuitBreaker<Integer> breaker = new CircuitBreaker<>(100, Duration.ofMillis(50));
        int threadCount = 20;
        int iterationsPerThread = 500;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                Random rand = new Random();
                try {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        try {
                            breaker.call(() -> {
                                if (rand.nextInt(100) < 30) { // 30% failure rate
                                    throw new RuntimeException("random fail");
                                }
                                return 42;
                            });
                            successCount.incrementAndGet();
                        } catch (CircuitOpenException e) {
                            blockedCount.incrementAndGet();
                        } catch (RuntimeException e) {
                            failCount.incrementAndGet();
                        }
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Verify state is valid (one of the three states)
        String state = breaker.getState();
        assertTrue(
            state.equals("CLOSED") || state.equals("OPEN") || state.equals("HALF_OPEN"),
            "State should be valid: " + state
        );

        // Verify total operations matches expected
        int totalOperations = successCount.get() + failCount.get() + blockedCount.get();
        assertEquals(
            threadCount * iterationsPerThread,
            totalOperations,
            "Total operations should match expected"
        );

        System.out.printf("Results: success=%d, fail=%d, blocked=%d, finalState=%s%n",
            successCount.get(), failCount.get(), blockedCount.get(), state);
    }

    @Test
    @DisplayName("Should handle concurrent success and failure")
    void shouldHandleConcurrentSuccessAndFailure() throws Exception {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(5, Duration.ofSeconds(1));
        int threadCount = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    breaker.call(() -> {
                        // Even threads succeed, odd threads fail
                        if (index % 2 == 0) {
                            return "success";
                        } else {
                            throw new RuntimeException("fail");
                        }
                    });
                    successCount.incrementAndGet();
                } catch (CircuitOpenException e) {
                    // Circuit opened
                } catch (RuntimeException e) {
                    failCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        // State should be valid
        String state = breaker.getState();
        assertTrue(
            state.equals("CLOSED") || state.equals("OPEN"),
            "State should be CLOSED or OPEN: " + state
        );

        System.out.printf("Mixed results: success=%d, fail=%d, finalState=%s%n",
            successCount.get(), failCount.get(), state);
    }

    @Test
    @DisplayName("Should handle rapid open-close cycles")
    void shouldHandleRapidOpenCloseCycles() throws Exception {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(2, Duration.ofMillis(50));
        int cycles = 10;

        for (int cycle = 0; cycle < cycles; cycle++) {
            // Open the circuit with failures
            for (int i = 0; i < 2; i++) {
                try {
                    breaker.call(() -> {
                        throw new RuntimeException("fail");
                    });
                } catch (Exception ignored) {
                }
            }

            assertEquals("OPEN", breaker.getState(), "Should be OPEN after failures");

            // Wait for timeout
            Thread.sleep(60);

            // Recover
            String result = breaker.call(() -> "recovered");
            assertEquals("recovered", result);
            assertEquals("CLOSED", breaker.getState(), "Should be CLOSED after recovery");
        }
    }

    @Test
    @DisplayName("Should handle multiple circuits independently")
    void shouldHandleMultipleCircuitsIndependently() throws Exception {
        CircuitBreaker<String> breaker1 = new CircuitBreaker<>(2, Duration.ofSeconds(1));
        CircuitBreaker<String> breaker2 = new CircuitBreaker<>(2, Duration.ofSeconds(1));

        // Open breaker1
        for (int i = 0; i < 2; i++) {
            try {
                breaker1.call(() -> {
                    throw new RuntimeException("fail");
                });
            } catch (Exception ignored) {
            }
        }

        assertEquals("OPEN", breaker1.getState());
        assertEquals("CLOSED", breaker2.getState());

        // breaker2 should still work
        String result = breaker2.call(() -> "success");
        assertEquals("success", result);
    }

    @Test
    @DisplayName("Should handle concurrent transitions from OPEN to HALF_OPEN")
    void shouldHandleConcurrentTransitionsFromOpenToHalfOpen() throws Exception {
        CircuitBreaker<String> breaker = new CircuitBreaker<>(1, Duration.ofMillis(50));

        // Open the circuit
        try {
            breaker.call(() -> {
                throw new RuntimeException("fail");
            });
        } catch (Exception ignored) {
        }

        // Wait for timeout
        Thread.sleep(60);

        int threadCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        CountDownLatch inSupplierLatch = new CountDownLatch(1);
        AtomicInteger transitionedCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    breaker.call(() -> {
                        transitionedCount.incrementAndGet();
                        // Signal that we're in the supplier
                        inSupplierLatch.countDown();
                        // Hold here while other threads attempt
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "success";
                    });
                } catch (CircuitOpenException e) {
                    blockedCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();

        // Wait for one thread to enter supplier before checking
        inSupplierLatch.await(5, TimeUnit.SECONDS);

        // Give other threads time to hit the HALF_OPEN state and get blocked
        Thread.sleep(50);

        doneLatch.await(10, TimeUnit.SECONDS);

        // Exactly one thread should have transitioned and executed
        assertEquals(1, transitionedCount.get(), "Exactly one thread should transition");
        assertEquals(threadCount - 1, blockedCount.get(), "Others should be blocked");
        assertEquals("CLOSED", breaker.getState());
    }
}
