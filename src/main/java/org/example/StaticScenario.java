package org.example;

import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.util.List;

/**
 * SCENARIO 1 – Static Load Balancing (Baseline / Unbalanced)
 * Every cloudlet is pinned to VM-0 regardless of length or priority.
 */
public class StaticScenario {

    public void run() {

        System.out.println("\n" + "=".repeat(65));
        System.out.println("  SCENARIO 1 — STATIC (all tasks → VM-0)");
        System.out.println("=".repeat(65));

        InfrastructureFactory.SimulationBundle bundle = InfrastructureFactory.createSimulation();

        List<VmSimple>       vms       = InfrastructureFactory.createVMs();
        List<CloudletSimple> cloudlets = InfrastructureFactory.createCloudlets();

        bundle.broker.submitVmList(vms);

        System.out.println("\n  Task assignment:");
        int idx = 0;
        for (CloudletSimple c : cloudlets) {
            c.setVm(vms.get(0));
            System.out.printf("    Task %2d [MI=%5d, Pri=%s] → VM-0%n",
                    idx++, c.getLength(), priorityLabel(c.getNetServiceLevel()));
        }

        // Enforce the project's workflow DAG against the real simulation
        // (see WorkflowUtil) so makespan/SLA reflect dependency-constrained
        // scheduling like every other scenario's reported numbers do.
        Workflow workflow = WorkflowUtil.standardWorkflow(cloudlets);
        double[] vmMips = vms.stream().mapToDouble(VmSimple::getMips).toArray();
        WorkflowUtil.applyDependencyDelays(cloudlets, vms, workflow.topologicalOrder(), vmMips);

        bundle.broker.submitCloudletList(cloudlets);
        bundle.sim.start();

        MetricsCalculator.Result result = MetricsCalculator.compute(
                "Static",
                bundle.broker.getCloudletFinishedList(),
                vms,
                bundle.sim);

        MetricsCalculator.printAndSave(result, bundle.broker.getCloudletFinishedList(), vms);
    }

    private static String priorityLabel(int p) {
        return switch (p) { case 0 -> "HIGH  "; case 1 -> "MEDIUM"; default -> "LOW   "; };
    }
}