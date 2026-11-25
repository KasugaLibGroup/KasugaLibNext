package lib.kasuga.content.graph;

import lombok.Getter;

import java.util.*;

/**
 * Utility for detecting cycles in directed graphs and performing topological sorting.
 *
 * <p>Uses DFS three-color marking for cycle detection (white/gray/black)
 * and Kahn's algorithm (BFS) for topological sorting.
 *
 * @param <T> the graph node type
 */
public class GraphCycleDetector<T> {

    /**
     * Provides neighbor nodes reachable via directed edges from a given node.
     * Used by {@link #dfs(Set, GraphAdapter)}.
     */
    @FunctionalInterface
    public interface GraphAdapter<T> {
        /**
         * Returns the nodes directly reachable from {@code node} via outgoing edges.
         */
        List<T> getNeighbors(T node);
    }

    /**
     * Result of a DFS cycle detection scan.
     */
    @Getter
    public static class CycleResult<T> {
        /** All detected cycles. Each cycle is a list of nodes forming the cycle path. */
        private final List<List<T>> cycles;
        /** All nodes that participate in at least one cycle. */
        private final Set<T> nodesInCycles;

        public CycleResult(List<List<T>> cycles) {
            this.cycles = new ArrayList<>(cycles);
            this.nodesInCycles = new HashSet<>();
            for (List<T> cycle : cycles) {
                nodesInCycles.addAll(cycle);
            }
        }

        /** Whether any cycle was detected. */
        public boolean hasCycle() {
            return !cycles.isEmpty();
        }
    }

    /**
     * Result of a topological sort operation.
     */
    public static class TopoResult<T> {
        /** Nodes in topological order (parents before children). */
        @Getter
        private final List<T> sorted;
        private final boolean hasCycle;
        /** Nodes that could not be sorted because they participate in cycles. */
        @Getter
        private final Set<T> nodesNotSorted;

        public TopoResult(List<T> sorted, Set<T> allNodes) {
            this.sorted = Collections.unmodifiableList(sorted);
            this.nodesNotSorted = new HashSet<>(allNodes);
            for (T node : sorted) {
                this.nodesNotSorted.remove(node);
            }
            this.hasCycle = !nodesNotSorted.isEmpty();
        }

        /** Whether the sort was incomplete due to cycles. */
        public boolean hasCycle() {
            return hasCycle;
        }
    }

    /**
     * Detects all cycles in a directed graph using DFS three-color marking.
     *
     * <p>Nodes in the "visiting" state (gray, on the current DFS path) that are
     * reached again form a cycle. The method records every such cycle and
     * continues to find all cycles in the graph.
     *
     * @param nodes   all nodes in the graph
     * @param adapter provides outgoing edges from each node
     * @return detection result containing all found cycles
     */
    public static <T> CycleResult<T> dfs(Set<T> nodes, GraphAdapter<T> adapter) {
        List<List<T>> allCycles = new ArrayList<>();
        Set<T> visited = new HashSet<>();
        Set<T> visiting = new HashSet<>();

        for (T node : nodes) {
            if (!visited.contains(node)) {
                dfsVisit(node, adapter, visited, visiting, new ArrayList<>(), allCycles);
            }
        }

        return new CycleResult<>(allCycles);
    }

    private static <T> void dfsVisit(
            T current,
            GraphAdapter<T> adapter,
            Set<T> visited,
            Set<T> visiting,
            List<T> path,
            List<List<T>> allCycles) {
        visiting.add(current);
        path.add(current);

        for (T neighbor : adapter.getNeighbors(current)) {
            if (visiting.contains(neighbor)) {
                // Found a back edge: neighbor is on the current DFS path
                int index = path.indexOf(neighbor);
                if (index != -1) {
                    List<T> cycle = new ArrayList<>(path.subList(index, path.size()));
                    allCycles.add(cycle);
                }
            } else if (!visited.contains(neighbor)) {
                dfsVisit(neighbor, adapter, visited, visiting, path, allCycles);
            }
        }

        visiting.remove(current);
        path.removeLast();
        visited.add(current);
    }

    /**
     * Performs a topological sort using Kahn's algorithm (BFS-based).
     *
     * <p>The adapter provides outgoing edges from each node. For topological ordering,
     * the algorithm internally reverses these edges: if {@code adapter.getNeighbors(X)}
     * returns {@code [Y]}, Y must appear before X in the result.
     *
     * <p>Example — group parent chain:
     * <pre>{@code
     *   // Group A has parent B, Group B has parent C
     *   adapter.getNeighbors(A) = [B]
     *   adapter.getNeighbors(B) = [C]
     *   // Result: [C, B, A]  (parents before children)
     * }</pre>
     *
     * @param nodes   all nodes in the graph
     * @param adapter provides outgoing edges from each node (child → parent direction)
     * @return sort result containing the topological order and cycle information
     */
    public static <T> TopoResult<T> topologicalSort(Set<T> nodes, GraphAdapter<T> adapter) {
        // Build reverse adjacency: for each edge node → neighbor,
        // record that neighbor must come before node.
        Map<T, List<T>> predecessors = new HashMap<>();
        Map<T, Integer> inDegree = new HashMap<>();

        for (T node : nodes) {
            inDegree.putIfAbsent(node, 0);
            List<T> neighbors = adapter.getNeighbors(node);
            for (T neighbor : neighbors) {
                // Edge node → neighbor reversed to neighbor → node
                predecessors.computeIfAbsent(neighbor, k -> new ArrayList<>()).add(node);
                inDegree.merge(node, 1, Integer::sum);
            }
        }

        // Seed the queue with nodes that have no incoming dependencies
        Queue<T> queue = new LinkedList<>();
        for (Map.Entry<T, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        // Process in topological order
        List<T> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            T node = queue.poll();
            sorted.add(node);
            for (T successor : predecessors.getOrDefault(node, List.of())) {
                int remaining = inDegree.merge(successor, -1, Integer::sum);
                if (remaining == 0) {
                    queue.add(successor);
                }
            }
        }

        return new TopoResult<>(sorted, nodes);
    }

    private GraphCycleDetector() {}
}
