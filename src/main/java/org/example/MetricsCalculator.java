package org.example;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.vms.Vm;

import java.io.FileWriter;
import java.util.*;

/**
 * Computes 7 metrics from a finished simulation and saves them to CSV.
 *
 * Metrics:
 *  1. Avg Response Time    – average finish time across all cloudlets
 *  2. Makespan             – time the LAST cloudlet finishes
 *  3. Throughput           – tasks / second
 *  4. Load Imbalance Index – std-dev of per-VM LOAD (see below; lower = better)
 *  5. SLA Violations       – cloudlets whose finish time > SLA deadline
 *  6. Energy (kWh)         – total energy consumed across all VMs
 *  7. Cost ($)             – energy cost + per-task billing
 *
 * Load Imbalance Index (FIXED — see project history): previously this was
 * std-dev of raw TASK COUNT per VM, which ignores VM speed entirely — two
 * VMs each holding 3 tasks isn't "balanced" if one VM is 4x faster and
 * finishes with time to spare. It's now std-dev of per-VM LOAD, where
 * load = TL_i / C_i (total assigned task length / VM capacity in MIPS,
 * i.e. each VM's estimated execution-time share). This matches Malik et al.
 * (2021)'s own load definition (Eq. 3-5) and — importantly — matches what
 * AdaptiveGreenPSOScenario's GreenObjectiveEvaluator and
 * PaperBasedPSOScenario's PaperPSOFitness both optimize internally during
 * their PSO search. Before this fix, AdaptiveGreenPSO was being graded on a
 * metric (task count variance) different from the one it was actually
 * trying to minimize (execution-time variance) — this made its reported
 * ImbalanceIndex numbers not reflect what the algorithm was designed to do.
 *
 * NOTE: this changes the ImbalanceIndex column's scale/units for EVERY
 * algorithm (all 9), not just the two PSO variants — re-running will
 * produce different numbers than previous reports for Static, RoundRobin,
 * etc. too. That's expected: it's the same underlying idea (dispersion of
 * per-VM load) measured with a more meaningful unit, not a regression.
 *
 * Energy model:
 *   Each VM has an active power (Watts) and an idle power (20% of active).
 *   For each VM:
 *     activeTime = sum of (cloudlet.length / vm.mips) for each assigned cloudlet
 *     idleTime   = makespan - activeTime
 *     energyWh   = (activeTime × activePower + idleTime × idlePower) / 3600
 *   Total energy in kWh = sum(energyWh) / 1000
 *   (activeTime is now computed once and reused for both the imbalance and
 *   energy sections below — it's the same per-VM quantity either way.)
 */
public class MetricsCalculator {

    public static class Result {
        public final String algorithm;
        public final double avgResponseTime;
        public final double makespan;
        public final double throughput;
        public final double imbalanceIndex;
        public final int    slaViolations;
        public final double energyKWh;
        public final double costUSD;
        public final double co2Grams;

        public Result(String algorithm, double avgResponseTime, double makespan,
                      double throughput, double imbalanceIndex, int slaViolations,
                      double energyKWh, double costUSD, double co2Grams) {
            this.algorithm       = algorithm;
            this.avgResponseTime = avgResponseTime;
            this.makespan        = makespan;
            this.throughput      = throughput;
            this.imbalanceIndex  = imbalanceIndex;
            this.slaViolations   = slaViolations;
            this.energyKWh       = energyKWh;
            this.costUSD         = costUSD;
            this.co2Grams        = co2Grams;
        }
    }

    public static Result compute(String algorithm,
                                 List<? extends Cloudlet> finished,
                                 List<? extends Vm> vms,
                                 CloudSim sim) {
        return compute(algorithm, finished, vms, sim, null);
    }

