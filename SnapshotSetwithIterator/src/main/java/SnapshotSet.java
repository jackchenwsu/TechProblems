import java.util.*;

/**
 * A set data structure that supports snapshot-based iterators.
 * When an iterator is created, it captures a read-only snapshot of the set
 * at that moment. Subsequent modifications do not affect existing iterators.
 *
 * @param <T> the type of elements in the set
 */
public class SnapshotSet<T> {

    private static class Entry<T> {
        final T value;
        final long addedAt;
        long removedAt;

        Entry(T value, long addedAt, long removedAt) {
            this.value = value;
            this.addedAt = addedAt;
            this.removedAt = removedAt;
        }
    }

    private long currentVersion = 0;
    private final List<Entry<T>> elements = new ArrayList<>();
    private final Map<T, Integer> indexMap = new HashMap<>();

    public SnapshotSet() {
    }

    public SnapshotSet(Collection<T> initial) {
        for (T item : initial) {
            add(item);
        }
    }

    /**
     * Adds an element to the set. If the element already exists, does nothing.
     *
     * @param x the element to add
     * @throws NullPointerException if x is null
     */
    public void add(T x) {
        Objects.requireNonNull(x, "Null elements are not supported");

        Integer existingIndex = indexMap.get(x);
        if (existingIndex != null) {
            Entry<T> entry = elements.get(existingIndex);
            if (entry.removedAt == Long.MAX_VALUE) {
                return; // Already active, do nothing
            }
        }

        // Add as new entry (even if previously existed)
        currentVersion++;
        Entry<T> newEntry = new Entry<>(x, currentVersion, Long.MAX_VALUE);
        indexMap.put(x, elements.size());
        elements.add(newEntry);
    }

    /**
     * Removes an element from the set. If the element does not exist, does nothing.
     *
     * @param x the element to remove
     * @throws NullPointerException if x is null
     */
    public void remove(T x) {
        Objects.requireNonNull(x, "Null elements are not supported");

        Integer index = indexMap.get(x);
        if (index == null) return; // Not present

        Entry<T> entry = elements.get(index);
        if (entry.removedAt != Long.MAX_VALUE) return; // Already removed

        currentVersion++;
        entry.removedAt = currentVersion;
        indexMap.remove(x);
    }

    /**
     * Checks if an element exists in the current (real-time) state of the set.
     *
     * @param x the element to check
     * @return true if the element exists, false otherwise
     * @throws NullPointerException if x is null
     */
    public boolean contains(T x) {
        Objects.requireNonNull(x, "Null elements are not supported");

        Integer index = indexMap.get(x);
        if (index == null) return false;
        return elements.get(index).removedAt == Long.MAX_VALUE;
    }

    /**
     * Returns an iterator over the elements in this set's current snapshot.
     * The iterator will return elements in insertion order and will not
     * reflect any modifications made after this call.
     *
     * @return an iterator over the snapshot
     */
    public Iterator<T> iterator() {
        return new SnapshotIterator(currentVersion);
    }

    private class SnapshotIterator implements Iterator<T> {
        private final long snapshotVersion;
        private int currentIndex = 0;

        SnapshotIterator(long version) {
            this.snapshotVersion = version;
        }

        private void advanceToNextValid() {
            while (currentIndex < elements.size()) {
                Entry<T> entry = elements.get(currentIndex);
                if (isVisible(entry)) break;
                currentIndex++;
            }
        }

        private boolean isVisible(Entry<T> entry) {
            // Entry was added at or before snapshot, and not yet removed at snapshot time
            return entry.addedAt <= snapshotVersion
                && entry.removedAt > snapshotVersion;
        }

        @Override
        public boolean hasNext() {
            advanceToNextValid();
            return currentIndex < elements.size();
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return elements.get(currentIndex++).value;
        }
    }
}
