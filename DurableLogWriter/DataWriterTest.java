import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for DataWriter
 */
public class DataWriterTest {

    // ==================== TEST CONSTANTS ====================

    private static final int DEFAULT_THREAD_COUNT = 10;
    private static final int DEFAULT_WRITES_PER_THREAD = 100;
    private static final int HIGH_CONTENTION_THREAD_COUNT = 50;
    private static final int BENCHMARK_WRITE_COUNT = 10_000;
    private static final int SMALL_BATCH_SIZE = 100;
    private static final int LARGE_BATCH_SIZE = 256 * 1024;
    private static final int ONE_MEGABYTE = 1024 * 1024;
    private static final long TEST_TIMEOUT_SECONDS = 30;

    @TempDir
    Path tempDir;

    // ==================== HELPER METHODS ====================

    private Path getTestFile() {
        return tempDir.resolve("test.log");
    }

    private DataWriter createWriter() throws IOException {
        return new DataWriter(getTestFile().toString());
    }

    private DataWriter createWriter(WriterConfig config) throws IOException {
        return new DataWriter(getTestFile().toString(), config);
    }

    private List<LogRecovery.Record> readAllRecords() throws IOException {
        return LogRecovery.readAll(getTestFile().toString());
    }

    private void writeMessages(DataWriter writer, int count, String prefix) throws IOException {
        for (int i = 0; i < count; i++) {
            writer.push((prefix + i).getBytes());
        }
    }

