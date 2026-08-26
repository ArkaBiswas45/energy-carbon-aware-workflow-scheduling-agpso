package org.example;

import java.util.*;

/**
 * Paper-inspired preprocessing: calculate workflow depth and task length,
 * derive data-driven thresholds, and classify tasks into queues.
 *
 * Thresholds use medians in this implementation. This is an implementation
 * choice for a reproducible CloudSim experiment, not a claim that the paper
 * mandates the median formula.
 */
public class WorkflowPreprocessor {
    public static class Result {
        public final Map<Integer, Integer> depth;
        public final double depthThreshold;
        public final double lengthThreshold;
        public final List<WorkflowTask> depthQueue;
        public final List<WorkflowTask> lengthQueue;
        public final List<WorkflowTask> criticalQueue;
        public final List<WorkflowTask> normalQueue;
        public final List<WorkflowTask> topologicalOrder;

        Result(Map<Integer, Integer> depth, double depthThreshold, double lengthThreshold,
               List<WorkflowTask> depthQueue, List<WorkflowTask> lengthQueue,
               List<WorkflowTask> criticalQueue, List<WorkflowTask> normalQueue,
               List<WorkflowTask> topologicalOrder) {
            this.depth = depth;
            this.depthThreshold = depthThreshold;
            this.lengthThreshold = lengthThreshold;
            this.depthQueue = depthQueue;
            this.lengthQueue = lengthQueue;
            this.criticalQueue = criticalQueue;
            this.normalQueue = normalQueue;
            this.topologicalOrder = topologicalOrder;
        }
    }

    public Result process(Workflow workflow) {
        List<WorkflowTask> order = workflow.topologicalOrder();
        Map<Integer, Integer> depth = new HashMap<>();
        for (WorkflowTask task : order) {
            int d = 0;
            for (int parent : task.getParents()) {
                d = Math.max(d, depth.get(parent) + 1);
            }
            depth.put(task.getId(), d);
        }

        double depthThreshold = median(depth.values().stream().mapToDouble(Integer::doubleValue).toArray());
        double lengthThreshold = median(workflow.getTasks().stream()
                .mapToDouble(t -> t.getLength()).toArray());

        List<WorkflowTask> depthQueue = new ArrayList<>();
        List<WorkflowTask> lengthQueue = new ArrayList<>();
        List<WorkflowTask> criticalQueue = new ArrayList<>();
        List<WorkflowTask> normalQueue = new ArrayList<>();

        for (WorkflowTask task : order) {
            boolean deep = depth.get(task.getId()) >= depthThreshold;
            boolean longTask = task.getLength() >= lengthThreshold;
            if (deep) depthQueue.add(task);
            if (longTask) lengthQueue.add(task);
            if (deep && longTask) criticalQueue.add(task);
            if (!deep && !longTask) normalQueue.add(task);
        }

        return new Result(depth, depthThreshold, lengthThreshold,
                depthQueue, lengthQueue, criticalQueue, normalQueue, order);
    }

    private static double median(double[] values) {
        if (values.length == 0) return 0;
        Arrays.sort(values);
        int mid = values.length / 2;
        return values.length % 2 == 0 ? (values[mid - 1] + values[mid]) / 2.0 : values[mid];
    }
}
