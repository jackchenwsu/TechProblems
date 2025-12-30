# FindOptimalCommute

A route planner for finding the optimal transportation mode in a multi-modal city graph using BFS shortest path algorithm.

## Problem Statement

Given a city with:
- **N** intersections (nodes)
- **M** roads (edges)
- **K** transportation modes (e.g., Walk, Bike, Drive)
- Source **S** and Destination **D**

Find the **optimal transportation mode** to travel from S to D based on:
- **Primary criterion**: Minimum total travel time
- **Secondary criterion**: Minimum total cost (tie-breaker)

### Key Insight

Different transportation modes may have access to different roads:
- A pedestrian bridge might only allow walking
- A highway might only allow driving
- Each mode has different speed (time per edge) and cost per edge

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         City Graph                                   │
│                                                                      │
│     Node 0 ─────── Node 1 ─────── Node 2                            │
│       │             │               │                                │
│       │   walk      │  all modes    │   all modes                   │
│       │   only      │               │                                │
│       │             │               │                                │
│     Node 3 ─────── Node 4 ─────── Node 5                            │
│                                                                      │
│  Each mode sees a different subgraph based on road restrictions     │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    Mode-Specific Graphs                              │
│                                                                      │
│  Walk Graph         Bike Graph          Drive Graph                  │
│  (all roads)        (no walk-only)      (no walk-only)              │
│                                                                      │
│  0───1───2          0───1───2           0───1───2                   │
│  │   │   │              │   │               │   │                   │
│  3───4───5          3───4───5           3───4───5                   │
└─────────────────────────────────────────────────────────────────────┘
```

## Algorithm

### Core Approach: BFS per Mode

Since each edge within a mode has uniform weight, **BFS guarantees the shortest path** (minimum edge count).

```
findOptimalMode(source, destination):
    │
    ├──▶ For each mode k in [0, K):
    │       │
    │       ├── Run BFS on mode k's graph
    │       │   Returns: edgeCount (or -1 if unreachable)
    │       │
    │       ├── If reachable:
    │       │   totalTime = edgeCount × timePerEdge[k]
    │       │   totalCost = edgeCount × costPerEdge[k]
    │       │
    │       └── Update optimal if:
    │           - totalTime < bestTime, OR
    │           - totalTime == bestTime AND totalCost < bestCost
    │
    └──▶ Return optimal mode with metrics
```

### BFS Implementation

```
bfs(start, end, mode):
    │
    ├── If start == end: return 0
    │
    ├── Initialize:
    │   - visited[n] = false
    │   - queue = [(start, distance=0)]
    │   - visited[start] = true
    │
    └── While queue not empty:
            │
            ├── (node, dist) = queue.poll()
            │
            └── For each neighbor in adjacencyList[mode][node]:
                    │
                    ├── If neighbor == end: return dist + 1  ◀── Found!
                    │
                    └── If not visited:
                        visited[neighbor] = true
                        queue.add((neighbor, dist + 1))

    return -1  ◀── Unreachable
```

### Time Complexity

| Operation | Complexity | Description |
|-----------|------------|-------------|
| BFS (single mode) | O(N + M) | Visit each node and edge once |
| findOptimalMode | O(K × (N + M)) | K BFS traversals |
| Space | O(K × N × avg_degree) | Adjacency lists for all modes |

Where:
- **N** = number of intersections
- **M** = number of roads
- **K** = number of transportation modes

---

## Class: RoutePlanner

### Data Structures

```java
// Number of nodes and modes
private int n;  // Intersections (0 to n-1)
private int k;  // Transportation modes (0 to k-1)

// Graph representation: adjacencyList[mode][node] → List<neighbor>
private List<List<Integer>>[] adjacencyList;

// Per-mode parameters
private int[] timePerEdge;   // Time to traverse one edge
private int[] costPerEdge;   // Cost to traverse one edge
private String[] modeNames;  // Human-readable names
```

### Why Separate Graphs per Mode?

Different modes may have access to different roads:

```
Road 0-3: Walk only (pedestrian path)
Road 0-1: All modes (regular street)

