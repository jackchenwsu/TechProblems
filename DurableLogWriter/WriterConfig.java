/**
 * Configuration for the DataWriter.
 * Uses builder pattern for flexible configuration.
 */
public class WriterConfig {

    // Default values
    public static final int DEFAULT_MAX_BATCH_SIZE = 1024 * 1024;  // 1 MB
    public static final int DEFAULT_MAX_BATCH_WAIT_MS = 5;          // 5 milliseconds
    public static final int DEFAULT_MAX_QUEUE_SIZE = 100000;        // 100k pending requests
    public static final int DEFAULT_MAX_RECORD_SIZE = 16 * 1024 * 1024;  // 16 MB

    private final int maxBatchSize;
    private final int maxBatchWaitMs;
    private final int maxQueueSize;
    private final int maxRecordSize;
    private final boolean syncMetadata;

    private WriterConfig(Builder builder) {
        this.maxBatchSize = builder.maxBatchSize;
        this.maxBatchWaitMs = builder.maxBatchWaitMs;
        this.maxQueueSize = builder.maxQueueSize;
        this.maxRecordSize = builder.maxRecordSize;
        this.syncMetadata = builder.syncMetadata;
    }

    /**
     * Maximum bytes to collect in a batch before triggering fsync.
     */
    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    /**
     * Maximum time to wait for more writes before triggering fsync.
     */
    public int getMaxBatchWaitMs() {
        return maxBatchWaitMs;
    }

    /**
     * Maximum number of pending write requests.
     * If exceeded, push() will block until space is available.
     */
    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    /**
     * Maximum size of a single record.
     * Prevents memory issues from very large writes.
     */
    public int getMaxRecordSize() {
        return maxRecordSize;
    }

    /**
     * Whether to sync file metadata (timestamps, etc.) on fsync.
     * false uses fdatasync() which is faster.
     */
    public boolean isSyncMetadata() {
        return syncMetadata;
    }

    /**
     * Create a new builder with default values.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a config with all default values.
     */
    public static WriterConfig defaults() {
        return new Builder().build();
    }

    @Override
    public String toString() {
        return String.format(
            "WriterConfig[maxBatchSize=%d, maxBatchWaitMs=%d, maxQueueSize=%d, maxRecordSize=%d, syncMetadata=%b]",
            maxBatchSize, maxBatchWaitMs, maxQueueSize, maxRecordSize, syncMetadata);
    }

    public static class Builder {
        private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
        private int maxBatchWaitMs = DEFAULT_MAX_BATCH_WAIT_MS;
        private int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
        private int maxRecordSize = DEFAULT_MAX_RECORD_SIZE;
        private boolean syncMetadata = false;

        public Builder maxBatchSize(int maxBatchSize) {
            if (maxBatchSize <= 0) {
                throw new IllegalArgumentException("maxBatchSize must be positive");
            }
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        public Builder maxBatchWaitMs(int maxBatchWaitMs) {
            if (maxBatchWaitMs < 0) {
                throw new IllegalArgumentException("maxBatchWaitMs cannot be negative");
            }
            this.maxBatchWaitMs = maxBatchWaitMs;
            return this;
        }

        public Builder maxQueueSize(int maxQueueSize) {
            if (maxQueueSize <= 0) {
                throw new IllegalArgumentException("maxQueueSize must be positive");
            }
            this.maxQueueSize = maxQueueSize;
            return this;
        }

        public Builder maxRecordSize(int maxRecordSize) {
            if (maxRecordSize <= 0) {
                throw new IllegalArgumentException("maxRecordSize must be positive");
            }
            this.maxRecordSize = maxRecordSize;
            return this;
        }

        public Builder syncMetadata(boolean syncMetadata) {
            this.syncMetadata = syncMetadata;
            return this;
        }

        public WriterConfig build() {
            return new WriterConfig(this);
        }
    }
}
