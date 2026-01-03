import java.util.*;

/**
 * Route Planner for finding optimal transportation mode in a city graph.
 *
 * Problem: Given a city with N intersections, M roads, and K transportation modes,
 * find the optimal mode to travel from S to D based on:
 * - Primary: Minimum total travel time
 * - Secondary: Minimum total cost (tie-breaker)
 *
 * Complexity: O(K × (N + M))
 * - K iterations (one per transportation mode)
 * - Each BFS traversal takes O(N + M) where N = nodes, M = edges
 *
 * Design Pattern: Uses separate adjacency lists per mode for efficient graph traversal
 */
public class RoutePlanner {

    // ==================== INSTANCE VARIABLES ====================

    private int n;  // Number of intersections (nodes in the graph, 0-indexed)
    private int k;  // Number of transportation modes available

    // Adjacency list structure: adjacencyList[mode][node] -> list of neighboring nodes
    // Each mode has its own graph representation since different modes may have
    // access to different roads
    private List<List<Integer>>[] adjacencyList;

    // Per-mode travel parameters
    private int[] timePerEdge;   // Time units to traverse one edge using each mode
    private int[] costPerEdge;   // Cost units to traverse one edge using each mode
    private String[] modeNames;  // Human-readable names for each mode (e.g., "Walk", "Bike")

    // ==================== INNER CLASS: RouteResult ====================

    /**
     * Result class encapsulating the outcome of a route search.
     * Immutable data object (all fields are final) for thread safety and clarity.
     */
    public static class RouteResult {
        public final int mode;        // Index of the transportation mode used
        public final int totalTime;   // Total time = edgeCount × timePerEdge[mode]
        public final int totalCost;   // Total cost = edgeCount × costPerEdge[mode]
        public final int edgeCount;   // Number of edges (roads) traversed in shortest path
        public final boolean reachable;  // True if destination is reachable from source

        /**
         * Constructs a RouteResult with the given metrics.
         *
         * @param mode Transportation mode index
         * @param totalTime Total travel time
         * @param totalCost Total travel cost
         * @param edgeCount Number of edges in path (-1 if unreachable)
         */
        public RouteResult(int mode, int totalTime, int totalCost, int edgeCount) {
            this.mode = mode;
            this.totalTime = totalTime;
            this.totalCost = totalCost;
            this.edgeCount = edgeCount;
            // Destination is reachable if we found a valid path (edgeCount >= 0)
            this.reachable = (edgeCount != -1);
        }

        /**
         * Returns a human-readable string representation of this route result.
         * Useful for debugging and displaying results to users.
         */
        @Override
        public String toString() {
            if (!reachable) return "Unreachable by any mode";
            return String.format("Mode=%d, Edges=%d, Time=%d, Cost=%d",
                                 mode, edgeCount, totalTime, totalCost);
        }
    }

    // ==================== CONSTRUCTOR ====================

