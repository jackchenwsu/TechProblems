import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

/**
 * A thread-safe, durable log writer.
 *
 * Features:
 * - Thread-safe: Thousands of threads can call push() concurrently
 * - Durable: push() blocks until data is physically on disk (fsync)
 * - Ordered: Writes from the same thread maintain their order
 * - Fast: Uses group commit to batch multiple writes per fsync
 *
 * File Format:
 * - File Header: 32 bytes (magic, version, flags)
 * - Records: [Length(4)][CRC32(4)][ThreadID(8)][Data(N)]
 */
public class DataWriter implements AutoCloseable {

    // File format constants
    private static final long MAGIC_NUMBER = 0x4455524C4F47_01L;  // "DURLOG" + version
    private static final int FILE_HEADER_SIZE = 32;
    private static final int RECORD_HEADER_SIZE = 16;  // length(4) + crc(4) + threadId(8)

    // State
    private final String filePath;
    private final WriterConfig config;
    private final FileChannel fileChannel;
    private final ConcurrentLinkedQueue<WriteRequest> pendingWrites;
    private final ConcurrentHashMap<Long, AtomicLong> threadSequences;
    private final Semaphore queueCapacity;

    // Sync thread coordination
    private final Thread syncThread;
    private final ReentrantLock syncLock;
    private final Condition hasWork;
    private volatile boolean running;

    // Statistics
    private final AtomicLong totalWrites;
    private final AtomicLong totalBytes;
    private final AtomicLong totalSyncs;

    /**
     * Create a DataWriter with default configuration.
     */
    public DataWriter(String filePathOnDisk) throws IOException {
        this(filePathOnDisk, WriterConfig.defaults());
    }

    /**
     * Create a DataWriter with custom configuration.
     */
    public DataWriter(String filePathOnDisk, WriterConfig config) throws IOException {
        this.filePath = filePathOnDisk;
        this.config = config;
        this.pendingWrites = new ConcurrentLinkedQueue<>();
        this.threadSequences = new ConcurrentHashMap<>();
        this.queueCapacity = new Semaphore(config.getMaxQueueSize());
        this.syncLock = new ReentrantLock();
        this.hasWork = syncLock.newCondition();
        this.running = true;
        this.totalWrites = new AtomicLong(0);
        this.totalBytes = new AtomicLong(0);
        this.totalSyncs = new AtomicLong(0);

        // Open or create file
        Path path = Paths.get(filePathOnDisk);
        boolean isNew = !Files.exists(path);

        this.fileChannel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ);

        if (isNew) {
            writeFileHeader();
        } else {
            // Position at end for appending
            fileChannel.position(fileChannel.size());
        }

