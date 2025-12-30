# DurableLogWriter

A high-performance, thread-safe, durable log writer implementation in Java. This system guarantees that data is physically written to disk before returning to the caller, while maintaining high throughput through group commit optimization.

## Problem Statement

We need to build a library that writes logs to a file on a single server. This library must be thread-safe and fast.

**Key details:**
- Thousands of threads will try to write data at the same time
- The data must be safely saved to the disk (persistent)

### The Code Interface

```java
class DataWriter {
    public DataWriter(String filePathOnDisk) {
        // Setup the writer
    }

    public void push(byte[] data) {
        // Write data to the file
        // IMPORTANT: This method must wait and only return AFTER data is safe on the disk
    }
}
```

### Requirements

#### 1. Durability (Safety)

- The `push()` method must wait (block). It cannot return until the data is physically written to the disk using `fsync`
- If the server loses power or crashes, the data must still be there when it restarts

#### 2. Order of Messages

- Data coming from the same thread must stay in order in the file
- Data from different threads can mix together

**Example:**
```
Thread A sends: d1, then d2
Thread B sends: d3, then d4

✅ Good results:    d1_d2_d3_d4, d1_d3_d4_d2, d3_d1_d2_d4
❌ Bad results:     d2_d1_d3_d4 (Thread A is out of order)
```

#### 3. Speed Goals

- **High Throughput**: Handle as many total writes per second as possible
- **Low Latency**: Do not make threads wait too long
- **The Hard Part**: Using `fsync()` is very slow (it takes 1-10ms). If we do it every time, the system will be too slow

#### 4. Crash Recovery

- Define a file format
- Explain how to fix the file if the server crashes in the middle of a write

### Hard Problems to Solve

| Problem | Description |
|---------|-------------|
| Fsync is Slow | Doing one fsync per write takes ~5ms. This limits us to only 200 writes per second |
| Concurrency | How do we handle 1000 threads trying to write at once? |
| Ordering | How do we make sure Thread A's messages stay in order? |
| Recovery | How do we find and fix half-written data after a crash? |

---

## Key Features

- **Thread-Safe**: Thousands of threads can call `push()` concurrently
- **Durable**: `push()` blocks until data is physically on disk (fsync)
- **Ordered**: Writes from the same thread maintain their order
- **Fast**: Uses group commit to batch multiple writes per fsync
- **Crash Recovery**: Can recover from crashes with CRC validation

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Producer Threads                            │
│   Thread 1      Thread 2      Thread 3      ...      Thread N       │
│      │             │             │                      │           │
│      └─────────────┴─────────────┴──────────────────────┘           │
│                              │                                       │
│                         push(data)                                   │
│                              │                                       │
│                              ▼                                       │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │              ConcurrentLinkedQueue<WriteRequest>            │    │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐           │    │
│  │  │ Request │ │ Request │ │ Request │ │ Request │ ...       │    │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘           │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                              │                                       │
│                              ▼                                       │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                    Sync Thread (Background)                  │    │
│  │  1. Collect batch (timeout or size limit)                   │    │
│  │  2. Sort by (threadId, sequenceNum)                         │    │
│  │  3. Write batch to FileChannel                              │    │
│  │  4. fsync (channel.force())                                 │    │
│  │  5. Complete all waiters                                    │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                              │                                       │
│                              ▼                                       │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                        Log File                              │    │
│  │  [Header 32B][Record][Record][Record]...                    │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

## File Format

### File Header (32 bytes)

```
┌──────────────────┬──────────────┬──────────────┬──────────────────┬──────────────┐
│  Magic Number    │   Version    │    Flags     │  Creation Time   │   Reserved   │
│     8 bytes      │   4 bytes    │   4 bytes    │     8 bytes      │   8 bytes    │
└──────────────────┴──────────────┴──────────────┴──────────────────┴──────────────┘
```

- **Magic Number**: `0x4455524C4F47_01` ("DURLOG" + version byte)
- **Version**: Format version (currently 1)
- **Flags**: Reserved for future use
- **Creation Time**: Unix timestamp in milliseconds
- **Reserved**: For future expansion

### Record Format (16 + N bytes)

```
┌──────────────┬──────────────┬──────────────┬────────────────────────┐
│    Length    │    CRC32     │   Thread ID  │         Data           │
│   4 bytes    │   4 bytes    │   8 bytes    │       N bytes          │
└──────────────┴──────────────┴──────────────┴────────────────────────┘
```

