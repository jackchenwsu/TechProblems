import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

public class SnapshotSetTest {

    private SnapshotSet<Integer> set;

    @BeforeEach
    void setUp() {
        set = new SnapshotSet<>();
    }

    // ==================== Basic Operations Tests ====================

    @Test
    void testAddAndContains() {
        set.add(1);
        assertTrue(set.contains(1));
    }

    @Test
    void testAddDuplicate() {
        set.add(1);
        set.add(1); // Should not throw
        assertTrue(set.contains(1));
    }

    @Test
    void testRemoveExisting() {
        set.add(1);
        set.remove(1);
        assertFalse(set.contains(1));
    }

    @Test
    void testRemoveNonExisting() {
        set.remove(1); // Should not throw
        assertFalse(set.contains(1));
    }

    @Test
    void testContainsOnEmpty() {
        assertFalse(set.contains(1));
    }

    // ==================== Iterator Basic Tests ====================

    @Test
    void testIteratorEmptySet() {
        Iterator<Integer> iter = set.iterator();
        assertFalse(iter.hasNext());
        assertThrows(NoSuchElementException.class, iter::next);
    }

    @Test
    void testIteratorSingleElement() {
        set.add(42);
        Iterator<Integer> iter = set.iterator();

        assertTrue(iter.hasNext());
        assertEquals(42, iter.next());
        assertFalse(iter.hasNext());
    }

    @Test
    void testIteratorMultipleElements() {
        set.add(1);
        set.add(2);
        set.add(3);

        Iterator<Integer> iter = set.iterator();
        List<Integer> result = new ArrayList<>();
        while (iter.hasNext()) {
            result.add(iter.next());
        }

        assertEquals(Arrays.asList(1, 2, 3), result);
    }

    @Test
    void testIteratorExhausted() {
        set.add(1);
        Iterator<Integer> iter = set.iterator();
        iter.next();

        assertThrows(NoSuchElementException.class, iter::next);
    }

    // ==================== Snapshot Isolation Tests ====================

    @Test
    void testIteratorIgnoresSubsequentAdd() {
        set.add(1);
        set.add(2);

        Iterator<Integer> iter = set.iterator();
        set.add(3); // Added after iterator created

        List<Integer> result = new ArrayList<>();
        while (iter.hasNext()) {
            result.add(iter.next());
        }

        assertEquals(Arrays.asList(1, 2), result);
        assertTrue(set.contains(3)); // But set does contain 3
    }

    @Test
    void testIteratorIgnoresSubsequentRemove() {
        set.add(1);
        set.add(2);
        set.add(3);

        Iterator<Integer> iter = set.iterator();
        set.remove(2); // Removed after iterator created

        List<Integer> result = new ArrayList<>();
        while (iter.hasNext()) {
            result.add(iter.next());
        }

        assertEquals(Arrays.asList(1, 2, 3), result); // Iterator still sees 2
        assertFalse(set.contains(2)); // But set no longer contains 2
    }

    @Test
    void testContainsReflectsRealTime() {
        set.add(1);
        set.add(2);

        Iterator<Integer> iter = set.iterator();
        set.remove(1);

        // Iterator still sees 1
        assertEquals(1, iter.next());
        // But contains reflects real-time state
        assertFalse(set.contains(1));
    }

    // ==================== Multiple Iterators Tests ====================

    @Test
    void testTwoIteratorsDifferentSnapshots() {
        set.add(1);
        set.add(2);

        Iterator<Integer> iter1 = set.iterator(); // Snapshot: {1, 2}

        set.add(3);
        set.remove(1);

        Iterator<Integer> iter2 = set.iterator(); // Snapshot: {2, 3}

        // iter1 sees {1, 2}
        List<Integer> result1 = new ArrayList<>();
        while (iter1.hasNext()) {
            result1.add(iter1.next());
        }
        assertEquals(Arrays.asList(1, 2), result1);

        // iter2 sees {2, 3}
        List<Integer> result2 = new ArrayList<>();
        while (iter2.hasNext()) {
            result2.add(iter2.next());
        }
        assertEquals(Arrays.asList(2, 3), result2);
    }