        // Start the sync thread
        this.syncThread = new Thread(this::syncLoop, "DataWriter-SyncThread");
        this.syncThread.setDaemon(true);
        this.syncThread.start();
    }

    /**
     * Write data to the log.
     * This method blocks until the data is durably written to disk.
     *
     * @param data The data to write
     * @throws IOException if write fails
     * @throws IllegalArgumentException if data exceeds max record size
     */
    public void push(byte[] data) throws IOException {
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
        if (data.length > config.getMaxRecordSize()) {
            throw new IllegalArgumentException(
                "Data size " + data.length + " exceeds max record size " + config.getMaxRecordSize());
        }
        if (!running) {
            throw new IOException("DataWriter is closed");
        }

        // Get thread-specific sequence number
        long threadId = Thread.currentThread().getId();
        long sequenceNum = threadSequences
                .computeIfAbsent(threadId, k -> new AtomicLong(0))
                .getAndIncrement();

        // Create request
        WriteRequest request = new WriteRequest(data, threadId, sequenceNum);

        // Acquire queue capacity (backpressure)
        try {
            queueCapacity.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for queue capacity", e);
        }

        // Add to queue and signal sync thread
        pendingWrites.add(request);
        signalSyncThread();

        // Wait for durable write
        try {
            request.await();
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Write failed", e);
        }
    }

    /**
     * Signal the sync thread that there's work to do.
     */
    private void signalSyncThread() {
        syncLock.lock();
        try {
            hasWork.signal();
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * The main sync loop running in a background thread.
     * Collects batches, sorts them, writes to file, and fsyncs.
     *
     * Batch flush triggers:
     * 1. Batch size reaches maxBatchSize
     * 2. Batch timeout (maxBatchWaitMs) expires after first item arrives
     */
    private void syncLoop() {
        List<WriteRequest> batch = new ArrayList<>();
        ByteBuffer writeBuffer = ByteBuffer.allocateDirect(config.getMaxBatchSize() + 1024 * 1024);

        while (running || !pendingWrites.isEmpty()) {
            try {
                // Wait for at least one item to start a batch
                boolean hasRequests = waitForFirstItem();

                if (!hasRequests && !running) {
                    break;
                }

                // Collect batch with timeout
                batch.clear();
                int batchBytes = collectBatch(batch);

                if (batch.isEmpty()) {
                    continue;
                }

                // Sort by (threadId, sequenceNum) to maintain per-thread order
                Collections.sort(batch);

                // Write batch to file
                writeBatch(batch, writeBuffer);

                // Release queue capacity
                queueCapacity.release(batch.size());

                // Update statistics
                totalWrites.addAndGet(batch.size());
                totalBytes.addAndGet(batchBytes);
                totalSyncs.incrementAndGet();

            } catch (Exception e) {
                // Complete all pending requests with error
                for (WriteRequest req : batch) {
                    req.completeExceptionally(e);
                }
                batch.clear();

                if (!running) {
                    break;
                }
            }
        }
    }

    /**
     * Wait for at least one item to arrive in the queue.
     * Returns true if there is work to do.
     */
    private boolean waitForFirstItem() throws InterruptedException {
        syncLock.lock();
        try {
            // Wait indefinitely for first item (or until shutdown)
            while (pendingWrites.isEmpty() && running) {
                hasWork.await();
            }
            return !pendingWrites.isEmpty();
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * Collect items into a batch until either:
     * 1. Batch size reaches maxBatchSize
     * 2. Timeout (maxBatchWaitMs) expires
     *
     * @param batch List to collect items into
     * @return Total bytes in the batch
     */
    private int collectBatch(List<WriteRequest> batch) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + (config.getMaxBatchWaitMs() * 1_000_000L);
        int batchBytes = 0;

        while (running || !pendingWrites.isEmpty()) {
            // 1. Drain all currently available items
            batchBytes = drainAvailableItems(batch, batchBytes);

            // 2. Check if we should flush now
            if (shouldFlushBatch(batch, batchBytes, deadlineNanos)) {
                return batchBytes;
            }

            // 3. Wait for more items or timeout
            if (!waitForMoreItems(batch, deadlineNanos)) {
                break;  // Shutdown requested with empty batch
            }
        }

        return batchBytes;
    }

    /**
     * Drain all immediately available items from the queue into the batch.
     * @return Updated batch bytes count
     */
    private int drainAvailableItems(List<WriteRequest> batch, int currentBytes) {
        WriteRequest request;
        while ((request = pendingWrites.poll()) != null) {
            batch.add(request);
            currentBytes += RECORD_HEADER_SIZE + request.getData().length;

            if (currentBytes >= config.getMaxBatchSize()) {
                break;  // Batch is full
            }
        }
        return currentBytes;
    }

    /**
     * Determine if the batch should be flushed now.
     */
    private boolean shouldFlushBatch(List<WriteRequest> batch, int batchBytes, long deadlineNanos) {
        if (batch.isEmpty()) {
            return false;
        }
        // Flush if batch is full OR timeout expired
        return batchBytes >= config.getMaxBatchSize() || System.nanoTime() >= deadlineNanos;
    }

    /**
     * Wait for more items to arrive or until timeout.
     * @return true if we should continue collecting, false if shutdown with empty batch
     */
    private boolean waitForMoreItems(List<WriteRequest> batch, long deadlineNanos) throws InterruptedException {
        syncLock.lock();
        try {
            if (pendingWrites.isEmpty() && running) {
                long remainingNanos = deadlineNanos - System.nanoTime();

                if (batch.isEmpty()) {
                    // No items yet - wait indefinitely for first item
                    hasWork.await();
                } else if (remainingNanos > 0) {
                    // Have items - wait for more until timeout
                    hasWork.awaitNanos(remainingNanos);
                }
            }
            // Continue if running or there are items to process
            return running || !pendingWrites.isEmpty() || !batch.isEmpty();
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * Write a batch of requests to the file and fsync.
     */
    private void writeBatch(List<WriteRequest> batch, ByteBuffer buffer) throws IOException {
        buffer.clear();

        // Serialize all records into buffer
        for (WriteRequest request : batch) {
            byte[] data = request.getData();
            long threadId = request.getThreadId();

            // Calculate CRC over threadId + data
            CRC32 crc = new CRC32();
            ByteBuffer crcBuffer = ByteBuffer.allocate(8 + data.length);
            crcBuffer.putLong(threadId);
            crcBuffer.put(data);
            crc.update(crcBuffer.array());
            int crcValue = (int) crc.getValue();

            // Write record: [length][crc][threadId][data]
            int recordLength = 8 + data.length;  // threadId + data
            buffer.putInt(recordLength);
            buffer.putInt(crcValue);
            buffer.putLong(threadId);
            buffer.put(data);
        }

        // Write to file
        buffer.flip();
        while (buffer.hasRemaining()) {
            fileChannel.write(buffer);
        }

        // CRITICAL: fsync to ensure durability
        fileChannel.force(config.isSyncMetadata());

        // Complete all requests - they are now durable
        for (WriteRequest request : batch) {
            request.complete();
        }
    }

    /**
     * Write the file header for new files.
     */
    private void writeFileHeader() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_SIZE);
        header.putLong(MAGIC_NUMBER);      // Magic + version
        header.putInt(1);                   // Format version
        header.putInt(0);                   // Flags
        header.putLong(System.currentTimeMillis());  // Creation time
        header.putLong(0);                  // Reserved
        header.flip();

        fileChannel.write(header);
        fileChannel.force(true);
    }

    /**
     * Close the writer gracefully.
     * Waits for all pending writes to complete.
     */
    @Override
    public void close() throws IOException {
        running = false;

        // Signal sync thread to wake up
        signalSyncThread();

        // Wait for sync thread to finish
        try {
            syncThread.join(10000);  // Wait up to 10 seconds
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        fileChannel.close();
    }

    /**
     * Get statistics about this writer.
     */
    public Stats getStats() {
        return new Stats(totalWrites.get(), totalBytes.get(), totalSyncs.get());
    }

    /**
     * Get the file path.
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * Statistics about writer performance.
     */
    public static class Stats {
        public final long totalWrites;
        public final long totalBytes;
        public final long totalSyncs;
        public final double avgWritesPerSync;

        public Stats(long totalWrites, long totalBytes, long totalSyncs) {
            this.totalWrites = totalWrites;
            this.totalBytes = totalBytes;
            this.totalSyncs = totalSyncs;
            this.avgWritesPerSync = totalSyncs > 0 ? (double) totalWrites / totalSyncs : 0;
        }

        @Override
        public String toString() {
            return String.format(
                "Stats[writes=%d, bytes=%d, syncs=%d, avgPerSync=%.1f]",
                totalWrites, totalBytes, totalSyncs, avgWritesPerSync);
        }
    }

    // ==================== DEMO ====================

    public static void main(String[] args) throws Exception {
        System.out.println("=== DataWriter Demo ===\n");

        String testFile = "/tmp/datawriter_demo.log";
        Files.deleteIfExists(Paths.get(testFile));

        // Create writer
        WriterConfig config = WriterConfig.builder()
                .maxBatchWaitMs(2)
                .maxBatchSize(64 * 1024)
                .build();

        try (DataWriter writer = new DataWriter(testFile, config)) {
            int numThreads = 10;
            int writesPerThread = 100;

            System.out.println("Starting " + numThreads + " threads, " +
                             writesPerThread + " writes each...\n");

            long startTime = System.currentTimeMillis();

            // Launch threads
            Thread[] threads = new Thread[numThreads];
            for (int t = 0; t < numThreads; t++) {
                final int threadNum = t;
                threads[t] = new Thread(() -> {
                    try {
                        for (int i = 0; i < writesPerThread; i++) {
                            String msg = "Thread-" + threadNum + "-Write-" + i;
                            writer.push(msg.getBytes());
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                threads[t].start();
            }

            // Wait for all threads
            for (Thread t : threads) {
                t.join();
            }

            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("Completed in " + elapsed + " ms");
            System.out.println("Stats: " + writer.getStats());
            System.out.println("Throughput: " +
                (numThreads * writesPerThread * 1000 / elapsed) + " writes/sec");
        }

        // Verify file
        System.out.println("\nFile size: " + Files.size(Paths.get(testFile)) + " bytes");

        // Recovery demo
        System.out.println("\n=== Recovery Demo ===");
        LogRecovery.RecoveryResult result = LogRecovery.recover(testFile);
        System.out.println("Recovery: " + result);
    }
}
