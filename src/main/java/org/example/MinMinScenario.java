package org.example;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.util.*;

/**
 * SCENARIO 5 – Min-Min Scheduling Algorithm
 *
 * Classic heuristic from grid computing literature.
 * For each unassigned task, computes the Estimated Completion Time (ECT)
 * on every VM and assigns the task to the VM with the MINIMUM ECT.
 *
 * ECT(task, vm) = readyTime[vm] + taskLength / vmMips
 *
 * This minimises makespan more effectively than round-robin approaches
 * because it respects both current VM load AND task length.
 */
public class MinMinScenario {

    public void run() {

        System.out.println("\n" + "=".repeat(65));
        System.out.println("  SCENARIO 5 — MIN-MIN SCHEDULING");
        System.out.println("=".repeat(65));

        CloudSim sim = new CloudSim();
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(sim);
        new DatacenterSimple(sim, InfrastructureFactory.createHosts());

        List<VmSimple>       vms       = InfrastructureFactory.createVMs();
        List<CloudletSimple> cloudlets = InfrastructureFactory.createCloudlets();

        broker.submitVmList(vms);

        // readyTime[vm] = earliest time this VM will be free
        Map<VmSimple, Double> readyTime = new LinkedHashMap<>();
        for (VmSimple vm : vms) readyTime.put(vm, 0.0);

        // Work on a copy so we can remove as we assign
        List<CloudletSimple> unassigned = new ArrayList<>(cloudlets);

        System.out.println("\n  Task assignment (Min-Min):");
        int taskIdx = 0;

        while (!unassigned.isEmpty()) {

            // For each unassigned task, find its minimum ECT across all VMs
            CloudletSimple bestTask = null;
            VmSimple       bestVm   = null;
            double         bestECT  = Double.MAX_VALUE;

            for (CloudletSimple c : unassigned) {
                for (VmSimple vm : vms) {
                    double ect = readyTime.get(vm) + (double) c.getLength() / vm.getMips();
                    if (ect < bestECT) {
                        bestECT  = ect;
                        bestTask = c;
                        bestVm   = vm;
                    }
                }
            }

            // Assign the task with the global minimum ECT
            bestTask.setVm(bestVm);
            readyTime.put(bestVm, bestECT);
            unassigned.remove(bestTask);

            System.out.printf(
                    "    Task %2d [MI=%5d, Pri=%s] → VM-%d  (ECT=%.3f s)%n",
                    taskIdx++,
                    bestTask.getLength(),
                    priorityLabel(bestTask.getNetServiceLevel()),
                    bestVm.getId(),
                    bestECT
            );
        }

        System.out.println("\n  Estimated ready times after assignment:");
        for (VmSimple vm : vms) {
            System.out.printf("    VM-%d (%4d MIPS) → ready at %.3f s%n",
                    vm.getId(), (long) vm.getMips(), readyTime.get(vm));
        }

        Workflow workflow = WorkflowUtil.standardWorkflow(cloudlets);
        double[] vmMips = vms.stream().mapToDouble(VmSimple::getMips).toArray();
        WorkflowUtil.applyDependencyDelays(cloudlets, vms, workflow.topologicalOrder(), vmMips);

        broker.submitCloudletList(cloudlets);
        sim.start();

        MetricsCalculator.Result result = MetricsCalculator.compute(
                "MinMin",
                broker.getCloudletFinishedList(),
                new ArrayList<>(vms),
                sim);

        MetricsCalculator.printAndSave(result, broker.getCloudletFinishedList(), new ArrayList<>(vms));
    }

    private static String priorityLabel(int p) {
        return switch (p) { case 0 -> "HIGH  "; case 1 -> "MEDIUM"; default -> "LOW   "; };
    }
}