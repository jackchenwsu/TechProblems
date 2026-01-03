# SnapshotSet with Iterator

## Problem Statement

Design a data structure called `SnapshotSet<T>`. This is like a normal set, but it has a special feature: **snapshot-based iterators**.

The main rule is simple: When you ask for an iterator, it must take a read-only picture (snapshot) of the set at that exact moment. If you add or remove items from the set after creating the iterator, the iterator should not see those changes.

## Methods to Implement

You need to write code for these four methods:

| Method | Description |
|--------|-------------|
| `add(x)` | Put element `x` into the set. If it is already there, do nothing. |
| `remove(x)` | Take element `x` out of the set. If it is not there, do nothing. |
| `contains(x)` | Return `true` if `x` is in the set right now, `false` if it is not. |
| `iterator()` | Return an object that loops through the items in the current snapshot. |

## Rules for the Iterator

- **Snapshot Isolation**: The iterator remembers the set exactly as it was when created.
- **Insertion Order**: It must return items in the order they were first added.
- **Immutable View**: Changes made later (using `add` or `remove`) must not change what the iterator sees.
- **Standard Interface**: It needs `hasNext()` (is there more?) and `next()` (get the next item).

> **Note**: The `contains()` method always checks the real-time state of the set, not the snapshot.

## Usage Example

```python
# Start with elements [1, 2, 3]
ss = SnapshotSet([1, 2, 3])

# Add element 4. Set is now {1, 2, 3, 4}
ss.add(4)

# Create the first iterator. It freezes the state: {1, 2, 3, 4}
iter1 = ss.iterator()
print(iter1.next())            # Output: 1
print(iter1.next())            # Output: 2

# Remove 3 from the real set. Real set is now {1, 2, 4}
ss.remove(3)

# The iterator ignores the removal. It still sees the old snapshot.
print(iter1.next())            # Output: 3 (Still here!)
print(iter1.next())            # Output: 4

# Create a second iterator. It sees the new state: {1, 2, 4}
iter2 = ss.iterator()

# Add 6 to the real set. Real set is now {1, 2, 4, 6}
ss.add(6)

# iter2 sees its own snapshot. It does not see 6.
print(iter2.next())            # Output: 1
print(iter2.next())            # Output: 2
print(iter2.next())            # Output: 4
print(iter2.hasNext())         # Output: False (6 is not included)

# contains() always checks the real set
print(ss.contains(3))          # Output: False (It was removed)
print(ss.contains(6))          # Output: True (It was added)
```

## Technical Constraints

- Items can be compared and hashed.
- Number of operations is up to 10^5.
- Operations should be fast. Avoid O(n) if possible.
- Multiple iterators might run at the same time.
- Iterators might be at different stages.
