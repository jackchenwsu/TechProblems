# Circuit Breaker

## Task Overview

We need to build a Circuit Breaker. This is a design pattern used in systems with many parts (distributed systems). It stops one failure from causing everything else to fail.

Think of it like an electrical circuit breaker in your house. If a service keeps failing, the breaker "opens." This stops requests from going to that service. This gives the service time to fix itself.

### Key Goals

- **Thread-safe**: Multiple parts of the program can use it at the same time safely.
- **Three States**:
  - `CLOSED`: Everything is working fine.
  - `OPEN`: The service is broken. Requests are blocked.
  - `HALF_OPEN`: We are checking if the service is fixed.
- **Automatic Changes**: It switches states automatically based on how many times it fails and how much time passes.
- **Generic**: It should work with any type of data result.

## Requirements

We need to create a class called `CircuitBreaker<T>`. It wraps around an operation that might fail. It decides whether to run that operation based on the current state.

### 1. The States

- `CLOSED`: Normal mode. Requests are allowed through.
- `OPEN`: The circuit is broken. Requests are blocked immediately.
- `HALF_OPEN`: Test mode. It lets one request through to see if the service is working again.

### 2. How States Change

- `CLOSED → OPEN`: Happens when the number of failures hits a specific limit (threshold).
- `OPEN → HALF_OPEN`: Happens after a specific waiting time (timeout) ends.
- `HALF_OPEN → CLOSED`: Happens if the test request succeeds.
- `HALF_OPEN → OPEN`: Happens if the test request fails.

### 3. Settings

- `failureThreshold`: How many failures in a row cause the circuit to open.
- `recoveryTimeout`: How long to wait before trying to recover.

### 4. Public Methods (API)

- `T call(Supplier<T> supplier)`: Runs the operation through the circuit breaker.
- `String getState()`: Returns the current state (`CLOSED`, `OPEN`, or `HALF_OPEN`).

## Usage Examples

### Example 1: Basic Failure Handling

```java
CircuitBreaker<String> breaker = new CircuitBreaker<>(3, Duration.ofSeconds(5));

// First 3 failures
for (int i = 0; i < 3; i++) {
    try {
        breaker.call(() -> {
            throw new RuntimeException("Service down");
        });
    } catch (Exception e) {
        System.out.println("Failed: " + e.getMessage());
    }
}

// 4th attempt - circuit is now OPEN
try {
    breaker.call(() -> "Success");
} catch (Exception e) {
    System.out.println(e.getMessage()); // "Circuit is OPEN. Request blocked."
}
```

### Example 2: Recovery After Waiting

```java
CircuitBreaker<Integer> breaker = new CircuitBreaker<>(2, Duration.ofSeconds(2));

// Trigger failures to open circuit
breaker.call(() -> { throw new RuntimeException("Fail"); }); // Fail 1
breaker.call(() -> { throw new RuntimeException("Fail"); }); // Fail 2, circuit OPEN

// Wait for recovery timeout
Thread.sleep(2100);

// Next call transitions to HALF_OPEN and tries the request
Integer result = breaker.call(() -> 42); // Success! Circuit → CLOSED
System.out.println(result); // 42
```

### Example 3: Failed Recovery

```java
CircuitBreaker<String> breaker = new CircuitBreaker<>(1, Duration.ofSeconds(1));

// Open the circuit
breaker.call(() -> { throw new RuntimeException("Fail"); });

// Wait for timeout
Thread.sleep(1100);

// Recovery attempt fails - circuit goes back to OPEN
try {
    breaker.call(() -> {
        throw new RuntimeException("Still failing");
    });
} catch (Exception e) {
    System.out.println("Recovery failed");
}

// Circuit is OPEN again
System.out.println(breaker.getState()); // "OPEN"
```

## Rules and Limits

- `failureThreshold` will be between 1 and 100.
- `recoveryTimeout` will be between 1ms and 1 hour.
- The code must be thread-safe (handle multiple users at once).
- It must work with any data type (`T`).
- Errors from the service must be passed back to the caller.

---