adjacencyList[WALK][0] = [1, 3]    // Walk can use both roads
adjacencyList[BIKE][0] = [1]       // Bike can only use road 0-1
adjacencyList[DRIVE][0] = [1]      // Drive can only use road 0-1
```

### Constructor

```java
public RoutePlanner(int n, int k)
```

Creates a route planner with:
- **n** intersections (nodes 0 to n-1)
- **k** transportation modes (modes 0 to k-1)
- Empty adjacency lists for each mode

### Methods

#### Graph Building

```java
// Add a bidirectional road between u and v for specified modes
public void addRoad(int u, int v, List<Integer> allowedModes)
```

**Example:**
```java
// Road between 0 and 1, usable by Walk (0) and Bike (1)
planner.addRoad(0, 1, Arrays.asList(0, 1));

// Pedestrian-only bridge between 1 and 2
planner.addRoad(1, 2, Arrays.asList(0));  // Walk only
```

#### Mode Configuration

```java
// Set time and cost per edge for a mode
public void setModeParams(int mode, int time, int cost)

// Set human-readable name
public void setModeName(int mode, String name)

// Get mode name
public String getModeName(int mode)
```

**Example:**
```java
planner.setModeName(0, "Walk");
planner.setModeParams(0, 10, 0);  // 10 time units, free

planner.setModeName(1, "Drive");
planner.setModeParams(1, 2, 10);  // 2 time units, $10 per edge
```

#### Route Finding

```java
// Find the single best mode
public RouteResult findOptimalMode(int source, int destination)

// Get results for ALL modes (useful for comparison)
public List<RouteResult> getAllModeResults(int source, int destination)
```

---

## Class: RouteResult

Immutable result object containing route metrics.

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `mode` | `int` | Transportation mode index |
| `totalTime` | `int` | edgeCount × timePerEdge[mode] |
| `totalCost` | `int` | edgeCount × costPerEdge[mode] |
| `edgeCount` | `int` | Number of edges in shortest path |
| `reachable` | `boolean` | True if destination is reachable |

### Selection Logic

```java
// Update optimal if this mode is better:
if (totalTime < optimalTime ||
    (totalTime == optimalTime && totalCost < optimalCost)) {
    // This mode is the new optimal
}
```

**Lexicographic ordering**: Time is primary, Cost is tie-breaker.

---

## Usage Examples

### Example 1: Basic Scenario

```java
/*
 * Graph:
 *     0 --- 1
 *     |     |
 *     3 --- 2
 *
 * Walk: All roads, 10 time/edge, 0 cost/edge
 * Bike: No 0-3 shortcut, 5 time/edge, 2 cost/edge
 * Drive: No 0-3 shortcut, 2 time/edge, 10 cost/edge
 */

RoutePlanner planner = new RoutePlanner(4, 3);

// Configure modes
planner.setModeName(0, "Walk");
planner.setModeParams(0, 10, 0);

planner.setModeName(1, "Bike");
planner.setModeParams(1, 5, 2);

planner.setModeName(2, "Drive");
planner.setModeParams(2, 2, 10);

// Add roads
planner.addRoad(0, 1, Arrays.asList(0, 1, 2));  // All modes
planner.addRoad(1, 2, Arrays.asList(0, 1, 2));  // All modes
planner.addRoad(2, 3, Arrays.asList(0, 1, 2));  // All modes
planner.addRoad(0, 3, Arrays.asList(0));        // Walk-only shortcut!

// Find optimal from 0 to 3
RouteResult result = planner.findOptimalMode(0, 3);
```

**Results:**

| Mode | Path | Edges | Time | Cost |
|------|------|-------|------|------|
| Walk | 0→3 (shortcut) | 1 | 1×10 = **10** | 1×0 = 0 |
| Bike | 0→1→2→3 | 3 | 3×5 = 15 | 3×2 = 6 |
| Drive | 0→1→2→3 | 3 | 3×2 = **6** | 3×10 = 30 |

**Optimal**: Drive (time=6 is minimum)

### Example 2: Unreachable Modes

```java
/*
 * Graph:
 *     0 --- 1  ~~~  2 --- 3
 *           (bridge)
 *
 * The bridge (1-2) is pedestrian-only
 */