- **Length**: Size of (ThreadID + Data) = 8 + N bytes
- **CRC32**: Checksum computed over (ThreadID + Data)
- **Thread ID**: ID of the thread that wrote this record
- **Data**: The actual payload

---

## Main Classes

### 1. DataWriter

The core durable log writer implementation.

#### Constructor

```java
public DataWriter(String filePathOnDisk) throws IOException
public DataWriter(String filePathOnDisk, WriterConfig config) throws IOException
```

Creates a new DataWriter. If the file exists, positions at the end for appending. If new, writes a file header.

#### Core Method: `push(byte[] data)`

```java
public void push(byte[] data) throws IOException
```

Writes data to the log **durably**. This method blocks until the data is physically on disk.

**Internal Flow:**

```
push(data)
    │
    ├──▶ 1. Validate input (not null, within size limit, writer open)
    │
    ├──▶ 2. Get thread-specific sequence number
    │       threadSequences.computeIfAbsent(threadId, k -> new AtomicLong(0))
    │
    ├──▶ 3. Create WriteRequest with CompletableFuture
    │
    ├──▶ 4. Acquire queue capacity (Semaphore - backpressure)
    │       Blocks if queue is full
    │
    ├──▶ 5. Add request to ConcurrentLinkedQueue
    │
    ├──▶ 6. Signal sync thread (ReentrantLock + Condition)
    │
    └──▶ 7. Block on request.await() until fsync completes
            CompletableFuture.get()
```

#### Sync Thread: `syncLoop()`

The background thread that performs the actual I/O:

```
syncLoop()
    │
    while (running || !pendingWrites.isEmpty())
    │
    ├──▶ 1. waitForFirstItem()
    │       Wait indefinitely for at least one request
    │
    ├──▶ 2. collectBatch(batch)
    │       │
    │       ├── drainAvailableItems() - poll all ready items
    │       │
    │       ├── shouldFlushBatch() - check size/timeout
    │       │   Flush if: batchBytes >= maxBatchSize OR timeout expired
    │       │
    │       └── waitForMoreItems() - wait with timeout for more
    │
    ├──▶ 3. Collections.sort(batch)
    │       Sort by (threadId, sequenceNum) for per-thread ordering
    │
    ├──▶ 4. writeBatch(batch, buffer)
    │       │
    │       ├── Serialize each record: [length][crc][threadId][data]
    │       │
    │       ├── Write to FileChannel
    │       │
    │       ├── channel.force(syncMetadata)  ◀── CRITICAL: fsync
    │       │
    │       └── request.complete() for all requests
    │
    └──▶ 5. Release semaphore permits, update statistics
```

#### Group Commit Optimization

The key performance optimization is **group commit** - multiple writes share a single fsync:

```
Time ──────────────────────────────────────────────────────────────▶

Thread 1:  push(A) ─────────────────────────────────────────▶ return
Thread 2:      push(B) ─────────────────────────────────────▶ return
Thread 3:          push(C) ─────────────────────────────────▶ return

Sync Thread:       [collect batch] [write A,B,C] [fsync] [notify all]
                                                    │
                                                    └── Single fsync for 3 writes!
```

Without group commit, each write would need its own fsync (very slow).

#### Statistics

```java
public Stats getStats()
```

Returns:
- `totalWrites`: Total records written
- `totalBytes`: Total bytes written
- `totalSyncs`: Total fsync operations
- `avgWritesPerSync`: Average writes per fsync (measures group commit efficiency)

---

### 2. WriteRequest

Represents a single write request from a thread.

#### Fields

| Field | Type | Description |
|-------|------|-------------|
| `data` | `byte[]` | The data to write |
| `threadId` | `long` | Thread that submitted the request |
| `sequenceNum` | `long` | Per-thread sequence number for ordering |
| `completion` | `CompletableFuture<Void>` | Signals when write is durable |
| `timestamp` | `long` | Request creation time (nanos) |

#### Comparable Implementation

```java
public int compareTo(WriteRequest other) {
    int threadCompare = Long.compare(this.threadId, other.threadId);
    if (threadCompare != 0) {
        return threadCompare;
    }
    return Long.compare(this.sequenceNum, other.sequenceNum);
}
```