# Implementation Plan

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Thread Safety | AtomicReference + CAS | Lock-free, high performance under contention |
| State Representation | Enum with switch | Simple, clear, sufficient for 3 states |
| Failure Reset | Reset on success | More forgiving, prevents cascading from transient errors |
| HALF_OPEN Behavior | Fail fast | Simpler, prevents thundering herd |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    CircuitBreaker<T>                        │
├─────────────────────────────────────────────────────────────┤
│  - state: AtomicReference<CircuitState>                     │
│  - failureThreshold: int                                    │
│  - recoveryTimeout: Duration                                │
├─────────────────────────────────────────────────────────────┤
│  + call(Supplier<T>): T                                     │
│  + getState(): String                                       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              CircuitState (Immutable)                       │
├─────────────────────────────────────────────────────────────┤
│  - state: State (enum)                                      │
│  - failureCount: int                                        │
│  - openedAt: Instant                                        │
├─────────────────────────────────────────────────────────────┤
│  + withFailure(): CircuitState                              │
│  + withSuccess(): CircuitState                              │
│  + toOpen(): CircuitState                                   │
│  + toClosed(): CircuitState                                 │
│  + toHalfOpen(): CircuitState                               │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    State (Enum)                             │
├─────────────────────────────────────────────────────────────┤
│  CLOSED, OPEN, HALF_OPEN                                    │
└─────────────────────────────────────────────────────────────┘
```

---

## State Machine

```
                    ┌──────────────────┐
                    │                  │
         success    │     CLOSED       │◄─────────────────┐
        (reset to 0)│  failureCount=N  │                  │
            ┌───────│                  │                  │
            │       └────────┬─────────┘                  │
            │                │                            │
            ▼                │ failure                    │
         [stay]              │ count++                    │
                             │                            │
                             ▼                            │
                   ┌─────────────────────┐                │
                   │ failureCount >=     │                │
                   │ threshold?          │                │
                   └─────────┬───────────┘                │
                             │ YES                        │
                             ▼                            │
                    ┌────────────────┐                    │
                    │                │                    │ success
    other threads   │     OPEN       │                    │
    fail fast ───►  │  openedAt=now  │                    │
                    │                │                    │
                    └────────┬───────┘                    │
                             │                            │
                             │ timeout expired            │
                             │ (CAS to HALF_OPEN)         │
                             ▼                            │
                    ┌────────────────┐                    │
    other threads   │                │                    │
    fail fast ───►  │   HALF_OPEN    │────────────────────┘
                    │  (test mode)   │
                    │                │──────┐
                    └────────────────┘      │
                                           │ failure
                                           │
                                           ▼
                                    [back to OPEN]
```

---

## Detailed Design

### 1. State Enum

```java
public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
}
```

### 2. CircuitState (Immutable Value Object)

**Fields:**
- `State state` - current state
- `int failureCount` - consecutive failures (only meaningful in CLOSED)
- `Instant openedAt` - timestamp when entered OPEN (null if not OPEN)

**Factory Methods (return new instances):**
- `withFailure()` - increment failure count
- `withSuccess()` - reset failure count to 0
- `toOpen(Instant now)` - transition to OPEN, record timestamp
- `toClosed()` - transition to CLOSED, reset failure count
- `toHalfOpen()` - transition to HALF_OPEN

### 3. CircuitBreaker<T> Main Class

**Fields:**
- `AtomicReference<CircuitState> stateRef` - the atomic state holder
- `int failureThreshold` - configured threshold
- `Duration recoveryTimeout` - configured timeout

**Constructor:**
```java
public CircuitBreaker(int failureThreshold, Duration recoveryTimeout)
```

**Main Method - `call(Supplier<T> supplier)`:**

```
LOOP:
  1. Read current state (snapshot)

  2. SWITCH on state.state:

     CASE CLOSED:
        - Execute supplier
        - On SUCCESS: CAS to state.withSuccess(), return result
        - On FAILURE:
            - newState = state.withFailure()
            - if newState.failureCount >= threshold:
                - CAS to newState.toOpen(now)
            - else:
                - CAS to newState
            - Re-throw exception

     CASE OPEN:
        - Check if timeout expired: now > openedAt + recoveryTimeout
        - If NOT expired: throw "Circuit is OPEN"
        - If expired:
            - Try CAS: OPEN → HALF_OPEN
            - If CAS succeeds: continue to execute test (fall through to HALF_OPEN logic)
            - If CAS fails: LOOP again (re-read state)

     CASE HALF_OPEN:
        - If we just CAS'd here (we're the tester):
            - Execute supplier
            - On SUCCESS: CAS to toClosed(), return result
            - On FAILURE: CAS to toOpen(now), re-throw
        - If we didn't CAS here (another thread is testing):
            - throw "Circuit is HALF_OPEN"
