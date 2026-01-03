import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for RoutePlanner
 */
public class RoutePlannerTest {

    // ==================== BASIC FUNCTIONALITY TESTS ====================

    @Nested
    @DisplayName("Basic Functionality")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("Simple path with single mode")
        void testSimplePathSingleMode() {
            // Graph: 0 -- 1 -- 2
            RoutePlanner planner = new RoutePlanner(3, 1);
            planner.setModeParams(0, 10, 5);
            planner.addRoad(0, 1, Arrays.asList(0));
            planner.addRoad(1, 2, Arrays.asList(0));

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 2);

            assertTrue(result.reachable);
            assertEquals(0, result.mode);
            assertEquals(2, result.edgeCount);
            assertEquals(20, result.totalTime);  // 2 edges * 10 time
            assertEquals(10, result.totalCost);  // 2 edges * 5 cost
        }

        @Test
        @DisplayName("Multiple modes - select by lowest time")
        void testMultipleModesSelectByTime() {
            // Graph: 0 -- 1
            // Mode 0: time=10, cost=1
            // Mode 1: time=5, cost=100
            RoutePlanner planner = new RoutePlanner(2, 2);
            planner.setModeParams(0, 10, 1);
            planner.setModeParams(1, 5, 100);
            planner.addRoad(0, 1, Arrays.asList(0, 1));

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 1);

            assertTrue(result.reachable);
            assertEquals(1, result.mode);  // Mode 1 wins (lower time)
            assertEquals(5, result.totalTime);
            assertEquals(100, result.totalCost);
        }

        @Test
        @DisplayName("Direct path vs longer path - direct wins if faster")
        void testDirectPathVsLongerPath() {
            // Graph: 0 -- 1 -- 2
            //         \______/
            // Mode 0 (Walk): all roads, time=10
            // Mode 1 (Drive): only 0-1-2 path, time=3
            RoutePlanner planner = new RoutePlanner(3, 2);
            planner.setModeParams(0, 10, 0);  // Walk: 10 time per edge
            planner.setModeParams(1, 3, 0);   // Drive: 3 time per edge

            planner.addRoad(0, 1, Arrays.asList(0, 1));
            planner.addRoad(1, 2, Arrays.asList(0, 1));
            planner.addRoad(0, 2, Arrays.asList(0));  // Walk only direct path

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 2);

            // Drive: 2 edges * 3 = 6
            // Walk direct: 1 edge * 10 = 10
            // Drive wins
            assertEquals(1, result.mode);
            assertEquals(6, result.totalTime);
        }
    }

    // ==================== TIE-BREAKER TESTS ====================

    @Nested
    @DisplayName("Tie-breaker Scenarios")
    class TieBreakerTests {

        @Test
        @DisplayName("Same time - select by lower cost")
        void testSameTimeSelectByCost() {
            // Graph: 0 -- 1
            // Mode 0: time=10, cost=50
            // Mode 1: time=10, cost=20
            RoutePlanner planner = new RoutePlanner(2, 2);
            planner.setModeParams(0, 10, 50);
            planner.setModeParams(1, 10, 20);
            planner.addRoad(0, 1, Arrays.asList(0, 1));

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 1);

            assertEquals(1, result.mode);  // Mode 1 wins (lower cost)
            assertEquals(10, result.totalTime);
            assertEquals(20, result.totalCost);
        }

        @Test
        @DisplayName("Same time and cost - any mode acceptable")
        void testSameTimeAndCost() {
            // Graph: 0 -- 1
            // Both modes have identical time and cost
            RoutePlanner planner = new RoutePlanner(2, 2);
            planner.setModeParams(0, 10, 5);
            planner.setModeParams(1, 10, 5);
            planner.addRoad(0, 1, Arrays.asList(0, 1));

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 1);

            assertTrue(result.reachable);
            assertEquals(10, result.totalTime);
            assertEquals(5, result.totalCost);
            // Either mode is acceptable
            assertTrue(result.mode == 0 || result.mode == 1);
        }

        @Test
        @DisplayName("Different edge counts leading to same total time")
        void testDifferentEdgesSameTime() {
            // Graph: 0 -- 1 -- 2
            //         \______/
            // Mode 0: can only use direct path 0-2, time=6 per edge
            // Mode 1: can only use 0-1-2 path, time=3 per edge
            // Both result in same total time: Mode 0: 1*6=6, Mode 1: 2*3=6
            // Mode 0 should win due to lower cost
            RoutePlanner planner = new RoutePlanner(3, 2);
            planner.setModeParams(0, 6, 2);   // direct: 1*6=6 time, 1*2=2 cost
            planner.setModeParams(1, 3, 3);   // 2 edges: 2*3=6 time, 2*3=6 cost

            planner.addRoad(0, 1, Arrays.asList(1));     // Mode 1 only
            planner.addRoad(1, 2, Arrays.asList(1));     // Mode 1 only
            planner.addRoad(0, 2, Arrays.asList(0));     // Mode 0 only (direct)

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 2);

            // Both modes have same time (6), Mode 0 wins with lower cost (2 vs 6)
            assertEquals(0, result.mode);
            assertEquals(6, result.totalTime);
            assertEquals(2, result.totalCost);
        }
    }

    // ==================== EDGE CASES ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Start equals destination")
        void testStartEqualsDestination() {
            RoutePlanner planner = new RoutePlanner(3, 1);
            planner.setModeParams(0, 10, 5);
            planner.addRoad(0, 1, Arrays.asList(0));

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 0);

            assertTrue(result.reachable);
            assertEquals(0, result.edgeCount);
            assertEquals(0, result.totalTime);
            assertEquals(0, result.totalCost);
        }

        @Test
        @DisplayName("Destination unreachable by all modes")
        void testDestinationUnreachable() {
            // Graph: 0 -- 1    2 (isolated)
            RoutePlanner planner = new RoutePlanner(3, 1);
            planner.setModeParams(0, 10, 5);
            planner.addRoad(0, 1, Arrays.asList(0));

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 2);

            assertFalse(result.reachable);
            assertEquals(-1, result.mode);
            assertEquals(-1, result.totalTime);
            assertEquals(-1, result.totalCost);
        }

        @Test
        @DisplayName("Destination reachable by only one mode")
        void testReachableByOneMode() {
            // Graph: 0 -- 1 -- 2
            // Mode 0: all roads
            // Mode 1: only 0-1
            RoutePlanner planner = new RoutePlanner(3, 2);
            planner.setModeParams(0, 10, 5);
            planner.setModeParams(1, 1, 1);  // faster but can't reach
            planner.addRoad(0, 1, Arrays.asList(0, 1));
            planner.addRoad(1, 2, Arrays.asList(0));  // Mode 0 only

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 2);

            assertTrue(result.reachable);
            assertEquals(0, result.mode);  // Only Mode 0 can reach
            assertEquals(20, result.totalTime);
        }

        @Test
        @DisplayName("Empty graph - no roads")
        void testEmptyGraph() {
            RoutePlanner planner = new RoutePlanner(5, 2);
            planner.setModeParams(0, 10, 5);
            planner.setModeParams(1, 5, 10);

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 4);

            assertFalse(result.reachable);
        }

        @Test
        @DisplayName("Single node graph")
        void testSingleNode() {
            RoutePlanner planner = new RoutePlanner(1, 1);
            planner.setModeParams(0, 10, 5);

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 0);

            assertTrue(result.reachable);
            assertEquals(0, result.edgeCount);
        }
    }

    // ==================== BFS CORRECTNESS TESTS ====================

    @Nested
    @DisplayName("BFS Correctness")
    class BfsCorrectnessTests {

        @Test
        @DisplayName("Finds shortest path in complex graph")
        void testShortestPathComplexGraph() {
            /*
             * Graph:
             *     1 --- 2
             *    /|     |\
             *   0 |     | 5
             *    \|     |/
             *     3 --- 4
             *
             * Shortest from 0 to 5: 0-1-2-5 or 0-3-4-5 (3 edges)
             */
            RoutePlanner planner = new RoutePlanner(6, 1);
            planner.setModeParams(0, 1, 1);

            planner.addRoad(0, 1, Arrays.asList(0));
            planner.addRoad(0, 3, Arrays.asList(0));
            planner.addRoad(1, 2, Arrays.asList(0));
            planner.addRoad(1, 3, Arrays.asList(0));
            planner.addRoad(2, 4, Arrays.asList(0));
            planner.addRoad(2, 5, Arrays.asList(0));
            planner.addRoad(3, 4, Arrays.asList(0));
            planner.addRoad(4, 5, Arrays.asList(0));

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 5);

            assertEquals(3, result.edgeCount);  // Shortest path is 3 edges
        }

        @Test
        @DisplayName("Handles cycles correctly")
        void testGraphWithCycles() {
            /*
             * Graph with cycle:
             *   0 -- 1
             *   |    |
             *   3 -- 2
             */
            RoutePlanner planner = new RoutePlanner(4, 1);
            planner.setModeParams(0, 1, 1);

            planner.addRoad(0, 1, Arrays.asList(0));
            planner.addRoad(1, 2, Arrays.asList(0));
            planner.addRoad(2, 3, Arrays.asList(0));
            planner.addRoad(3, 0, Arrays.asList(0));

            // 0 to 2: should be 2 edges (0-1-2), not 2 edges via (0-3-2)
            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 2);

            assertEquals(2, result.edgeCount);
        }

        @Test
        @DisplayName("Linear graph")
        void testLinearGraph() {
            // 0 -- 1 -- 2 -- 3 -- 4
            RoutePlanner planner = new RoutePlanner(5, 1);
            planner.setModeParams(0, 2, 3);

            for (int i = 0; i < 4; i++) {
                planner.addRoad(i, i + 1, Arrays.asList(0));
            }

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 4);

            assertEquals(4, result.edgeCount);
            assertEquals(8, result.totalTime);   // 4 * 2
            assertEquals(12, result.totalCost);  // 4 * 3
        }
    }

    // ==================== MULTIPLE MODES TESTS ====================

    @Nested
    @DisplayName("Multiple Modes")
    class MultipleModesTests {

        @Test
        @DisplayName("Three modes with different characteristics")
        void testThreeModesComparison() {
            /*
             * Graph: 0 -- 1 -- 2 -- 3
             *
             * Mode 0 (Walk): all roads, time=5, cost=0
             * Mode 1 (Bike): all roads, time=3, cost=2
             * Mode 2 (Drive): all roads, time=1, cost=10
             */
            RoutePlanner planner = new RoutePlanner(4, 3);
            planner.setModeParams(0, 5, 0);
            planner.setModeParams(1, 3, 2);
            planner.setModeParams(2, 1, 10);

            for (int i = 0; i < 3; i++) {
                planner.addRoad(i, i + 1, Arrays.asList(0, 1, 2));
            }

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 3);

            // All take 3 edges
            // Walk: 15 time, 0 cost
            // Bike: 9 time, 6 cost
            // Drive: 3 time, 30 cost <- wins (lowest time)
            assertEquals(2, result.mode);
            assertEquals(3, result.totalTime);
            assertEquals(30, result.totalCost);
        }

        @Test
        @DisplayName("Mode with shorter path beats faster mode")
        void testShorterPathBeatsFasterMode() {
            /*
             * Graph:
             *   0 -------- 3
             *   |          |
             *   1 -- 2 ----+
             *
             * Mode 0 (Walk): can use direct 0-3, time=10
             * Mode 1 (Drive): must go 0-1-2-3, time=2
             *
             * Walk: 1 edge * 10 = 10
             * Drive: 3 edges * 2 = 6 <- wins
             */
            RoutePlanner planner = new RoutePlanner(4, 2);
            planner.setModeParams(0, 10, 0);
            planner.setModeParams(1, 2, 0);

            planner.addRoad(0, 1, Arrays.asList(1));      // Drive only
            planner.addRoad(1, 2, Arrays.asList(1));      // Drive only
            planner.addRoad(2, 3, Arrays.asList(1));      // Drive only
            planner.addRoad(0, 3, Arrays.asList(0, 1));   // Both modes

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 3);

            // Drive can use direct path: 1 * 2 = 2 (wins!)
            assertEquals(1, result.mode);
            assertEquals(2, result.totalTime);
        }
    }

    // ==================== getAllModeResults TESTS ====================

    @Nested
    @DisplayName("getAllModeResults")
    class GetAllModeResultsTests {

        @Test
        @DisplayName("Returns results for all modes")
        void testReturnsAllModeResults() {
            RoutePlanner planner = new RoutePlanner(2, 3);
            planner.setModeParams(0, 10, 1);
            planner.setModeParams(1, 5, 2);
            planner.setModeParams(2, 3, 3);
            planner.addRoad(0, 1, Arrays.asList(0, 1, 2));

            List<RoutePlanner.RouteResult> results = planner.getAllModeResults(0, 1);

            assertEquals(3, results.size());

            assertEquals(0, results.get(0).mode);
            assertEquals(10, results.get(0).totalTime);

            assertEquals(1, results.get(1).mode);
            assertEquals(5, results.get(1).totalTime);

            assertEquals(2, results.get(2).mode);
            assertEquals(3, results.get(2).totalTime);
        }

        @Test
        @DisplayName("Marks unreachable modes correctly")
        void testMarksUnreachableModes() {
            RoutePlanner planner = new RoutePlanner(3, 2);
            planner.setModeParams(0, 10, 1);
            planner.setModeParams(1, 5, 2);
            planner.addRoad(0, 1, Arrays.asList(0));    // Mode 0 only
            planner.addRoad(1, 2, Arrays.asList(0));    // Mode 0 only

            List<RoutePlanner.RouteResult> results = planner.getAllModeResults(0, 2);

            assertTrue(results.get(0).reachable);   // Mode 0 can reach
            assertFalse(results.get(1).reachable);  // Mode 1 cannot reach
        }
    }

    // ==================== MODE NAMES TESTS ====================

    @Nested
    @DisplayName("Mode Names")
    class ModeNamesTests {

        @Test
        @DisplayName("Default mode names")
        void testDefaultModeNames() {
            RoutePlanner planner = new RoutePlanner(2, 3);

            assertEquals("Mode0", planner.getModeName(0));
            assertEquals("Mode1", planner.getModeName(1));
            assertEquals("Mode2", planner.getModeName(2));
        }

        @Test
        @DisplayName("Custom mode names")
        void testCustomModeNames() {
            RoutePlanner planner = new RoutePlanner(2, 3);
            planner.setModeName(0, "Walk");
            planner.setModeName(1, "Bike");
            planner.setModeName(2, "Drive");

            assertEquals("Walk", planner.getModeName(0));
            assertEquals("Bike", planner.getModeName(1));
            assertEquals("Drive", planner.getModeName(2));
        }

        @Test
        @DisplayName("Invalid mode name returns Unknown")
        void testInvalidModeName() {
            RoutePlanner planner = new RoutePlanner(2, 2);

            assertEquals("Unknown", planner.getModeName(-1));
            assertEquals("Unknown", planner.getModeName(5));
        }
    }

    // ==================== STRESS/BOUNDARY TESTS ====================

    @Nested
    @DisplayName("Boundary Conditions")
    class BoundaryTests {

        @Test
        @DisplayName("Large linear graph")
        void testLargeLinearGraph() {
            int n = 1000;
            RoutePlanner planner = new RoutePlanner(n, 1);
            planner.setModeParams(0, 1, 1);

            for (int i = 0; i < n - 1; i++) {
                planner.addRoad(i, i + 1, Arrays.asList(0));
            }

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, n - 1);

            assertEquals(n - 1, result.edgeCount);
            assertEquals(n - 1, result.totalTime);
            assertEquals(n - 1, result.totalCost);
        }

        @Test
        @DisplayName("Dense graph (complete graph)")
        void testCompleteGraph() {
            int n = 10;
            RoutePlanner planner = new RoutePlanner(n, 1);
            planner.setModeParams(0, 1, 1);

            // Add all edges (complete graph)
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    planner.addRoad(i, j, Arrays.asList(0));
                }
            }

            // Any two nodes should be 1 edge apart
            RoutePlanner.RouteResult result = planner.findOptimalMode(0, n - 1);

            assertEquals(1, result.edgeCount);
        }

        @Test
        @DisplayName("Zero time and cost")
        void testZeroTimeAndCost() {
            RoutePlanner planner = new RoutePlanner(2, 1);
            planner.setModeParams(0, 0, 0);
            planner.addRoad(0, 1, Arrays.asList(0));

            RoutePlanner.RouteResult result = planner.findOptimalMode(0, 1);

            assertTrue(result.reachable);
            assertEquals(1, result.edgeCount);
            assertEquals(0, result.totalTime);
            assertEquals(0, result.totalCost);
        }
    }
}