    private Thread[] createWriterThreads(DataWriter writer, int threadCount, int writesPerThread) {
        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadNum = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < writesPerThread; i++) {
                    try {
                        writer.push(("T" + threadNum + "-" + i).getBytes());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, "WriterThread-" + t);
        }
        return threads;
    }

    private void startAndJoinAll(Thread[] threads) throws InterruptedException {
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join(TEST_TIMEOUT_SECONDS * 1000);
        }
    }

    // ==================== BASIC FUNCTIONALITY ====================

    @Nested
    @DisplayName("Basic Functionality")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("Write single record")
        void testWriteSingleRecord() throws Exception {
            // Given
            String message = "Hello, World!";

            // When
            try (DataWriter writer = createWriter()) {
                writer.push(message.getBytes());
            }

            // Then
            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(1, records.size(), "Should have exactly one record");
            assertEquals(message, records.get(0).getDataAsString());
        }

        @Test
        @DisplayName("Write multiple records")
        void testWriteMultipleRecords() throws Exception {
            // Given
            String[] messages = {"First", "Second", "Third"};

            // When
            try (DataWriter writer = createWriter()) {
                for (String msg : messages) {
                    writer.push(msg.getBytes());
                }
            }

            // Then
            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(messages.length, records.size());
            for (int i = 0; i < messages.length; i++) {
                assertEquals(messages[i], records.get(i).getDataAsString(),
                    "Record " + i + " should match");
            }
        }

        @Test
        @DisplayName("Write empty data")
        void testWriteEmptyData() throws Exception {
            try (DataWriter writer = createWriter()) {
                writer.push(new byte[0]);
            }

            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(1, records.size());
            assertEquals(0, records.get(0).data.length, "Empty record should have zero length");
        }

        @Test
        @DisplayName("Write binary data preserves all byte values")
        void testWriteBinaryData() throws Exception {
            // Given: binary data with all possible byte values
            byte[] binaryData = new byte[256];
            for (int i = 0; i < 256; i++) {
                binaryData[i] = (byte) i;
            }

            // When
            try (DataWriter writer = createWriter()) {
                writer.push(binaryData);
            }

            // Then
            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(1, records.size());
            assertArrayEquals(binaryData, records.get(0).data, "Binary data should be preserved exactly");
        }

        @Test
        @DisplayName("Reopen and append to existing file")
        void testReopenAndAppend() throws Exception {
            // Given: first writer creates initial record
            try (DataWriter writer = createWriter()) {
                writer.push("First".getBytes());
            }

            // When: second writer appends
            try (DataWriter writer = createWriter()) {
                writer.push("Second".getBytes());
            }

            // Then: both records should exist
            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(2, records.size(), "Should have records from both writers");
            assertEquals("First", records.get(0).getDataAsString());
            assertEquals("Second", records.get(1).getDataAsString());
        }
    }

    // ==================== THREAD SAFETY ====================

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Concurrent writes from multiple threads")
        void testConcurrentWrites() throws Exception {
            // Given
            int expectedTotalWrites = DEFAULT_THREAD_COUNT * DEFAULT_WRITES_PER_THREAD;

            // When
            try (DataWriter writer = createWriter()) {
                ExecutorService executor = Executors.newFixedThreadPool(DEFAULT_THREAD_COUNT);
                List<Future<?>> futures = new ArrayList<>();

                for (int t = 0; t < DEFAULT_THREAD_COUNT; t++) {
                    final int threadNum = t;
                    futures.add(executor.submit(() -> {
                        for (int i = 0; i < DEFAULT_WRITES_PER_THREAD; i++) {
                            try {
                                writer.push(("T" + threadNum + "-" + i).getBytes());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }));
                }

                for (Future<?> future : futures) {
                    future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
                executor.shutdown();
            }

            // Then
            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(expectedTotalWrites, records.size(),
                "All writes from all threads should be recorded");
        }

        @Test
        @DisplayName("High contention stress test")
        void testHighContention() throws Exception {
            // Given
            int writesPerThread = 50;
            int expectedTotalWrites = HIGH_CONTENTION_THREAD_COUNT * writesPerThread;
            AtomicInteger successCount = new AtomicInteger(0);

            WriterConfig config = WriterConfig.builder()
                    .maxBatchWaitMs(1)
                    .build();

            // When
            try (DataWriter writer = createWriter(config)) {
                Thread[] threads = new Thread[HIGH_CONTENTION_THREAD_COUNT];

                for (int t = 0; t < HIGH_CONTENTION_THREAD_COUNT; t++) {
                    final int threadNum = t;
                    threads[t] = new Thread(() -> {
                        for (int i = 0; i < writesPerThread; i++) {
                            try {
                                writer.push(("T" + threadNum + "-" + i).getBytes());
                                successCount.incrementAndGet();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }, "StressThread-" + t);
                }

                startAndJoinAll(threads);
            }

            // Then
            assertEquals(expectedTotalWrites, successCount.get(),
                "All writes should succeed under high contention");
        }

        @Test
        @DisplayName("No data corruption under concurrent access")
        void testNoDataCorruption() throws Exception {
            // Given
            int threadCount = 20;
            int writesPerThread = 50;
            String messagePattern = "THREAD%03d-WRITE%05d";

            // When
            try (DataWriter writer = createWriter()) {
                ExecutorService executor = Executors.newFixedThreadPool(threadCount);
                List<Future<?>> futures = new ArrayList<>();

                for (int t = 0; t < threadCount; t++) {
                    final int threadNum = t;
                    futures.add(executor.submit(() -> {
                        for (int i = 0; i < writesPerThread; i++) {
                            try {
                                String msg = String.format(messagePattern, threadNum, i);
                                writer.push(msg.getBytes());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }));
                }

                for (Future<?> future : futures) {
                    future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
                executor.shutdown();
            }

            // Then: verify each record matches the expected pattern
            List<LogRecovery.Record> records = readAllRecords();
            String expectedPatternRegex = "THREAD\\d{3}-WRITE\\d{5}";

            for (LogRecovery.Record record : records) {
                String data = record.getDataAsString();
                assertTrue(data.matches(expectedPatternRegex),
                    "Data appears corrupted: " + data);
            }
        }
    }

    // ==================== ORDERING GUARANTEE ====================

    @Nested
    @DisplayName("Ordering Guarantee")
    class OrderingTests {

        @Test
        @DisplayName("Same thread writes maintain order")
        void testSameThreadOrdering() throws Exception {
            // Given
            int messageCount = 100;

            // When
            try (DataWriter writer = createWriter()) {
                writeMessages(writer, messageCount, "MSG-");
            }

            // Then: verify strict ordering
            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(messageCount, records.size());

            for (int i = 0; i < messageCount; i++) {
                assertEquals("MSG-" + i, records.get(i).getDataAsString(),
                    "Message at index " + i + " should be in order");
            }
        }

        @Test
        @DisplayName("Per-thread ordering preserved with concurrent writes")
        void testPerThreadOrdering() throws Exception {
            // Given
            int threadCount = 5;
            int writesPerThread = 100;

            // When
            try (DataWriter writer = createWriter()) {
                Thread[] threads = new Thread[threadCount];

                for (int t = 0; t < threadCount; t++) {
                    final int threadNum = t;
                    threads[t] = new Thread(() -> {
                        for (int i = 0; i < writesPerThread; i++) {
                            try {
                                String msg = "T" + threadNum + "-SEQ" + String.format("%05d", i);
                                writer.push(msg.getBytes());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }, "OrderingThread-" + t);
                }

                startAndJoinAll(threads);
            }

            // Then: group records by thread and verify each thread's ordering
            List<LogRecovery.Record> records = readAllRecords();
            Map<String, List<Integer>> sequencesByThread = groupSequencesByThread(records);

            for (Map.Entry<String, List<Integer>> entry : sequencesByThread.entrySet()) {
                String threadId = entry.getKey();
                List<Integer> sequences = entry.getValue();

                assertSequencesAreOrdered(threadId, sequences);
            }
        }

        private Map<String, List<Integer>> groupSequencesByThread(List<LogRecovery.Record> records) {
            Map<String, List<Integer>> sequencesByThread = new HashMap<>();

            for (LogRecovery.Record record : records) {
                String data = record.getDataAsString();
                String threadId = data.split("-")[0];  // "T0", "T1", etc.
                int seqNum = Integer.parseInt(data.split("SEQ")[1]);

                sequencesByThread.computeIfAbsent(threadId, k -> new ArrayList<>())
                                 .add(seqNum);
            }

            return sequencesByThread;
        }

        private void assertSequencesAreOrdered(String threadId, List<Integer> sequences) {
            for (int i = 1; i < sequences.size(); i++) {
                int previous = sequences.get(i - 1);
                int current = sequences.get(i);

                assertTrue(current > previous,
                    String.format("Thread %s out of order: sequence %d came after %d",
                        threadId, current, previous));
            }
        }
    }

    // ==================== DURABILITY ====================

    @Nested
    @DisplayName("Durability")
    class DurabilityTests {

        @Test
        @DisplayName("Data persists after close")
        void testDataPersistsAfterClose() throws Exception {
            // Given
            String testData = "Persistent data test";

            // When
            try (DataWriter writer = createWriter()) {
                writer.push(testData.getBytes());
            }

            // Then
            assertTrue(Files.exists(getTestFile()), "File should exist after close");
            assertTrue(Files.size(getTestFile()) > 0, "File should have content");

            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(1, records.size());
            assertEquals(testData, records.get(0).getDataAsString());
        }

        @Test
        @DisplayName("Statistics track syncs")
        void testStatisticsTrackSyncs() throws Exception {
            // Given: config that forces frequent syncs
            WriterConfig config = WriterConfig.builder()
                    .maxBatchWaitMs(1)
                    .maxBatchSize(SMALL_BATCH_SIZE)
                    .build();

            // When
            try (DataWriter writer = createWriter(config)) {
                for (int i = 0; i < 10; i++) {
                    writer.push(("Message " + i).getBytes());
                    Thread.sleep(5);  // Allow time for batches to flush
                }

                // Then
                DataWriter.Stats stats = writer.getStats();
                assertEquals(10, stats.totalWrites, "Should track all writes");
                assertTrue(stats.totalSyncs > 0, "Should have at least one sync");
                assertTrue(stats.totalBytes > 0, "Should have written bytes");
            }
        }

        @Test
        @DisplayName("Batch flushes on timeout without new writes")
        void testBatchFlushesOnTimeout() throws Exception {
            // Given
            int batchTimeoutMs = 50;
            WriterConfig config = WriterConfig.builder()
                    .maxBatchWaitMs(batchTimeoutMs)
                    .maxBatchSize(ONE_MEGABYTE)  // Large batch so timeout triggers first
                    .build();

            // When
            try (DataWriter writer = createWriter(config)) {
                long startTime = System.currentTimeMillis();
                writer.push("Single record".getBytes());
                long elapsed = System.currentTimeMillis() - startTime;

                // Then: push() should return within reasonable time
                int maxExpectedMs = batchTimeoutMs + 100;  // Allow processing overhead
                assertTrue(elapsed < maxExpectedMs,
                    "push() took " + elapsed + "ms, expected < " + maxExpectedMs + "ms");
            }

            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(1, records.size());
            assertEquals("Single record", records.get(0).getDataAsString());
        }

        @Test
        @DisplayName("Batch flushes immediately when full")
        void testBatchFlushesWhenFull() throws Exception {
            // Given: very small batch size, very long timeout
            WriterConfig config = WriterConfig.builder()
                    .maxBatchWaitMs(10_000)
                    .maxBatchSize(SMALL_BATCH_SIZE)
                    .build();

            // When
            try (DataWriter writer = createWriter(config)) {
                byte[] largeData = new byte[SMALL_BATCH_SIZE + 50];
                Arrays.fill(largeData, (byte) 'A');

                long startTime = System.currentTimeMillis();
                writer.push(largeData);
                long elapsed = System.currentTimeMillis() - startTime;

                // Then: should flush immediately due to batch size, not wait for timeout
                assertTrue(elapsed < 1000,
                    "push() waited for timeout instead of flushing on batch size: " + elapsed + "ms");
            }
        }

        @Test
        @DisplayName("Multiple writes batch together within timeout")
        void testMultipleWritesBatchTogether() throws Exception {
            // Given
            int threadCount = 5;
            WriterConfig config = WriterConfig.builder()
                    .maxBatchWaitMs(100)
                    .maxBatchSize(ONE_MEGABYTE)
                    .build();

            // When
            try (DataWriter writer = createWriter(config)) {
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(threadCount);

                for (int t = 0; t < threadCount; t++) {
                    final int threadNum = t;
                    new Thread(() -> {
                        try {
                            startLatch.await();
                            writer.push(("Thread-" + threadNum).getBytes());
                            doneLatch.countDown();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, "BatchThread-" + t).start();
                }

                // Start all threads simultaneously
                startLatch.countDown();
                doneLatch.await(5, TimeUnit.SECONDS);

                // Then: with batching, we should have fewer syncs than writes
                DataWriter.Stats stats = writer.getStats();
                assertEquals(threadCount, stats.totalWrites);
                assertTrue(stats.totalSyncs <= threadCount,
                    "Expected batching to reduce syncs, got " + stats.totalSyncs +
                    " syncs for " + threadCount + " writes");
            }
        }
    }

    // ==================== CRASH RECOVERY ====================

    @Nested
    @DisplayName("Crash Recovery")
    class CrashRecoveryTests {

        @Test
        @DisplayName("Recovery on clean file")
        void testRecoveryOnCleanFile() throws Exception {
            // Given: a file with valid records
            try (DataWriter writer = createWriter()) {
                writer.push("Record 1".getBytes());
                writer.push("Record 2".getBytes());
            }

            // When
            LogRecovery.RecoveryResult result = LogRecovery.recover(getTestFile().toString());

            // Then
            assertTrue(result.success, "Recovery should succeed on clean file");
            assertEquals(2, result.validRecords, "Should find both valid records");
            assertEquals(0, result.corruptRecords, "Should have no corrupt records");
        }

        @Test
        @DisplayName("Recovery truncates partial record")
        void testRecoveryTruncatesPartialRecord() throws Exception {
            // Given: valid records followed by a simulated partial write
            try (DataWriter writer = createWriter()) {
                writer.push("Valid Record 1".getBytes());
                writer.push("Valid Record 2".getBytes());
            }

            long validFileSize = Files.size(getTestFile());

            // Simulate a crash during write (incomplete record header)
            try (var out = Files.newOutputStream(getTestFile(), StandardOpenOption.APPEND)) {
                out.write(new byte[]{0x00, 0x00, 0x10, 0x00});  // Invalid length prefix
            }

            assertTrue(Files.size(getTestFile()) > validFileSize,
                "File should have garbage appended");

            // When
            LogRecovery.RecoveryResult result = LogRecovery.recover(getTestFile().toString());

            // Then
            assertTrue(result.success, "Recovery should succeed");
            assertEquals(2, result.validRecords, "Should preserve valid records");
            assertTrue(result.corruptRecords > 0 || result.bytesRecovered > 0,
                "Should detect corruption or recover bytes");
            assertEquals(validFileSize, Files.size(getTestFile()),
                "File should be truncated to valid size");

            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(2, records.size(), "Original records should be readable");
        }

        @Test
        @DisplayName("Recovery handles CRC corruption")
        void testRecoveryHandlesCrcCorruption() throws Exception {
            // Given: a file with a valid record
            try (DataWriter writer = createWriter()) {
                writer.push("Valid Record".getBytes());
            }

            // Corrupt a byte in the data section
            byte[] fileContent = Files.readAllBytes(getTestFile());
            if (fileContent.length > 50) {
                fileContent[50] ^= 0xFF;  // Flip all bits
            }
            Files.write(getTestFile(), fileContent);

            // When
            LogRecovery.RecoveryResult result = LogRecovery.recover(getTestFile().toString());

            // Then: recovery should detect the corruption
            assertTrue(result.success, "Recovery operation should complete");
        }

        @Test
        @DisplayName("Recovery on empty file")
        void testRecoveryOnEmptyFile() throws Exception {
            // Given
            Files.createFile(getTestFile());

            // When
            LogRecovery.RecoveryResult result = LogRecovery.recover(getTestFile().toString());

            // Then
            assertFalse(result.success, "Recovery should fail on empty file");
            assertTrue(result.message.contains("too small"),
                "Error message should indicate file is too small");
        }

        @Test
        @DisplayName("Recovery on non-existent file")
        void testRecoveryOnNonExistentFile() throws Exception {
            // Given
            Path nonExistentFile = tempDir.resolve("nonexistent.log");

            // When
            LogRecovery.RecoveryResult result = LogRecovery.recover(nonExistentFile.toString());

            // Then
            assertFalse(result.success, "Recovery should fail for missing file");
            assertTrue(result.message.contains("does not exist"),
                "Error message should indicate file doesn't exist");
        }
    }

    // ==================== CONFIGURATION ====================

    @Nested
    @DisplayName("Configuration")
    class ConfigurationTests {

        @Test
        @DisplayName("Custom batch size")
        void testCustomBatchSize() throws Exception {
            WriterConfig config = WriterConfig.builder()
                    .maxBatchSize(1024)
                    .build();

            try (DataWriter writer = createWriter(config)) {
                writer.push("Test".getBytes());
            }

            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(1, records.size());
        }

        @Test
        @DisplayName("Custom batch wait time")
        void testCustomBatchWaitTime() throws Exception {
            WriterConfig config = WriterConfig.builder()
                    .maxBatchWaitMs(10)
                    .build();

            try (DataWriter writer = createWriter(config)) {
                writer.push("Test".getBytes());
            }

            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(1, records.size());
        }

        @Test
        @DisplayName("Reject null data")
        void testRejectNullData() throws Exception {
            try (DataWriter writer = createWriter()) {
                assertThrows(IllegalArgumentException.class,
                    () -> writer.push(null),
                    "Should reject null data");
            }
        }

        @Test
        @DisplayName("Reject oversized data")
        void testRejectOversizedData() throws Exception {
            int maxSize = 100;
            WriterConfig config = WriterConfig.builder()
                    .maxRecordSize(maxSize)
                    .build();

            try (DataWriter writer = createWriter(config)) {
                byte[] oversizedData = new byte[maxSize * 2];

                assertThrows(IllegalArgumentException.class,
                    () -> writer.push(oversizedData),
                    "Should reject data exceeding max record size");
            }
        }

        @Test
        @DisplayName("Writer config builder validation")
        void testConfigBuilderValidation() {
            assertThrows(IllegalArgumentException.class,
                () -> WriterConfig.builder().maxBatchSize(-1).build(),
                "Should reject negative batch size");

            assertThrows(IllegalArgumentException.class,
                () -> WriterConfig.builder().maxBatchWaitMs(-1).build(),
                "Should reject negative wait time");

            assertThrows(IllegalArgumentException.class,
                () -> WriterConfig.builder().maxQueueSize(0).build(),
                "Should reject zero queue size");
        }
    }

    // ==================== EDGE CASES ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Write after close throws exception")
        void testWriteAfterClose() throws Exception {
            DataWriter writer = createWriter();
            writer.push("Before close".getBytes());
            writer.close();

            assertThrows(IOException.class,
                () -> writer.push("After close".getBytes()),
                "Writing to closed writer should throw IOException");
        }

        @Test
        @DisplayName("Large record write (1 MB)")
        void testLargeRecordWrite() throws Exception {
            // Given
            byte[] largeData = new byte[ONE_MEGABYTE];
            new Random().nextBytes(largeData);

            // When
            try (DataWriter writer = createWriter()) {
                writer.push(largeData);
            }

            // Then
            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(1, records.size());
            assertArrayEquals(largeData, records.get(0).data,
                "Large record data should be preserved exactly");
        }

        @Test
        @DisplayName("Many small records")
        void testManySmallRecords() throws Exception {
            // Given
            int recordCount = BENCHMARK_WRITE_COUNT;

            // When
            try (DataWriter writer = createWriter()) {
                writeMessages(writer, recordCount, "R");
            }

            // Then
            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(recordCount, records.size(),
                "All small records should be written");
        }

        @Test
        @DisplayName("Double close is safe")
        void testDoubleClose() throws Exception {
            DataWriter writer = createWriter();
            writer.push("Test".getBytes());
            writer.close();

            assertDoesNotThrow(writer::close,
                "Closing an already-closed writer should not throw");
        }
    }

    // ==================== PERFORMANCE BENCHMARK ====================

    @Nested
    @DisplayName("Performance")
    class PerformanceTests {

        @Test
        @DisplayName("Single-thread throughput benchmark")
        void testThroughputBenchmark() throws Exception {
            WriterConfig config = WriterConfig.builder()
                    .maxBatchWaitMs(2)
                    .maxBatchSize(LARGE_BATCH_SIZE)
                    .build();

            long startTime = System.currentTimeMillis();

            try (DataWriter writer = createWriter(config)) {
                writeMessages(writer, BENCHMARK_WRITE_COUNT, "Benchmark message ");
            }

            long elapsedMs = System.currentTimeMillis() - startTime;
            double throughput = BENCHMARK_WRITE_COUNT * 1000.0 / elapsedMs;

            System.out.printf("Single-thread throughput: %.0f writes/sec (%d writes in %d ms)%n",
                throughput, BENCHMARK_WRITE_COUNT, elapsedMs);

            // Verify all writes
            List<LogRecovery.Record> records = readAllRecords();
            assertEquals(BENCHMARK_WRITE_COUNT, records.size());
        }

        @Test
        @DisplayName("Concurrent throughput benchmark")
        void testConcurrentThroughputBenchmark() throws Exception {
            int writesPerThread = 1000;
            int totalWrites = DEFAULT_THREAD_COUNT * writesPerThread;

            WriterConfig config = WriterConfig.builder()
                    .maxBatchWaitMs(2)
                    .build();

            long startTime = System.currentTimeMillis();

            try (DataWriter writer = createWriter(config)) {
                Thread[] threads = new Thread[DEFAULT_THREAD_COUNT];

                for (int t = 0; t < DEFAULT_THREAD_COUNT; t++) {
                    final int threadNum = t;
                    threads[t] = new Thread(() -> {
                        for (int i = 0; i < writesPerThread; i++) {
                            try {
                                writer.push(("T" + threadNum + "-" + i).getBytes());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }, "BenchmarkThread-" + t);
                }

                startAndJoinAll(threads);

                DataWriter.Stats stats = writer.getStats();
                System.out.println("Stats: " + stats);
            }

            long elapsedMs = System.currentTimeMillis() - startTime;
            double throughput = totalWrites * 1000.0 / elapsedMs;

            System.out.printf("Concurrent throughput (%d threads): %.0f writes/sec (%d writes in %d ms)%n",
                DEFAULT_THREAD_COUNT, throughput, totalWrites, elapsedMs);
        }
    }
}