RoutePlanner planner = new RoutePlanner(4, 2);

planner.setModeName(0, "Walk");
planner.setModeParams(0, 10, 0);

planner.setModeName(1, "Bike");
planner.setModeParams(1, 3, 5);

planner.addRoad(0, 1, Arrays.asList(0, 1));  // Both modes
planner.addRoad(1, 2, Arrays.asList(0));     // Walk only!
planner.addRoad(2, 3, Arrays.asList(0, 1));  // Both modes

RouteResult result = planner.findOptimalMode(0, 3);
```

**Results:**

| Mode | Reachable | Edges | Time | Cost |
|------|-----------|-------|------|------|
| Walk | Yes | 3 | 30 | 0 |
| Bike | **No** | - | - | - |

**Optimal**: Walk (only reachable option)

### Example 3: Tie-Breaker (Cost)

```java
/*
 * Graph: 0 --- 1 --- 2
 *
 * ModeA: 5 time/edge, 10 cost/edge
 * ModeB: 5 time/edge, 3 cost/edge (same speed, cheaper)
 */

RoutePlanner planner = new RoutePlanner(3, 2);

planner.setModeParams(0, 5, 10);  // ModeA
planner.setModeParams(1, 5, 3);   // ModeB

planner.addRoad(0, 1, Arrays.asList(0, 1));
planner.addRoad(1, 2, Arrays.asList(0, 1));

RouteResult result = planner.findOptimalMode(0, 2);
```

**Results:**

| Mode | Edges | Time | Cost |
|------|-------|------|------|
| ModeA | 2 | 2×5 = 10 | 2×10 = 20 |
| ModeB | 2 | 2×5 = 10 | 2×3 = **6** |

**Optimal**: ModeB (same time, lower cost wins)

---

## Key Design Decisions

### 1. Separate Adjacency Lists per Mode

**Why?** Different modes may have access to different roads. A single graph with edge annotations would require filtering during BFS, which is less efficient.

```java
// Instead of: graph[node] → List<Edge{neighbor, allowedModes}>
// We use:    adjacencyList[mode][node] → List<neighbor>
```

### 2. BFS Instead of Dijkstra

**Why?** All edges within a mode have the same weight (time/cost is per-edge, not per-specific-edge). BFS finds shortest path in O(N+M), Dijkstra would be O((N+M)log N) - unnecessary overhead.

### 3. Immutable RouteResult

**Why?** Thread safety and clarity. Once created, a RouteResult cannot be modified, preventing bugs from accidental mutation.

### 4. Lexicographic Selection

**Why?** Clear, deterministic selection criteria. Time is more important than cost (primary), but cost breaks ties (secondary).

---

## Running the Code

```bash
# Compile
javac RoutePlanner.java

# Run demo
java RoutePlanner

# Run tests
java -jar junit-platform-console-standalone.jar -cp . --scan-class-path
```

## Test Coverage

The test suite (`RoutePlannerTest.java`) covers:

| Test Class | Description |
|------------|-------------|
| BasicFunctionalityTests | Core routing functionality |
| BfsCorrectnessTests | BFS shortest path verification |
| BoundaryTests | Edge cases (single node, no roads) |
| EdgeCaseTests | Invalid inputs, same source/dest |
| GetAllModeResultsTests | Multi-mode result retrieval |
| ModeNamesTests | Mode naming functionality |
| MultipleModesTests | Mode comparison scenarios |
| TieBreakerTests | Cost-based tie-breaking |

## Complexity Summary

| Metric | Value |
|--------|-------|
| Time Complexity | O(K × (N + M)) |
| Space Complexity | O(K × N × avg_degree) |
| BFS per Mode | O(N + M) |

Where K = modes, N = nodes, M = edges.
