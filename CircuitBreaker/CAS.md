# CAS (Compare-And-Swap) Logic

CAS (Compare-And-Swap, sometimes called Compare-And-Set) is an **atomic operation** used in multithreaded programming to achieve synchronization without locks. It's a fundamental building block for lock-free data structures and algorithms.

## How CAS Works

The operation takes three parameters:
1. **Memory location** (V) - the variable to update
2. **Expected value** (E) - the value we expect the variable to have
3. **New value** (N) - the value to set if expectation matches

```
if (V == E) {
    V = N;
    return true;  // success
} else {
    return false; // failure, someone else modified V
}
```

This entire operation is **atomic** - it happens as a single, uninterruptible step at the CPU level.

---

## Code Examples

### 1. Java - AtomicInteger Counter (Lock-Free)

```java
import java.util.concurrent.atomic.AtomicInteger;

public class CASCounter {
    private AtomicInteger count = new AtomicInteger(0);
    
    // Thread-safe increment without locks
    public int increment() {
        int oldValue, newValue;
        do {
            oldValue = count.get();
            newValue = oldValue + 1;
        } while (!count.compareAndSet(oldValue, newValue));  // CAS operation
        return newValue;
    }
    
    // Alternative using built-in (uses CAS internally)
    public int incrementSimple() {
        return count.incrementAndGet();
    }
}
```

### 2. Java - Lock-Free Stack

```java
import java.util.concurrent.atomic.AtomicReference;

public class LockFreeStack<T> {
    private AtomicReference<Node<T>> top = new AtomicReference<>();
    
    private static class Node<T> {
        final T value;
        Node<T> next;
        Node(T value) { this.value = value; }
    }
    
    public void push(T value) {
        Node<T> newNode = new Node<>(value);
        Node<T> oldTop;
        do {
            oldTop = top.get();
            newNode.next = oldTop;
        } while (!top.compareAndSet(oldTop, newNode));  // CAS
    }
    
    public T pop() {
        Node<T> oldTop;
        Node<T> newTop;
        do {
            oldTop = top.get();
            if (oldTop == null) return null;
            newTop = oldTop.next;
        } while (!top.compareAndSet(oldTop, newTop));  // CAS
        return oldTop.value;
    }
}
```

### 3. C++ - Using std::atomic

```cpp
#include <atomic>
#include <iostream>

class SpinLock {
    std::atomic<bool> locked{false};
    
public:
    void lock() {
        bool expected = false;
        // Keep trying until we successfully set false -> true
        while (!locked.compare_exchange_weak(expected, true)) {
            expected = false;  // Reset expected for next attempt
        }
    }
    
    void unlock() {
        locked.store(false);
    }
};

// Lock-free counter
class AtomicCounter {
    std::atomic<int> value{0};
    
public:
    int increment() {
        int old_value = value.load();
        while (!value.compare_exchange_weak(old_value, old_value + 1)) {
            // old_value is updated automatically on failure
        }
        return old_value + 1;
    }
};
```

### 4. Go - Using sync/atomic

```go
package main

import (
    "sync/atomic"
)

type Counter struct {
    value int64
}

func (c *Counter) Increment() int64 {
    for {
        old := atomic.LoadInt64(&c.value)
        new := old + 1
        if atomic.CompareAndSwapInt64(&c.value, old, new) {
            return new
        }
        // CAS failed, retry
    }
}

// Simpler alternative
func (c *Counter) IncrementSimple() int64 {
    return atomic.AddInt64(&c.value, 1)
}
```

---

## Real-World Scenarios

| Scenario | Description |
|----------|-------------|
| **Lock-free counters** | High-performance metrics, request counters, rate limiters |
| **Lock-free queues/stacks** | Message passing between threads without blocking |
| **Spin locks** | Short-held locks where spinning is cheaper than context switching |
| **Database optimistic locking** | Version numbers to detect concurrent modifications |
| **Connection pools** | Atomic allocation of resources |
| **Circuit breakers** | Atomic state transitions (CLOSED → OPEN → HALF_OPEN) |
| **Caches** | Atomic updates to cached values |

---

## CAS vs Locks

| Aspect | CAS (Lock-Free) | Locks |
|--------|-----------------|-------|
| **Blocking** | Non-blocking (spin/retry) | Blocking |
| **Deadlock** | Impossible | Possible |
| **Performance** | Better under low contention | Better under high contention |
| **Complexity** | Higher (ABA problem, retry logic) | Lower |
| **Starvation** | Possible (unlucky threads) | Prevented with fair locks |

---

## ABA Problem

A known issue with CAS:

```
Thread 1: Read A, prepare to swap to C
Thread 2: Changes A → B → A
Thread 1: CAS succeeds (sees A), but state was modified!
```

**Solution**: Use versioned references (`AtomicStampedReference` in Java):

```java
AtomicStampedReference<Integer> ref = new AtomicStampedReference<>(100, 0);

int[] stampHolder = new int[1];
int value = ref.get(stampHolder);
int stamp = stampHolder[0];

// CAS with version check
ref.compareAndSet(value, newValue, stamp, stamp + 1);
```

---

CAS is essential for building high-performance concurrent systems where lock overhead is unacceptable!
