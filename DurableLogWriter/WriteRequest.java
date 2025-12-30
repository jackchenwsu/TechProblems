import java.util.concurrent.CompletableFuture;

/**
 * Represents a single write request from a thread.
 * Contains the data, thread identification, sequence number for ordering,
 * and a future to signal completion.
 */
public class WriteRequest implements Comparable<WriteRequest> {

    private final byte[] data;
    private final long threadId;
    private final long sequenceNum;
    private final CompletableFuture<Void> completion;
    private final long timestamp;

    public WriteRequest(byte[] data, long threadId, long sequenceNum) {
        this.data = data;
        this.threadId = threadId;
        this.sequenceNum = sequenceNum;
        this.completion = new CompletableFuture<>();
        this.timestamp = System.nanoTime();
    }

    public byte[] getData() {
        return data;
    }

    public long getThreadId() {
        return threadId;
    }

    public long getSequenceNum() {
        return sequenceNum;
    }

    public CompletableFuture<Void> getCompletion() {
        return completion;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Complete this request successfully.
     * This unblocks the thread waiting on push().
     */
    public void complete() {
        completion.complete(null);
    }

    /**
     * Complete this request with an error.
     * This causes push() to throw an exception.
     */
    public void completeExceptionally(Throwable ex) {
        completion.completeExceptionally(ex);
    }

    /**
     * Wait for this request to be durably written.
     * Blocks until fsync completes.
     */
    public void await() throws Exception {
        completion.get();
    }

    /**
     * Sort by (threadId, sequenceNum) to maintain per-thread ordering.
     */
    @Override
    public int compareTo(WriteRequest other) {
        int threadCompare = Long.compare(this.threadId, other.threadId);
        if (threadCompare != 0) {
            return threadCompare;
        }
        return Long.compare(this.sequenceNum, other.sequenceNum);
    }

    @Override
    public String toString() {
        return String.format("WriteRequest[tid=%d, seq=%d, size=%d]",
                             threadId, sequenceNum, data.length);
    }
}