```

**Key Insight for HALF_OPEN:**
We need to track whether *this thread* transitioned to HALF_OPEN or if we just observed it. Use a local `boolean isTestingThread = false` before the loop, set to `true` after successful CAS from OPEN→HALF_OPEN.

---

## CAS Retry Strategy

For CAS operations that might fail due to concurrent updates:

```java
while (true) {
    CircuitState current = stateRef.get();
    CircuitState next = /* compute next state */;
    if (stateRef.compareAndSet(current, next)) {
        break; // success
    }
    // CAS failed, another thread modified state
    // Re-read and re-evaluate from the beginning
}
```

---

## Exception Handling

1. **CircuitOpenException** - thrown when circuit is OPEN or HALF_OPEN (not testing thread)
2. **Original exceptions** - from supplier are re-thrown to caller after state update

---

## Implementation Steps

### Step 1: Create State Enum
- Simple enum with CLOSED, OPEN, HALF_OPEN
- File: `State.java`

### Step 2: Create CircuitState Class
- Immutable value object
- All fields final
- Factory methods for transitions
- File: `CircuitState.java`

### Step 3: Create CircuitBreaker<T> Class
- Constructor with validation
- AtomicReference for state
- `call()` method with CAS logic
- `getState()` method
- File: `CircuitBreaker.java`

### Step 4: Create Custom Exception
- `CircuitOpenException extends RuntimeException`
- Clear message about circuit state
- File: `CircuitOpenException.java`

---

## Files to Create

```
CircuitBreaker/
├── README.md (existing)
├── src/
│   ├── State.java
│   ├── CircuitState.java
│   ├── CircuitBreaker.java
│   └── CircuitOpenException.java
└── test/
    ├── CircuitBreakerTest.java
    ├── CircuitBreakerConcurrencyTest.java
    └── MutableClock.java
```

---

## Edge Cases to Handle

1. **Concurrent transitions OPEN→HALF_OPEN**: Only one thread wins CAS
2. **Concurrent failures in CLOSED**: Each thread CAS's its own failure count increment
3. **Rapid success after failure in CLOSED**: Reset works correctly
4. **Time edge case**: Exactly at timeout boundary
5. **Null supplier**: Validate in call() method
6. **Supplier returns null**: Valid case, pass through

---

## Testing Strategy

### Injectable Clock for Deterministic Tests

Add a `Clock` parameter to avoid flaky `Thread.sleep()` tests:

```java
public CircuitBreaker(int threshold, Duration timeout) {
    this(threshold, timeout, Clock.systemUTC());  // Production
}

// Package-private for testing
CircuitBreaker(int threshold, Duration timeout, Clock clock) {
    this.clock = clock;
}
```

### Test Categories

#### 1. Unit Tests (Single-Threaded)

| Test Case | Description |
|-----------|-------------|
| `shouldStartInClosedState` | Initial state is CLOSED |
| `shouldTransitionToOpenAfterThresholdFailures` | N failures → OPEN |
| `shouldBlockRequestsWhenOpen` | OPEN throws CircuitOpenException |
| `shouldTransitionToHalfOpenAfterTimeout` | Timeout expires → HALF_OPEN |
| `shouldTransitionToClosedOnSuccessfulTest` | HALF_OPEN + success → CLOSED |
| `shouldTransitionBackToOpenOnFailedTest` | HALF_OPEN + failure → OPEN |
| `shouldResetFailureCountOnSuccess` | Success in CLOSED resets counter |
| `shouldPassThroughSupplierException` | Original exception is re-thrown |
| `shouldReturnSupplierResult` | Successful result is returned |

#### 2. Concurrency Tests

| Test Case | Description |
|-----------|-------------|
| `shouldCountAllConcurrentFailures` | Multiple threads failing simultaneously all count |
| `shouldAllowOnlyOneTestInHalfOpen` | Only 1 thread executes test, others fail fast |
| `shouldNotCorruptStateUnderHighContention` | Stress test with random success/fail |
| `shouldHandleConcurrentSuccessAndFailure` | Mixed concurrent operations |

### MutableClock Utility

```java
public class MutableClock extends Clock {
    private Instant now;

    public MutableClock(Instant start) { this.now = start; }
    public void advance(Duration duration) { now = now.plus(duration); }
    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
}
```