Sorting by `(threadId, sequenceNum)` ensures that within a batch, records from the same thread appear in the order they were submitted.

#### Synchronization Pattern

```
Producer Thread                    Sync Thread
      │                                 │
      │ request = new WriteRequest()    │
      │      │                          │
      │      ▼                          │
      │ pendingWrites.add(request)      │
      │      │                          │
      │      ▼                          │
      │ request.await() ──────────────▶ │ (blocks on CompletableFuture.get())
      │      │                          │
      │      │                          │ batch.add(request)
      │      │                          │ writeBatch()
      │      │                          │ fsync()
      │      │                          │      │
      │      │◀──────────────────────── │ request.complete()
      │      │                          │      │
      ▼      ▼                          ▼      ▼
    return                            continue
```

---

### 3. LogRecovery

Utility for recovering log files after a crash.

#### Recovery Process

```
recover(filePath)
    │
    ├──▶ 1. Check file exists and size >= 32 bytes (header)
    │
    ├──▶ 2. Validate header
    │       - Check magic number: 0x4455524C4F47_01
    │       - Check version: 1
    │
    ├──▶ 3. Scan records sequentially
    │       │
    │       for each record at position:
    │       │
    │       ├── Read header: [length][crc][threadId]
    │       │
    │       ├── Validate length (8 <= length <= 64MB)
    │       │
    │       ├── Check if complete record fits in file
    │       │
    │       ├── Read data
    │       │
    │       ├── Compute CRC over (threadId + data)
    │       │
    │       ├── Compare with stored CRC
    │       │   │
    │       │   ├── Match: validRecords++, continue
    │       │   │
    │       │   └── Mismatch: STOP scanning
    │       │
    │       └── Update validEnd position
    │
    ├──▶ 4. Truncate file at validEnd
    │       channel.truncate(validEnd)
    │       channel.force(true)
    │
    └──▶ 5. Return RecoveryResult
```

#### Why Truncation Works

Since records are written sequentially and only complete after fsync:

```
File before crash:
[Header][Record1][Record2][Record3][Partial...
         valid    valid    valid   ← incomplete/corrupt

After recovery:
[Header][Record1][Record2][Record3]
         valid    valid    valid   ← clean file
```

The incomplete tail is simply removed. No valid data is lost.

#### CRC Validation

```java
CRC32 crc = new CRC32();
ByteBuffer crcBuffer = ByteBuffer.allocate(8 + dataLength);
crcBuffer.putLong(threadId);
crcBuffer.put(data);
crc.update(crcBuffer.array());
int computedCrc = (int) crc.getValue();

if (computedCrc != storedCrc) {
    // Corrupt record - stop scanning
}
```

CRC covers both threadId and data to detect any corruption.

#### Methods

| Method | Description |
|--------|-------------|
| `recover(filePath)` | Validate and truncate corrupt tail |
| `readAll(filePath)` | Read all valid records as `List<Record>` |
| `verifyOrdering(filePath)` | Check per-thread order integrity |

---

### 4. WriterConfig

Configuration using the Builder pattern.

#### Configuration Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxBatchSize` | 1 MB | Bytes to collect before triggering fsync |
| `maxBatchWaitMs` | 5 ms | Max time to wait for more writes |
| `maxQueueSize` | 100,000 | Max pending requests (backpressure) |
| `maxRecordSize` | 16 MB | Max single record size |
| `syncMetadata` | false | Whether to sync file metadata |

#### Tuning Guide

**High Throughput (higher latency):**
```java
WriterConfig.builder()
    .maxBatchSize(4 * 1024 * 1024)  // 4 MB batches
    .maxBatchWaitMs(10)              // Wait longer for more writes
    .build();
```

**Low Latency (lower throughput):**
```java
WriterConfig.builder()
    .maxBatchSize(64 * 1024)  // 64 KB batches
    .maxBatchWaitMs(1)        // Flush quickly
    .build();
```

**Backpressure Control:**
```java
WriterConfig.builder()
    .maxQueueSize(10000)  // Limit memory usage
    .build();
```

#### syncMetadata Option

```java
// Fast (fdatasync - only sync data blocks)
.syncMetadata(false)

// Safe (fsync - sync data + metadata like file size, timestamps)
.syncMetadata(true)
```

