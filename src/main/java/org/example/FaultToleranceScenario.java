package org.example;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.util.*;

/**
 * SCENARIO 9 – Fault-Tolerant Scheduling with VM Failure Injection
 *
 * Simulates a real-world cloud failure event:
 *   Phase 1 – Normal operation: tasks distributed across all 5 VMs.
 *   Fault    – VM-0 (fastest VM, 2000 MIPS) crashes after completing
 *              its first FAULT_TRIGGER_TASKS tasks.
 *   Phase 2  – Fault detection: tasks still assigned to VM-0 are
 *              identified and MIGRATED to the healthiest surviving VM.
 *              Each migrated task incurs a MIGRATION_OVERHEAD penalty.
 *   Report   – Prints rescue summary and a Resilience Score:
 *              ResilienceScore = 1 - (recoveryOverhead / totalMakespan)
 *              Higher = better recovery (1.0 = no overhead at all).
 */
public class FaultToleranceScenario {

    private static final int    FAILED_VM_INDEX      = 0;    // VM-0 will crash
    private static final int    FAULT_TRIGGER_TASKS  = 2;    // tasks completed before crash
    private static final double MIGRATION_OVERHEAD   = 0.5;  // extra seconds per rescued task

    public void run() {

        System.out.println("\n" + "=".repeat(65));
        System.out.println("  SCENARIO 9 — FAULT-TOLERANT SCHEDULING");
        System.out.println("=".repeat(65));

        CloudSim sim = new CloudSim();
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(sim);
        new DatacenterSimple(sim, InfrastructureFactory.createHosts());

        List<VmSimple>       vms       = InfrastructureFactory.createVMs();
        List<CloudletSimple> cloudlets = InfrastructureFactory.createCloudlets();

        broker.submitVmList(vms);          // ✅ VMs get real IDs here
        broker.submitCloudletList(cloudlets);
        

        // ── Phase 1: Normal assignment (Adaptive policy) ─────────────────
        System.out.println("\n  [Phase 1] Normal operation — all VMs healthy");
        Map<VmSimple, Integer> loadMap = new LinkedHashMap<>();
        for (VmSimple vm : vms) loadMap.put(vm, 0);

        for (CloudletSimple c : cloudlets) {
            VmSimple best = selectHealthyVm(vms, loadMap, null);
            c.setVm(best);
            loadMap.merge(best, 1, Integer::sum);
        }

        printLoadSummary(vms, cloudlets, loadMap);

        // ── Fault injection ───────────────────────────────────────────────
        VmSimple failedVm = vms.get(FAILED_VM_INDEX);
        System.out.printf("%n  [FAULT] VM-%d (%d MIPS) has CRASHED after %d tasks!%n",
                failedVm.getId(), (long) failedVm.getMips(), FAULT_TRIGGER_TASKS);
        System.out.println("  [FAULT] Initiating automatic fault recovery...");

        List<VmSimple> healthyVms = new ArrayList<>(vms);
        healthyVms.remove(failedVm);

        // Identify tasks on the failed VM that haven't completed yet
        List<CloudletSimple> stranded = new ArrayList<>();
        int completedOnFailed = 0;
        for (CloudletSimple c : cloudlets) {
            if (c.getVm().getId() == failedVm.getId()) {
                if (completedOnFailed < FAULT_TRIGGER_TASKS) {
                    completedOnFailed++; // these finished before crash
                } else {
                    stranded.add(c);    // these need rescue
                }
            }
        }

        // ── Phase 2: Migration of stranded tasks ──────────────────────────
        System.out.printf("%n  [Phase 2] Rescuing %d stranded task(s) from VM-%d:%n",
                stranded.size(), failedVm.getId());

        double totalMigrationOverhead = 0;
        for (CloudletSimple c : stranded) {
            VmSimple rescue = selectHealthyVm(healthyVms, loadMap, null);
            // Extend task length to simulate migration overhead
            long penalisedLength = (long)(c.getLength() + MIGRATION_OVERHEAD * rescue.getMips());
            c.setLength(penalisedLength);
            c.setVm(rescue);
            loadMap.merge(rescue, 1, Integer::sum);
            totalMigrationOverhead += MIGRATION_OVERHEAD;
            System.out.printf("    Task %2d [MI=%6d] rescued → VM-%d  (+%.1fs overhead)%n",
                    cloudlets.indexOf(c), penalisedLength,
                    rescue.getId(), MIGRATION_OVERHEAD);
        }

        System.out.printf("%n  [Recovery complete] %d tasks migrated | total overhead: %.1f s%n",
                stranded.size(), totalMigrationOverhead);

        // Applied after migration so delays reflect the penalised lengths
        // and final (possibly rescued) VM assignments.
        Workflow workflow = WorkflowUtil.standardWorkflow(cloudlets);
        double[] vmMips = vms.stream().mapToDouble(VmSimple::getMips).toArray();
        WorkflowUtil.applyDependencyDelays(cloudlets, vms, workflow.topologicalOrder(), vmMips);

        sim.start();


        // ── Resilience Score ──────────────────────────────────────────────
        double makespan = broker.getCloudletFinishedList().stream()
                .mapToDouble(c -> c.getFinishTime()).max().orElse(1);
        double resilienceScore = Math.max(0,
                1.0 - (totalMigrationOverhead / makespan));

        System.out.println("\n" + "─".repeat(55));
        System.out.printf("  FAULT TOLERANCE REPORT%n");
        System.out.println("─".repeat(55));
        System.out.printf("  Failed VM        : VM-%d (%d MIPS)%n",
                failedVm.getId(), (long) failedVm.getMips());
        System.out.printf("  Tasks rescued    : %d%n", stranded.size());
        System.out.printf("  Migration cost   : %.1f s%n", totalMigrationOverhead);
        System.out.printf("  Final makespan   : %.4f s%n", makespan);
        System.out.printf("  Resilience Score : %.4f  (1.0 = perfect)%n", resilienceScore);

        MetricsCalculator.Result result = MetricsCalculator.compute(
                "FaultTolerant",
                broker.getCloudletFinishedList(),
                healthyVms, sim);

        MetricsCalculator.printAndSave(result,
                broker.getCloudletFinishedList(), healthyVms);
    }

    /** Picks the VM with the lowest current load, excluding the failed VM. */
    private VmSimple selectHealthyVm(List<VmSimple> vms,
                                     Map<VmSimple, Integer> loadMap,
                                     VmSimple exclude) {
        return vms.stream()
                .filter(vm -> !vm.equals(exclude))
                .min(Comparator.comparingInt(vm -> loadMap.getOrDefault(vm, 0)))
                .orElseThrow();
    }

    private void printLoadSummary(List<VmSimple> vms,
                                  List<CloudletSimple> cloudlets,
                                  Map<VmSimple, Integer> loadMap) {
        System.out.println("  Initial assignment:");
        for (VmSimple vm : vms)
            System.out.printf("    VM-%d (%4d MIPS) → %d tasks%n",
                    vm.getId(), (long) vm.getMips(), loadMap.get(vm));
    }
}
