package org.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A lightweight DAG task used by the paper-based scheduler. */
public class WorkflowTask {
    private final int id;
    private final long length;
    private final int priority;
    private final List<Integer> parents = new ArrayList<>();
    private final List<Integer> children = new ArrayList<>();

    public WorkflowTask(int id, long length, int priority) {
        this.id = id;
        this.length = length;
        this.priority = priority;
    }

    public int getId() { return id; }
    public long getLength() { return length; }
    public int getPriority() { return priority; }

    public void addParent(int parentId) {
        if (!parents.contains(parentId)) parents.add(parentId);
    }

    public void addChild(int childId) {
        if (!children.contains(childId)) children.add(childId);
    }

    public List<Integer> getParents() { return Collections.unmodifiableList(parents); }
    public List<Integer> getChildren() { return Collections.unmodifiableList(children); }
}
