package org.example;

import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.util.List;

/**
 * SCENARIO 2 – Round Robin Load Balancing
 * Assigns cloudlets to VMs in a simple cyclic fashion.
 */
public class RoundRobinScenario {

    public void run() {

        System.out.println("\n" + "=".repeat(65));
        System.out.println("  SCENARIO 2 — ROUND ROBIN");
        System.out.println("=".repeat(65));

        InfrastructureFactory.SimulationBundle bundle = InfrastructureFactory.createSimulation();

        List<VmSimple>       vms       = InfrastructureFactory.createVMs();
        List<CloudletSimple> cloudlets = InfrastructureFactory.createCloudlets();

        bundle.broker.submitVmList(vms);

        System.out.println("\n  Task assignment:");
        int vmCount = vms.size();
        for (int i = 0; i < cloudlets.size(); i++) {
            CloudletSimple c  = cloudlets.get(i);
            VmSimple       vm = vms.get(i % vmCount);
            c.setVm(vm);
            System.out.printf("    Task %2d [MI=%5d, Pri=%s] → VM-%d%n",
                    i, c.getLength(), priorityLabel(c.getNetServiceLevel()), vm.getId());
        }

        Workflow workflow = WorkflowUtil.standardWorkflow(cloudlets);
        double[] vmMips = vms.stream().mapToDouble(VmSimple::getMips).toArray();
        WorkflowUtil.applyDependencyDelays(cloudlets, vms, workflow.topologicalOrder(), vmMips);

        bundle.broker.submitCloudletList(cloudlets);
        bundle.sim.start();

        MetricsCalculator.Result result = MetricsCalculator.compute(
                "RoundRobin",
                bundle.broker.getCloudletFinishedList(),
                vms,
                bundle.sim);

        MetricsCalculator.printAndSave(result, bundle.broker.getCloudletFinishedList(), vms);
    }

    private static String priorityLabel(int p) {
        return switch (p) { case 0 -> "HIGH  "; case 1 -> "MEDIUM"; default -> "LOW   "; };
    }
}