package org.example;

import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;

/**
 * SCENARIO 3 – Weighted Round Robin Load Balancing
 * VMs receive tasks in proportion to their MIPS rating.
 *
 *   VM-0 (2000 MIPS) : weight 4
 *   VM-1 (1000 MIPS) : weight 2
 *   VM-2  (500 MIPS) : weight 1
 */
public class WeightedRoundRobinScenario {

    public void run() {

        System.out.println("\n" + "=".repeat(65));
        System.out.println("  SCENARIO 3 — WEIGHTED ROUND ROBIN");
        System.out.println("=".repeat(65));

        InfrastructureFactory.SimulationBundle bundle = InfrastructureFactory.createSimulation();

        List<VmSimple>       vms       = InfrastructureFactory.createVMs();
        List<CloudletSimple> cloudlets = InfrastructureFactory.createCloudlets();

        bundle.broker.submitVmList(vms);

        // Build weighted pool from MIPS ratios
        long[] mipsValues = InfrastructureFactory.VM_MIPS;
        long gcd = gcd(gcd(mipsValues[0], mipsValues[1]), mipsValues[2]);

        List<VmSimple> weightedPool = new ArrayList<>();
        for (int v = 0; v < vms.size(); v++) {
            int weight = (int) (mipsValues[v] / gcd);
            for (int w = 0; w < weight; w++) weightedPool.add(vms.get(v));
        }

        System.out.println("  Weighted pool:");
        for (int v = 0; v < vms.size(); v++) {
            System.out.printf("    VM-%d (%4d MIPS) → weight %d%n",
                    v, mipsValues[v], (int)(mipsValues[v] / gcd));
        }

        System.out.println("\n  Task assignment:");
        int poolSize = weightedPool.size();
        for (int i = 0; i < cloudlets.size(); i++) {
            CloudletSimple c  = cloudlets.get(i);
            VmSimple       vm = weightedPool.get(i % poolSize);
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
                "WeightedRR",
                bundle.broker.getCloudletFinishedList(),
                vms,
                bundle.sim);

        MetricsCalculator.printAndSave(result, bundle.broker.getCloudletFinishedList(), vms);
    }

    private static long gcd(long a, long b) { return b == 0 ? a : gcd(b, a % b); }

    private static String priorityLabel(int p) {
        return switch (p) { case 0 -> "HIGH  "; case 1 -> "MEDIUM"; default -> "LOW   "; };
    }
}