    /**
     * Initialize RoutePlanner with N intersections and K transportation modes.
     *
     * Creates an empty graph structure ready to have roads added.
     * Each mode gets its own adjacency list since different modes may
     * have access to different subsets of roads.
     *
     * @param n Number of intersections (nodes 0 to n-1)
     * @param k Number of transportation modes (modes 0 to k-1)
     */
    @SuppressWarnings("unchecked")  // Suppress warning for generic array creation
    public RoutePlanner(int n, int k) {
        this.n = n;
        this.k = k;

        // Initialize arrays to store per-mode parameters
        this.timePerEdge = new int[k];  // Default: 0 time per edge
        this.costPerEdge = new int[k];  // Default: 0 cost per edge
        this.modeNames = new String[k];

        // Initialize adjacency list for each mode
        // Structure: adjacencyList[modeIndex] -> List of (n) lists, one per node
        this.adjacencyList = new ArrayList[k];
        for (int m = 0; m < k; m++) {
            // Create a list of n empty lists for this mode's graph
            adjacencyList[m] = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                adjacencyList[m].add(new ArrayList<>());  // Empty neighbor list for node i
            }
            modeNames[m] = "Mode" + m;  // Assign default name (can be overridden)
        }
    }

    // ==================== GRAPH BUILDING METHODS ====================

    /**
     * Add a road (edge) between intersections u and v for specified transportation modes.
     *
     * This creates an undirected edge, meaning travel is possible in both directions.
     * The road is only added to the adjacency lists of the specified modes.
     *
     * @param u First intersection (0-indexed)
     * @param v Second intersection (0-indexed)
     * @param allowedModes List of mode indices that can use this road
     */
    public void addRoad(int u, int v, List<Integer> allowedModes) {
        // Add edge to each allowed mode's graph
        for (int mode : allowedModes) {
            // Validate mode index to prevent array out of bounds
            if (mode >= 0 && mode < k) {
                // Add bidirectional edge (undirected graph)
                adjacencyList[mode].get(u).add(v);  // u -> v
                adjacencyList[mode].get(v).add(u);  // v -> u
            }
        }
    }

    // ==================== MODE CONFIGURATION METHODS ====================

    /**
     * Set time and cost per edge for a transportation mode.
     *
     * These values are used to calculate total travel time and cost:
     * - totalTime = numberOfEdges × timePerEdge
     * - totalCost = numberOfEdges × costPerEdge
     *
     * @param mode Mode index (0 to k-1)
     * @param time Time units to traverse one edge using this mode
     * @param cost Cost units to traverse one edge using this mode
     */
    public void setModeParams(int mode, int time, int cost) {
        // Validate mode index before setting parameters
        if (mode >= 0 && mode < k) {
            timePerEdge[mode] = time;
            costPerEdge[mode] = cost;
        }
    }

    /**
     * Set a human-readable name for a mode (optional)
     *
     * @param mode Mode index
     * @param name Human-readable name
     */
    public void setModeName(int mode, String name) {
        if (mode >= 0 && mode < k) {
            modeNames[mode] = name;
        }
    }

    /**
     * Get mode name
     *
     * @param mode Mode index
     * @return Mode name
     */
    public String getModeName(int mode) {
        if (mode >= 0 && mode < k) {
            return modeNames[mode];
        }
        return "Unknown";
    }

    // ==================== CORE ALGORITHM: BFS ====================

    /**
     * Breadth-First Search to find shortest path (minimum edge count) for a specific mode.
     *
     * BFS guarantees the shortest path in an unweighted graph because it explores
     * nodes level by level (all nodes at distance d before any node at distance d+1).
     *
     * Time Complexity: O(N + M) where N = nodes, M = edges for this mode
     * Space Complexity: O(N) for visited array and queue
     *
     * @param start Starting intersection (source node)
     * @param end Destination intersection (target node)
     * @param mode Transportation mode to use (determines which edges are available)
     * @return Number of edges in shortest path, or -1 if unreachable
     */
    private int bfs(int start, int end, int mode) {
        // Edge case: source equals destination, no travel needed
        if (start == end) return 0;

        // Validate node indices are within valid range
        if (start < 0 || start >= n || end < 0 || end >= n) {
            return -1;  // Invalid nodes
        }

        // Track visited nodes to avoid cycles and redundant exploration
        boolean[] visited = new boolean[n];

        // Queue stores pairs: {nodeIndex, distanceFromStart}
        // Using LinkedList as it implements Queue interface efficiently
        Queue<int[]> queue = new LinkedList<>();

        // Initialize BFS from the starting node
        visited[start] = true;
        queue.offer(new int[]{start, 0});  // Start at distance 0

        // BFS main loop: process nodes level by level
        while (!queue.isEmpty()) {
            int[] current = queue.poll();  // Dequeue front element
            int node = current[0];         // Current node index
            int dist = current[1];         // Distance (edges) from start

            // Explore all neighbors of current node in this mode's graph
            for (int neighbor : adjacencyList[mode].get(node)) {
                // Early termination: found destination
                if (neighbor == end) {
                    return dist + 1;  // Path length = current distance + 1 edge
                }

                // Add unvisited neighbors to queue for later exploration
                if (!visited[neighbor]) {
                    visited[neighbor] = true;  // Mark visited before enqueuing
                    queue.offer(new int[]{neighbor, dist + 1});
                }
            }
        }

        // Queue empty and destination not found: no path exists
        return -1;
    }

    // ==================== ROUTE FINDING METHODS ====================

    /**
     * Find the optimal transportation mode from source S to destination D.
     *
     * Algorithm:
     * 1. For each transportation mode, find the shortest path using BFS
     * 2. Calculate total time and cost for each reachable mode
     * 3. Select the mode with minimum time; use cost as tie-breaker
     *
     * Selection criteria (lexicographic ordering):
     * - Primary: Minimum total travel time
     * - Secondary: Minimum total cost (when times are equal)
     *
     * Time Complexity: O(K × (N + M)) - K BFS traversals
     *
     * @param s Starting intersection (source)
     * @param d Destination intersection (target)
     * @return RouteResult containing optimal mode and metrics, or unreachable result
     */
    public RouteResult findOptimalMode(int s, int d) {
        // Track the best solution found so far
        int optimalMode = -1;                    // -1 indicates no valid mode found yet
        int optimalTime = Integer.MAX_VALUE;     // Initialize to max for comparison
        int optimalCost = Integer.MAX_VALUE;     // Initialize to max for comparison
        int optimalEdges = -1;                   // Number of edges in optimal path

        // Iterate through all transportation modes to find the best one
        for (int m = 0; m < k; m++) {
            // Find shortest path (by edge count) for this mode
            int edges = bfs(s, d, m);

            // Skip this mode if destination is unreachable
            if (edges == -1) continue;

            // Calculate total metrics based on edge count and per-edge costs
            int totalTime = edges * timePerEdge[m];
            int totalCost = edges * costPerEdge[m];

            // Update optimal if this mode is better:
            // - Strictly less time, OR
            // - Same time but less cost (tie-breaker)
            if (totalTime < optimalTime ||
                (totalTime == optimalTime && totalCost < optimalCost)) {
                optimalMode = m;
                optimalTime = totalTime;
                optimalCost = totalCost;
                optimalEdges = edges;
            }
        }

        // Handle case where no mode can reach the destination
        if (optimalMode == -1) {
            // Return a result indicating unreachability
            return new RouteResult(-1, -1, -1, -1);
        }

        // Return the optimal solution
        return new RouteResult(optimalMode, optimalTime, optimalCost, optimalEdges);
    }

    /**
     * Get detailed results for ALL modes (useful for debugging/comparison).
     *
     * Unlike findOptimalMode which returns only the best mode, this method
     * returns results for every mode, including unreachable ones.
     *
     * @param s Starting intersection (source)
     * @param d Destination intersection (target)
     * @return List of RouteResult, one for each mode (index matches mode index)
     */
    public List<RouteResult> getAllModeResults(int s, int d) {
        List<RouteResult> results = new ArrayList<>();

        // Evaluate each mode independently
        for (int m = 0; m < k; m++) {
            int edges = bfs(s, d, m);

            if (edges == -1) {
                // Mode cannot reach destination - add unreachable result
                results.add(new RouteResult(m, -1, -1, -1));
            } else {
                // Mode can reach destination - calculate metrics
                int totalTime = edges * timePerEdge[m];
                int totalCost = edges * costPerEdge[m];
                results.add(new RouteResult(m, totalTime, totalCost, edges));
            }
        }

        return results;
    }

    // ==================== MAIN METHOD WITH EXAMPLES ====================

    /**
     * Main method demonstrating various use cases of the RoutePlanner.
     *
     * Runs three examples:
     * 1. Basic scenario with multiple modes and different optimal choices
     * 2. Scenario where some modes cannot reach the destination
     * 3. Tie-breaker scenario where cost decides between equal-time modes
     *
     * @param args Command line arguments (not used)
     */
    public static void main(String[] args) {
        System.out.println("=== Route Planner Demo ===\n");

        // Example 1: Basic scenario - demonstrates mode selection based on time
        runExample1();

        System.out.println("\n" + "=".repeat(50) + "\n");

        // Example 2: Some modes unreachable - shows handling of disconnected graphs
        runExample2();

        System.out.println("\n" + "=".repeat(50) + "\n");

        // Example 3: Tie-breaker scenario - demonstrates cost-based tie-breaking
        runExample3();
    }

    // ==================== EXAMPLE METHODS ====================

    /**
     * Example 1: Basic Scenario
     *
     * Demonstrates a simple 4-node graph where:
     * - Walking has a direct path (1 edge) but is slow
     * - Biking/Driving must go around (3 edges) but are faster
     * - The optimal mode depends on time × edges calculation
     */
    private static void runExample1() {
        System.out.println("Example 1: Basic Scenario");
        System.out.println("-".repeat(30));

        /*
         * Graph Structure (4 nodes in a square):
         *     0 --- 1
         *     |     |
         *     3 --- 2
         *
         * Transportation Modes:
         * - Walk (mode 0): allowed on all roads, time=10/edge, cost=0/edge
         * - Bike (mode 1): allowed on 0-1, 1-2, 2-3 only, time=5/edge, cost=2/edge
         * - Drive (mode 2): allowed on 0-1, 1-2, 2-3 only, time=2/edge, cost=10/edge
         *
         * Key insight: Walk has a shortcut (0-3 direct), others must go 0→1→2→3
         */

        // Create planner with 4 intersections and 3 transportation modes
        RoutePlanner planner = new RoutePlanner(4, 3);

        // Configure mode 0: Walking - slow but free
        planner.setModeName(0, "Walk");
        planner.setModeParams(0, 10, 0);  // 10 time units, 0 cost per edge

        // Configure mode 1: Biking - medium speed, low cost
        planner.setModeName(1, "Bike");
        planner.setModeParams(1, 5, 2);   // 5 time units, 2 cost per edge

        // Configure mode 2: Driving - fast but expensive
        planner.setModeName(2, "Drive");
        planner.setModeParams(2, 2, 10);  // 2 time units, 10 cost per edge

        // Add roads with their allowed transportation modes
        planner.addRoad(0, 1, Arrays.asList(0, 1, 2));  // All modes can use this road
        planner.addRoad(1, 2, Arrays.asList(0, 1, 2));  // All modes can use this road
        planner.addRoad(2, 3, Arrays.asList(0, 1, 2));  // All modes can use this road
        planner.addRoad(0, 3, Arrays.asList(0));        // Walk-only shortcut!

        // Find optimal route from intersection 0 to intersection 3
        System.out.println("Finding route from 0 to 3...\n");

        // Display results for all modes to compare
        // Expected results:
        // - Walk: 1 edge × 10 = 10 time, 1 × 0 = 0 cost (uses shortcut)
        // - Bike: 3 edges × 5 = 15 time, 3 × 2 = 6 cost (goes around)
        // - Drive: 3 edges × 2 = 6 time, 3 × 10 = 30 cost (goes around)
        List<RouteResult> allResults = planner.getAllModeResults(0, 3);
        for (int i = 0; i < allResults.size(); i++) {
            RouteResult r = allResults.get(i);
            if (r.reachable) {
                System.out.printf("%s: %d edges, Time=%d, Cost=%d%n",
                    planner.getModeName(i), r.edgeCount, r.totalTime, r.totalCost);
            } else {
                System.out.printf("%s: Unreachable%n", planner.getModeName(i));
            }
        }

        // Find and display the optimal mode
        // Expected: Drive wins with time=6 (lowest among all modes)
        RouteResult optimal = planner.findOptimalMode(0, 3);
        System.out.println("\nOptimal: " + planner.getModeName(optimal.mode) +
                          " (Time=" + optimal.totalTime + ", Cost=" + optimal.totalCost + ")");
    }

    /**
     * Example 2: Some Modes Unreachable
     *
     * Demonstrates handling of disconnected graph components:
     * - A "bridge" road (1-2) is only accessible by walking
     * - Biking cannot cross the bridge, making destination unreachable
     */
    private static void runExample2() {
        System.out.println("Example 2: Some Modes Unreachable");
        System.out.println("-".repeat(30));

        /*
         * Graph Structure (linear with a pedestrian bridge):
         *     0 --- 1  ~~~  2 --- 3
         *           (bridge)
         *
         * The bridge (1-2) is pedestrian-only:
         * - Walk can traverse: 0 → 1 → 2 → 3
         * - Bike is blocked at node 1, cannot reach nodes 2 or 3
         */

        // Create planner with 4 intersections and 2 transportation modes
        RoutePlanner planner = new RoutePlanner(4, 2);

        // Configure walking - can use the bridge
        planner.setModeName(0, "Walk");
        planner.setModeParams(0, 10, 0);  // 10 time, 0 cost per edge

        // Configure biking - faster but blocked by bridge
        planner.setModeName(1, "Bike");
        planner.setModeParams(1, 3, 5);   // 3 time, 5 cost per edge

        // Add roads with restrictions
        planner.addRoad(0, 1, Arrays.asList(0, 1));  // Both modes allowed
        planner.addRoad(1, 2, Arrays.asList(0));     // PEDESTRIAN BRIDGE - walk only!
        planner.addRoad(2, 3, Arrays.asList(0, 1));  // Both modes allowed

        System.out.println("Finding route from 0 to 3...\n");

        // Display results - Bike should show as unreachable
        List<RouteResult> allResults = planner.getAllModeResults(0, 3);
        for (int i = 0; i < allResults.size(); i++) {
            RouteResult r = allResults.get(i);
            if (r.reachable) {
                System.out.printf("%s: %d edges, Time=%d, Cost=%d%n",
                    planner.getModeName(i), r.edgeCount, r.totalTime, r.totalCost);
            } else {
                System.out.printf("%s: Unreachable%n", planner.getModeName(i));
            }
        }

        // Walk is the only option, so it wins by default
        RouteResult optimal = planner.findOptimalMode(0, 3);
        System.out.println("\nOptimal: " + planner.getModeName(optimal.mode) +
                          " (Time=" + optimal.totalTime + ", Cost=" + optimal.totalCost + ")");
    }

    /**
     * Example 3: Tie-breaker Scenario
     *
     * Demonstrates the secondary selection criterion (cost) when times are equal:
     * - Two modes with identical time per edge
     * - Same shortest path length
     * - Mode with lower cost should win
     */
    private static void runExample3() {
        System.out.println("Example 3: Tie-breaker Scenario");
        System.out.println("-".repeat(30));

        /*
         * Graph Structure (simple linear path):
         *     0 --- 1 --- 2
         *
         * Both modes have the same time per edge (5 units) but different costs.
         * This tests the tie-breaking logic:
         * - ModeA: 2 edges × 5 = 10 time, 2 × 10 = 20 cost
         * - ModeB: 2 edges × 5 = 10 time, 2 × 3 = 6 cost
         * Since times are equal, ModeB wins due to lower cost.
         */

        // Create planner with 3 intersections and 2 transportation modes
        RoutePlanner planner = new RoutePlanner(3, 2);

        // Configure ModeA: time=5, cost=10 per edge (expensive)
        planner.setModeName(0, "ModeA");
        planner.setModeParams(0, 5, 10);

        // Configure ModeB: time=5, cost=3 per edge (same speed, cheaper)
        planner.setModeName(1, "ModeB");
        planner.setModeParams(1, 5, 3);

        // Both modes can use all roads
        planner.addRoad(0, 1, Arrays.asList(0, 1));
        planner.addRoad(1, 2, Arrays.asList(0, 1));

        System.out.println("Finding route from 0 to 2...\n");

        // Display results for comparison
        List<RouteResult> allResults = planner.getAllModeResults(0, 2);
        for (int i = 0; i < allResults.size(); i++) {
            RouteResult r = allResults.get(i);
            System.out.printf("%s: %d edges, Time=%d, Cost=%d%n",
                planner.getModeName(i), r.edgeCount, r.totalTime, r.totalCost);
        }

        // ModeB should win: same time as ModeA, but lower cost
        RouteResult optimal = planner.findOptimalMode(0, 2);
        System.out.println("\nOptimal: " + planner.getModeName(optimal.mode) +
                          " (Time=" + optimal.totalTime + ", Cost=" + optimal.totalCost + ")");
        System.out.println("(ModeB wins due to lower cost when times are equal)");
    }
}
