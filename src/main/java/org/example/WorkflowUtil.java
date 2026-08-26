package org.example;

import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralizes the project's 15-task workflow DAG and enforces it against the
 * REAL CloudSim run.
 *
 * BUG THIS FIXES (2025-08-26): Workflow/WorkflowPreprocessor dependencies were
 * only ever consulted by the *analytical* fitness functions used to search
 * for an assignment (GreenObjectiveEvaluator, PaperPSOFitness,
 * CarbonRefinement). The actual CloudSim simulation that produces
 * results.csv — for ALL NINE scenarios, not just the two workflow-aware
 * ones — submitted every cloudlet with zero delay and no dependency
 * gating, so CloudSim itself never enforced "task 4 can't start before
 * tasks 0 and 1 finish." That means the makespan/SLA numbers being compared
 * across algorithms were never actually workflow-constrained, even though
 * the whole project is framed as workflow scheduling: e.g. the DAG's
 * critical path takes >= 25s on even the fastest VM by construction, yet
 * results.csv reported makespans of 11-14s, which is only possible if
 * dependencies were being ignored at simulation time.
 *
 * FIX: build the SAME workflow (same edges, since the DAG describes the
 * workload, not the algorithm) for every scenario, and after each
 * algorithm has finished assigning cloudlet.setVm(...), walk the
 * dependency-respecting schedule implied by that assignment and set each
 * cloudlet's submission delay to its analytical start time. CloudSim will
 * then refuse to start a cloudlet before its dependencies are satisfied,
 * for every algorithm uniformly, so the comparison table is finally
 * apples-to-apples with what the two PSO variants were actually optimizing.
 *
 * This uses the same single-server-per-VM start-time model already used by
 * GreenObjectiveEvaluator/PaperPSOFitness/CarbonRefinement, so the
 * analytical numbers those classes report and what CloudSim now actually
 * measures should track closely instead of being two unrelated numbers
 * that happen to share a column name.
 */
public class WorkflowUtil {

    /** The project's fixed 15-task DAG (5 HIGH + 5 MEDIUM + 5 LOW priority tasks). */
    public static Workflow standardWorkflow(List<CloudletSimple> cloudlets) {
        java.util.List<WorkflowTask> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < cloudlets.size(); i++) {
            tasks.add(new WorkflowTask(i, cloudlets.get(i).getLength(), cloudlets.get(i).getNetServiceLevel()));
        }
        Workflow w = new Workflow(tasks);
        int[][] edges = {
                {0, 3}, {0, 4}, {1, 4}, {1, 5}, {2, 5},
                {3, 6}, {4, 6}, {4, 7}, {5, 7},
                {6, 8}, {7, 8}, {7, 9}, {8, 10}, {9, 10},
                {10, 11}, {10, 12}, {11, 13}, {12, 13}, {13, 14}
        };
        for (int[] edge : edges) w.addDependency(edge[0], edge[1]);
        return w;
    }

    /**
     * Sets each cloudlet's submission delay so it cannot start before its
     * parents have finished AND its assigned VM is free, given the
     * cloudlet-to-VM assignment already applied via setVm(). Must be called
     * after every cloudlet has a VM assigned and before broker.submitCloudletList/sim.start().
     *
     * vms must be in the same order as vmMips (both indexed 0..nVms-1 as
     * produced by InfrastructureFactory.createVMs()/VM_MIPS).
     */
    public static void applyDependencyDelays(List<CloudletSimple> cloudlets, List<VmSimple> vms,
                                              List<WorkflowTask> topologicalOrder, double[] vmMips) {
        // IdentityHashMap, not HashMap: VmSimple hashes/equals by its
        // CloudSim-assigned id, which is only set once broker.submitVmList()
        // runs. If this map is built (or looked up) beforehand, every VM
        // still shares the same default id and a HashMap would silently
        // collapse all of them into one bucket — every cloudlet then
        // resolves to whichever VM was inserted last, regardless of which
        // VM it's actually assigned to. Reference identity sidesteps that
        // entirely, so this is correct no matter when it's called relative
        // to submitVmList().
        Map<VmSimple, Integer> vmIndex = new IdentityHashMap<>();
        for (int i = 0; i < vms.size(); i++) vmIndex.put(vms.get(i), i);

        Map<Integer, Double> finish = new HashMap<>();
        double[] vmAvailable = new double[vms.size()];

        for (WorkflowTask task : topologicalOrder) {
            CloudletSimple cloudlet = cloudlets.get(task.getId());
            Integer vmIdx = vmIndex.get(cloudlet.getVm());
            if (vmIdx == null) {
                throw new IllegalStateException(
                        "Cloudlet " + task.getId() + " has no VM assigned yet — "
                      + "applyDependencyDelays must run after every setVm() call.");
            }

            double parentReady = 0.0;
            for (int parent : task.getParents()) {
                parentReady = Math.max(parentReady, finish.getOrDefault(parent, 0.0));
            }

            double start = Math.max(vmAvailable[vmIdx], parentReady);
            double execution = task.getLength() / vmMips[vmIdx];
            double end = start + execution;

            finish.put(task.getId(), end);
            vmAvailable[vmIdx] = end;
            cloudlet.setSubmissionDelay(start);
        }
    }
}