`false` is faster because it uses `fdatasync()` semantics, which only ensures data blocks are on disk. Set to `true` if you need guaranteed metadata consistency.

---

## Usage Examples

### Basic Usage

```java
try (DataWriter writer = new DataWriter("/path/to/log.dat")) {
    writer.push("Hello, World!".getBytes());
    writer.push("This is durable!".getBytes());
}
// Both writes are guaranteed on disk when push() returns
```

### Multi-Threaded Usage

```java
DataWriter writer = new DataWriter("/path/to/log.dat");

ExecutorService executor = Executors.newFixedThreadPool(10);
for (int i = 0; i < 1000; i++) {
    final int id = i;
    executor.submit(() -> {
        writer.push(("Message " + id).getBytes());
    });
}

executor.shutdown();
executor.awaitTermination(1, TimeUnit.MINUTES);
writer.close();
```

### Custom Configuration

```java
WriterConfig config = WriterConfig.builder()
    .maxBatchSize(2 * 1024 * 1024)  // 2 MB
    .maxBatchWaitMs(10)
    .maxQueueSize(50000)
    .syncMetadata(true)
    .build();

try (DataWriter writer = new DataWriter("/path/to/log.dat", config)) {
    // Use writer...
}
```

### Recovery After Crash

```java
// Recover and truncate corrupt tail
LogRecovery.RecoveryResult result = LogRecovery.recover("/path/to/log.dat");
System.out.println("Valid records: " + result.validRecords);
System.out.println("Corrupt records: " + result.corruptRecords);

// Read all valid records
List<LogRecovery.Record> records = LogRecovery.readAll("/path/to/log.dat");
for (LogRecovery.Record record : records) {
    System.out.println("Thread " + record.threadId + ": " +
                       new String(record.data));
}
```

---

## Concurrency Deep Dive

### Thread Safety Mechanisms

| Component | Mechanism | Purpose |
|-----------|-----------|---------|
| `pendingWrites` | `ConcurrentLinkedQueue` | Lock-free queue for requests |
| `threadSequences` | `ConcurrentHashMap<Long, AtomicLong>` | Per-thread sequence numbers |
| `queueCapacity` | `Semaphore` | Backpressure control |
| `syncLock` + `hasWork` | `ReentrantLock` + `Condition` | Sync thread signaling |
| `running` | `volatile boolean` | Shutdown coordination |

### Ordering Guarantee

**Guarantee**: Writes from the same thread appear in the file in the order they were submitted.

**Implementation**:
1. Each thread gets an `AtomicLong` counter
2. Each request includes `(threadId, sequenceNum)`
3. Before writing, batch is sorted by `(threadId, sequenceNum)`

```
Thread 1 submits: A(seq=0), B(seq=1), C(seq=2)
Thread 2 submits: X(seq=0), Y(seq=1)

Batch before sort: [B, X, A, Y, C]
Batch after sort:  [A, B, C, X, Y]
                    └─────┘  └───┘
                    Thread 1  Thread 2
                    in order  in order
```

### Why CompletableFuture?

`WriteRequest` uses `CompletableFuture<Void>` to:
1. Block the producer until fsync completes (`await()` calls `get()`)
2. Allow the sync thread to notify multiple waiters efficiently (`complete()`)
3. Propagate exceptions cleanly (`completeExceptionally()`)

---

## Performance Characteristics

| Metric | Typical Value | Notes |
|--------|---------------|-------|
| Throughput | 50,000-200,000 writes/sec | Depends on disk and batch settings |
| Latency | 1-10 ms | Depends on `maxBatchWaitMs` |
| Writes per fsync | 10-1000+ | Higher = better throughput |

The key insight is that **fsync is expensive** (1-10ms typically). Group commit amortizes this cost across many writes.

## Error Handling

- `IllegalArgumentException`: Null data, data too large, or invalid config
- `IOException`: File I/O errors, writer closed
- On error during batch write, all requests in the batch receive the exception

## Files

| File | Description |
|------|-------------|
| `DataWriter.java` | Core durable log writer |
| `LogRecovery.java` | Crash recovery utility |
| `WriteRequest.java` | Write request model |
| `WriterConfig.java` | Configuration with builder |
| `DataWriterTest.java` | Comprehensive test suite |
| `ReadDemo.java` | Demo for reading logs |