    @Test
    void testIteratorsAtDifferentPositions() {
        set.add(1);
        set.add(2);
        set.add(3);

        Iterator<Integer> iter1 = set.iterator();
        Iterator<Integer> iter2 = set.iterator();

        // Advance iter1 by 2
        iter1.next();
        iter1.next();

        // iter2 still at beginning
        assertEquals(1, iter2.next());

        // iter1 at position 3
        assertEquals(3, iter1.next());
    }

    // ==================== Re-add Semantics Tests ====================

    @Test
    void testReaddAppearsAtEnd() {
        set.add(1);
        set.add(2);
        set.add(3);
        set.remove(2);
        set.add(2); // Re-add should appear at end

        Iterator<Integer> iter = set.iterator();
        List<Integer> result = new ArrayList<>();
        while (iter.hasNext()) {
            result.add(iter.next());
        }

        assertEquals(Arrays.asList(1, 3, 2), result);
    }

    @Test
    void testOldIteratorSeesOldPosition() {
        set.add(1);
        set.add(2);
        set.add(3);

        Iterator<Integer> iter1 = set.iterator(); // Sees {1, 2, 3}

        set.remove(2);
        set.add(2); // Re-add at end

        Iterator<Integer> iter2 = set.iterator(); // Sees {1, 3, 2}

        // iter1 sees original order
        List<Integer> result1 = new ArrayList<>();
        while (iter1.hasNext()) {
            result1.add(iter1.next());
        }
        assertEquals(Arrays.asList(1, 2, 3), result1);

        // iter2 sees new order
        List<Integer> result2 = new ArrayList<>();
        while (iter2.hasNext()) {
            result2.add(iter2.next());
        }
        assertEquals(Arrays.asList(1, 3, 2), result2);
    }

    // ==================== Null Handling Tests ====================

    @Test
    void testAddNullThrows() {
        assertThrows(NullPointerException.class, () -> set.add(null));
    }

    @Test
    void testRemoveNullThrows() {
        assertThrows(NullPointerException.class, () -> set.remove(null));
    }

    @Test
    void testContainsNullThrows() {
        assertThrows(NullPointerException.class, () -> set.contains(null));
    }

    // ==================== Integration Test (README Example) ====================

    @Test
    void testReadmeExample() {
        // Start with elements [1, 2, 3]
        SnapshotSet<Integer> ss = new SnapshotSet<>(Arrays.asList(1, 2, 3));

        // Add element 4. Set is now {1, 2, 3, 4}
        ss.add(4);

        // Create the first iterator. It freezes the state: {1, 2, 3, 4}
        Iterator<Integer> iter1 = ss.iterator();
        assertEquals(1, iter1.next());
        assertEquals(2, iter1.next());

        // Remove 3 from the real set. Real set is now {1, 2, 4}
        ss.remove(3);

        // The iterator ignores the removal. It still sees the old snapshot.
        assertEquals(3, iter1.next()); // Still here!
        assertEquals(4, iter1.next());

        // Create a second iterator. It sees the new state: {1, 2, 4}
        Iterator<Integer> iter2 = ss.iterator();

        // Add 6 to the real set. Real set is now {1, 2, 4, 6}
        ss.add(6);

        // iter2 sees its own snapshot. It does not see 6.
        assertEquals(1, iter2.next());
        assertEquals(2, iter2.next());
        assertEquals(4, iter2.next());
        assertFalse(iter2.hasNext()); // 6 is not included

        // contains() always checks the real set
        assertFalse(ss.contains(3)); // It was removed
        assertTrue(ss.contains(6));  // It was added
    }

    // ==================== Constructor Tests ====================

    @Test
    void testConstructorWithCollection() {
        SnapshotSet<String> strSet = new SnapshotSet<>(Arrays.asList("a", "b", "c"));

        assertTrue(strSet.contains("a"));
        assertTrue(strSet.contains("b"));
        assertTrue(strSet.contains("c"));

        Iterator<String> iter = strSet.iterator();
        assertEquals("a", iter.next());
        assertEquals("b", iter.next());
        assertEquals("c", iter.next());
        assertFalse(iter.hasNext());
    }

    @Test
    void testDefaultConstructor() {
        SnapshotSet<Integer> emptySet = new SnapshotSet<>();
        assertFalse(emptySet.contains(1));
        assertFalse(emptySet.iterator().hasNext());
    }
}
