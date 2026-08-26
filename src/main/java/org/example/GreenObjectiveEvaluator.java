package org.example;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Evaluates Adaptive Green PSO objectives without running CloudSim. */
public class GreenObjectiveEvaluator {
    private final CarbonModel carbonModel;

    public GreenObjectiveEvaluator(CarbonModel carbonModel) {
        this.carbonModel = carbonModel;
    }

    public ObjectiveVector evaluate(int[] assignment, List<WorkflowTask> tasks, double[] vmMips,
                                    double[] vmPowerWatts) {
        Map<Integer, Double> finish = new HashMap<>();
        double[] vmAvailable = new double[vmMips.length];
        double[] activeTime = new double[vmMips.length];
        int[] taskCount = new int[vmMips.length];
        int slaViolations = 0;

        for (WorkflowTask task : tasks) {
            int vm = assignment[task.getId()];
            double parentReady = 0.0;
            for (int parent : task.getParents()) {
                parentReady = Math.max(parentReady, finish.getOrDefault(parent, 0.0));
            }

            double start = Math.max(vmAvailable[vm], parentReady);
            double execution = task.getLength() / vmMips[vm];
            double end = start + execution;
            finish.put(task.getId(), end);
            vmAvailable[vm] = end;
            activeTime[vm] += execution;
            taskCount[vm]++;

            double deadline = InfrastructureFactory.SLA_DEADLINE[
                    Math.min(task.getPriority(), InfrastructureFactory.SLA_DEADLINE.length - 1)];
            if (end > deadline) slaViolations++;
        }

        double makespan = finish.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double energyKWh = 0.0;
        double carbonGrams = 0.0;
        for (int i = 0; i < vmMips.length; i++) {
            double activePower = powerForVm(vmPowerWatts, i);
            double idlePower = activePower * InfrastructureFactory.IDLE_POWER_FACTOR;
            double idleTime = Math.max(0.0, makespan - activeTime[i]);
            double vmEnergyKWh = ((activeTime[i] * activePower + idleTime * idlePower) / 3600.0) / 1000.0;
            energyKWh += vmEnergyKWh;
            carbonGrams += vmEnergyKWh * carbonModel.intensityForVm(i);
        }

        double meanActiveTime = 0.0;
        for (double time : activeTime) meanActiveTime += time;
        meanActiveTime /= Math.max(1, activeTime.length);

        double variance = 0.0;
        for (double time : activeTime) variance += Math.pow(time - meanActiveTime, 2);
        variance /= Math.max(1, activeTime.length);

        return new ObjectiveVector(
                makespan,
                energyKWh,
                carbonGrams,
                (double) slaViolations / Math.max(1, tasks.size()),
                Math.sqrt(variance)
        );
    }

    private static double powerForVm(double[] powers, int index) {
        if (index < powers.length) return powers[index];
        return InfrastructureFactory.SCALED_VM_POWER_WATTS;
    }
}