    public static Result compute(String algorithm,
                                 List<? extends Cloudlet> finished,
                                 List<? extends Vm> vms,
                                 CloudSim sim,
                                 CarbonModel carbonModel) {

        // 1. Avg response time & makespan
        double totalTime = 0, makespan = 0;
        for (Cloudlet c : finished) {
            totalTime += c.getFinishTime();
            makespan   = Math.max(makespan, c.getFinishTime());
        }
        double avgResponse = finished.isEmpty() ? 0 : totalTime / finished.size();

        // 2. Throughput
        double throughput = makespan > 0 ? (double) finished.size() / makespan : 0;

        // Per-VM active (execution) time — TL_i / C_i — shared by both the
        // imbalance index (3) and the energy model (5) below.
        Map<Long, Double> vmActiveTime = new HashMap<>();
        for (Vm vm : vms) vmActiveTime.put(vm.getId(), 0.0);
        for (Cloudlet c : finished) {
            double execTime = (double) c.getLength() / c.getVm().getMips();
            vmActiveTime.merge(c.getVm().getId(), execTime, Double::sum);
        }

        // 3. Load imbalance index — std-dev of per-VM load (TL_i / C_i), see class javadoc
        double meanLoad = vmActiveTime.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = vmActiveTime.values().stream()
                .mapToDouble(load -> Math.pow(load - meanLoad, 2))
                .average().orElse(0);
        double imbalance = Math.sqrt(variance);

        // 4. SLA violations
        int slaViolations = 0;
        for (Cloudlet c : finished) {
            int priority = c.getNetServiceLevel();
            double deadline = InfrastructureFactory.SLA_DEADLINE[
                    Math.min(priority, InfrastructureFactory.SLA_DEADLINE.length - 1)];
            if (c.getFinishTime() > deadline) slaViolations++;
        }

        // 5. Energy consumption (kWh) — reuses vmActiveTime computed above
        double totalEnergyWh = 0.0;
        double co2Grams = 0.0;
        for (int i = 0; i < vms.size(); i++) {
            Vm vm = vms.get(i);
            double activePower = vmPower(vm, i);
            double idlePower   = activePower * InfrastructureFactory.IDLE_POWER_FACTOR;
            double activeTime  = vmActiveTime.getOrDefault(vm.getId(), 0.0);
            double idleTime    = Math.max(0, makespan - activeTime);
            double vmEnergyWh  = (activeTime * activePower + idleTime * idlePower) / 3600.0;
            totalEnergyWh     += vmEnergyWh;
            double vmEnergyKWh = vmEnergyWh / 1000.0;
            double intensity = carbonModel == null ? 475.0 : carbonModel.intensityForVm(i);
            co2Grams += vmEnergyKWh * intensity;
        }
        double energyKWh = totalEnergyWh / 1000.0;

        // 6. Cost ($)
        double costUSD = energyKWh * InfrastructureFactory.COST_PER_KWH
                + finished.size() * InfrastructureFactory.COST_PER_TASK;

        return new Result(algorithm, avgResponse, makespan, throughput,
                imbalance, slaViolations, energyKWh, costUSD,  co2Grams);
    }

    /** Returns the active power (Watts) for a VM by its position in the list. */
    private static double vmPower(Vm vm, int index) {
        double[] powers = InfrastructureFactory.VM_POWER_WATTS;
        if (index < powers.length) return powers[index];
        return InfrastructureFactory.SCALED_VM_POWER_WATTS; // auto-scaled VM
    }

    public static void printAndSave(Result r,
                                    List<? extends Cloudlet> finished,
                                    List<? extends Vm> vms) {

        System.out.println("\n" + "─".repeat(55));
        System.out.printf("  RESULTS  →  %s%n", r.algorithm);
        System.out.println("─".repeat(55));
        System.out.printf("  Avg Response Time   : %.4f s%n",    r.avgResponseTime);
        System.out.printf("  Makespan            : %.4f s%n",    r.makespan);
        System.out.printf("  Throughput          : %.4f tasks/s%n", r.throughput);
        System.out.printf("  Load Imbalance Index: %.4f%n",      r.imbalanceIndex);
        System.out.printf("  SLA Violations      : %d / %d%n",  r.slaViolations, finished.size());
        System.out.printf("  Energy Consumed     : %.6f kWh%n",  r.energyKWh);
        System.out.printf("  Estimated Cost      : $%.6f%n",     r.costUSD);
        System.out.printf("  CO2 Emissions       : %.4f g CO2%n", r.co2Grams);

        // Per-VM summary
        Map<Long, List<Cloudlet>> vmMap = new LinkedHashMap<>();
        for (Vm vm : vms) vmMap.put(vm.getId(), new ArrayList<>());
        for (Cloudlet c : finished)
            vmMap.computeIfAbsent(c.getVm().getId(), k -> new ArrayList<>()).add(c);

        System.out.println("\n  Per-VM breakdown:");
        for (Vm vm : vms) {
            List<Cloudlet> cl = vmMap.getOrDefault(vm.getId(), List.of());
            double vmAvg = cl.stream().mapToDouble(Cloudlet::getFinishTime).average().orElse(0);
            System.out.printf("    VM-%d (%4d MIPS) → %2d tasks | avg finish: %.2f s%n",
                    vm.getId(), (long) vm.getMips(), cl.size(), vmAvg);
        }

        // Save to CSV (9 columns)
        try (FileWriter fw = new FileWriter("results.csv", true)) {
            fw.write(String.format(Locale.US, "%s,%.4f,%.4f,%.4f,%.4f,%d,%.6f,%.6f,%.4f%n",
                    r.algorithm, r.avgResponseTime, r.makespan, r.throughput,
                    r.imbalanceIndex, r.slaViolations, r.energyKWh, r.costUSD, r.co2Grams));
            System.out.println("\n  ✔ Saved to results.csv");
        } catch (Exception e) {
            System.err.println("  ✘ Could not save CSV: " + e.getMessage());
        }
    }
}
