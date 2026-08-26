package org.example;

import java.util.*;

/** Directed acyclic workflow used by the paper-based PSO experiment. */
public class Workflow {
    private final List<WorkflowTask> tasks;

    public Workflow(List<WorkflowTask> tasks) {
        this.tasks = new ArrayList<>(tasks);
        validate();
    }

    public List<WorkflowTask> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public WorkflowTask getTask(int id) {
        return tasks.stream().filter(t -> t.getId() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown task: " + id));
    }

    public void addDependency(int parentId, int childId) {
        WorkflowTask parent = getTask(parentId);
        WorkflowTask child = getTask(childId);
        parent.addChild(childId);
        child.addParent(parentId);
        validate();
    }

    /** Returns a deterministic topological order; throws if a cycle exists. */
    public List<WorkflowTask> topologicalOrder() {
        Map<Integer, Integer> indegree = new HashMap<>();
        for (WorkflowTask t : tasks) indegree.put(t.getId(), t.getParents().size());

        PriorityQueue<Integer> ready = new PriorityQueue<>();
        indegree.forEach((id, degree) -> { if (degree == 0) ready.add(id); });

        List<WorkflowTask> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            int id = ready.poll();
            WorkflowTask task = getTask(id);
            order.add(task);
            for (int child : task.getChildren()) {
                int degree = indegree.merge(child, -1, Integer::sum);
                if (degree == 0) ready.add(child);
            }
        }
        if (order.size() != tasks.size()) {
            throw new IllegalArgumentException("Workflow contains a cycle");
        }
        return order;
    }

    private void validate() {
        // Validate ids and DAG whenever a workflow is constructed/modified.
        Set<Integer> ids = new HashSet<>();
        for (WorkflowTask t : tasks) {
            if (!ids.add(t.getId())) throw new IllegalArgumentException("Duplicate task id: " + t.getId());
        }
        for (WorkflowTask t : tasks) {
            for (int p : t.getParents()) if (!ids.contains(p)) throw new IllegalArgumentException("Unknown parent: " + p);
            for (int c : t.getChildren()) if (!ids.contains(c)) throw new IllegalArgumentException("Unknown child: " + c);
        }
        if (!tasks.isEmpty()) topologicalOrder();
    }
}
