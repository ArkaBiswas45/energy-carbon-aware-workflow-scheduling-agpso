package org.example;

import java.util.*;

/**
 * Paper-inspired PSO objectives: makespan, energy and load imbalance.
 * All objectives are minimized. Normalization is performed over the current
 * swarm so different units do not dominate the combined objective.
 *
 * FIX: load imbalance was previously computed from per-VM TASK COUNT
 * variance. This didn't match Malik et al. (2021)'s own load definition
 * (Eq. 3-5: Lvmi = TL_i / C_i, an execution-time share, not a task count),
 * and — after the same fix was applied to MetricsCalculator — no longer
 * matched the reported ImbalanceIndex this algorithm is graded on either.
 * Both now use per-VM active-time variance, so what this fitness function
 * searches for during PSO and what ends up in the final report are the same
 * quantity, and this scenario's load-balance objective is now faithful to
 * the equation it's meant to reproduce.
 */
public class PaperPSOFitness {
    public static class Objectives {
        public final double makespan;
        public final double energyKWh;
        public final double loadImbalance;
        public Objectives(double makespan, double energyKWh, double loadImbalance) {
            this.makespan = makespan;
            this.energyKWh = energyKWh;
            this.loadImbalance = loadImbalance;
        }
    }

    public static double fitness(Objectives o, List<Objectives> population) {
        double mMin = min(population, x -> x.makespan), mMax = max(population, x -> x.makespan);
        double eMin = min(population, x -> x.energyKWh), eMax = max(population, x -> x.energyKWh);
        double lMin = min(population, x -> x.loadImbalance), lMax = max(population, x -> x.loadImbalance);

        double mn = normalize(o.makespan, mMin, mMax);
        double en = normalize(o.energyKWh, eMin, eMax);
        double ln = normalize(o.loadImbalance, lMin, lMax);
        return (mn + en + ln) / 3.0;
    }

    public static Objectives evaluate(int[] assignment, List<WorkflowTask> tasks,
                                      double[] vmMips, double[] vmPowerWatts) {
        Map<Integer, Double> finish = new HashMap<>();
        double[] vmAvailable = new double[vmMips.length];
        double[] activeTime = new double[vmMips.length];

        // Task list is expected to be topologically ordered.
        for (WorkflowTask task : tasks) {
            int vm = assignment[task.getId()];
            double parentReady = 0;
            for (int parent : task.getParents()) {
                parentReady = Math.max(parentReady, finish.getOrDefault(parent, 0.0));
            }
            double start = Math.max(vmAvailable[vm], parentReady);
            double execution = task.getLength() / vmMips[vm];
            double end = start + execution;
            finish.put(task.getId(), end);
            vmAvailable[vm] = end;
            activeTime[vm] += execution;
        }

        double makespan = finish.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);

        double totalEnergyWh = 0;
        for (int i = 0; i < vmMips.length; i++) {
            double activePower = vmPowerWatts[i];
            double idlePower = activePower * InfrastructureFactory.IDLE_POWER_FACTOR;
            double idleTime = Math.max(0, makespan - activeTime[i]);
            totalEnergyWh += (activeTime[i] * activePower + idleTime * idlePower) / 3600.0;
        }
        double energyKWh = totalEnergyWh / 1000.0;

        // Load imbalance: std-dev of per-VM active time (TL_i / C_i) — matches
        // Malik's Eq. 3-5 and the corrected MetricsCalculator.ImbalanceIndex.
        double meanActive = 0;
        for (double t : activeTime) meanActive += t;
        meanActive /= vmMips.length;

        double variance = 0;
        for (double t : activeTime) variance += Math.pow(t - meanActive, 2);
        variance /= vmMips.length;

        return new Objectives(makespan, energyKWh, Math.sqrt(variance));
    }

    private interface Value { double get(Objectives o); }
    private static double min(List<Objectives> p, Value v) { return p.stream().mapToDouble(v::get).min().orElse(0); }
    private static double max(List<Objectives> p, Value v) { return p.stream().mapToDouble(v::get).max().orElse(0); }
    private static double normalize(double x, double min, double max) {
        return max - min < 1e-12 ? 0 : (x - min) / (max - min);
    }
}
