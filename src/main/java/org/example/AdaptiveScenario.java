package org.example;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.util.*;

/**
 * SCENARIO 4 – Adaptive Priority-Aware Load Balancing
 *
 * Features:
 *  1) Priority ordering   – HIGH tasks assigned before MEDIUM, then LOW
 *  2) Load-aware routing  – picks VM with smallest current ESTIMATED LOAD
 *  3) Speed awareness     – breaks ties by preferring the faster VM
 *  4) Threshold guard     – prefers VMs below an overload threshold
 *  5) Auto-scaling        – if ALL VMs exceed threshold, provisions an extra VM
 *
 * FIX: load was previously tracked as raw TASK COUNT per VM. With a
 * perfectly divisible workload (15 tasks / 5 VMs) and ties always resolved
 * toward the fastest VM first, count-based greedy assignment mathematically
 * degenerates into the exact same VM-0..VM-4 cycling pattern as
 * RoundRobinScenario — "Adaptive" and "RoundRobin" were producing
 * byte-identical results. Worse, OVERLOAD_THRESHOLD as a task COUNT could
 * only ever be reached on the very last task of a balanced workload, so
 * auto-scaling never had a real chance to fire.
 *
 * Load is now tracked as ESTIMATED CUMULATIVE EXECUTION TIME per VM (sum of
 * assigned task length / VM MIPS). This is what "load-aware" and
 * "speed-aware" are actually supposed to mean — a fast VM naturally
 * accumulates less time-load per task than a slow one, so placement now
 * genuinely differs from round-robin, and the overload threshold is
 * calibrated in seconds so it can plausibly fire (a slow VM taking on a
 * single LOW-priority 9000-MI task alone exceeds it — see constant below).
 */
public class AdaptiveScenario {

    /**
     * Overload threshold in ESTIMATED SECONDS of assigned work, not task count.
     * Calibrated against this project's default workload (VM MIPS 500-2000,
     * task lengths 2000/5000/9000 MI): a HIGH task takes 1.0-4.0s depending
     * on VM speed, a LOW task takes 4.5-18.0s. 6.0s means a fast VM can
     * absorb several HIGH tasks before flagging, while a slow VM flags after
     * a single MEDIUM/LOW task — which is the actual point of a speed-aware
     * threshold guard. Re-tune if you change the default task/VM profile.
     */
    private static final double OVERLOAD_THRESHOLD_SECONDS = 6.0;
    private static final long   SCALED_VM_MIPS             = 1500;

    public void run() {

        System.out.println("\n" + "=".repeat(65));
        System.out.println("  SCENARIO 4 — ADAPTIVE (priority + auto-scale)");
        System.out.println("=".repeat(65));

        CloudSim sim = new CloudSim();
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(sim);
        new DatacenterSimple(sim, InfrastructureFactory.createHosts());

        List<VmSimple> vms = InfrastructureFactory.createVMs();
        broker.submitVmList(vms);

        // Sort cloudlets: HIGH first, then MEDIUM, then LOW
        List<CloudletSimple> cloudlets = InfrastructureFactory.createCloudlets();
        cloudlets.sort(Comparator.comparingInt(CloudletSimple::getNetServiceLevel));

        Map<VmSimple, Double>        loadMap = new LinkedHashMap<>(); // estimated seconds of assigned work
        Map<VmSimple, List<Integer>> vmTasks = new LinkedHashMap<>();
        for (VmSimple vm : vms) { loadMap.put(vm, 0.0); vmTasks.put(vm, new ArrayList<>()); }

        boolean scaled = false;

        System.out.println("\n  Task assignment (priority-sorted, time-based load):");
        int taskIdx = 0;
        for (CloudletSimple c : cloudlets) {

            // Auto-scaling check — now meaningful: fires once every VM's
            // estimated load exceeds the threshold, not only on the last task.
            boolean allOverloaded = loadMap.values().stream()
                    .allMatch(load -> load >= OVERLOAD_THRESHOLD_SECONDS);

            if (allOverloaded && !scaled) {
                System.out.println("\n  All VMs over the time threshold — provisioning extra VM (1500 MIPS)...");
                VmSimple extraVm = new VmSimple(SCALED_VM_MIPS, 1);
                extraVm.setRam(768).setBw(1500).setSize(10_000);
                extraVm.setCloudletScheduler(new CloudletSchedulerTimeShared());
                vms.add(extraVm);
                broker.submitVm(extraVm);
                loadMap.put(extraVm, 0.0);
                vmTasks.put(extraVm, new ArrayList<>());
                scaled = true;
                System.out.println("  Extra VM added to the pool.\n");
            }

            VmSimple selected = selectBestVm(vms, loadMap);
            double estDuration = (double) c.getLength() / selected.getMips();
            c.setVm(selected);
            loadMap.merge(selected, estDuration, Double::sum);
            vmTasks.get(selected).add(taskIdx);

            System.out.printf("    Task %2d [MI=%5d, Pri=%s] → VM-%d (load now: %.2fs)%n",
                    taskIdx++, c.getLength(),
                    priorityLabel(c.getNetServiceLevel()),
                    selected.getId(), loadMap.get(selected));
        }

        System.out.println("\n  Final load distribution:");
        for (VmSimple vm : vms) {
            System.out.printf("    VM-%d (%4d MIPS) → %.2fs estimated load  %s%n",
                    vm.getId(), (long) vm.getMips(), loadMap.get(vm), vmTasks.get(vm));
        }

        // NOTE: createCloudlets() already emits HIGH,MEDIUM,LOW in that
        // order and the priority sort above is stable, so cloudlet index
        // still equals the standard DAG's task id here.
        Workflow workflow = WorkflowUtil.standardWorkflow(cloudlets);
        double[] vmMips = vms.stream().mapToDouble(VmSimple::getMips).toArray();
        WorkflowUtil.applyDependencyDelays(cloudlets, vms, workflow.topologicalOrder(), vmMips);

        broker.submitCloudletList(cloudlets);
        sim.start();

        MetricsCalculator.Result result = MetricsCalculator.compute(
                "Adaptive",
                broker.getCloudletFinishedList(),
                new ArrayList<>(vms),
                sim);

        MetricsCalculator.printAndSave(result, broker.getCloudletFinishedList(), new ArrayList<>(vms));
    }

    private VmSimple selectBestVm(List<VmSimple> vms, Map<VmSimple, Double> loadMap) {
        VmSimple best = null;
        double bestLoad = Double.MAX_VALUE;

        // Prefer VMs under the time threshold
        for (VmSimple vm : vms) {
            double load = loadMap.get(vm);
            if (load < OVERLOAD_THRESHOLD_SECONDS) {
                if (load < bestLoad || (load == bestLoad && best != null && vm.getMips() > best.getMips())) {
                    best = vm; bestLoad = load;
                }
            }
        }

        // All above threshold – pick globally least-loaded
        if (best == null) {
            for (VmSimple vm : vms) {
                double load = loadMap.get(vm);
                if (load < bestLoad || (load == bestLoad && best != null && vm.getMips() > best.getMips())) {
                    best = vm; bestLoad = load;
                }
            }
        }
        return best;
    }

    private static String priorityLabel(int p) {
        return switch (p) { case 0 -> "HIGH  "; case 1 -> "MEDIUM"; default -> "LOW   "; };
    }
}
