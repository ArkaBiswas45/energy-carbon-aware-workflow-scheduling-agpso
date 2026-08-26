package org.example;

import java.util.List;

/** Min-max normalized weighted fitness for objectives that are all minimized. */
public class NormalizedFitness {
    public static double compute(ObjectiveVector objective, List<ObjectiveVector> population,
                                 ObjectiveWeights weights) {
        return weights.makespan * normalize(objective.makespan, population, v -> v.makespan)
                + weights.energy * normalize(objective.energyKWh, population, v -> v.energyKWh)
                + weights.carbon * normalize(objective.carbonGrams, population, v -> v.carbonGrams)
                + weights.sla * normalize(objective.slaViolationRatio, population, v -> v.slaViolationRatio)
                + weights.loadBalance * normalize(objective.loadImbalance, population, v -> v.loadImbalance);
    }

    /**
     * Same weighted-sum fitness, but normalized against a running
     * {@link ObjectiveRange} accumulated across the whole PSO run instead
     * of the current iteration's population. Use this inside the PSO loop
     * where pBest/gBest comparisons span iterations -- see ObjectiveRange
     * for why the population-relative version above is unsafe there.
     */
    public static double compute(ObjectiveVector objective, ObjectiveRange range, ObjectiveWeights weights) {
        return weights.makespan * range.normalizeMakespan(objective.makespan)
                + weights.energy * range.normalizeEnergy(objective.energyKWh)
                + weights.carbon * range.normalizeCarbon(objective.carbonGrams)
                + weights.sla * range.normalizeSla(objective.slaViolationRatio)
                + weights.loadBalance * range.normalizeLoad(objective.loadImbalance);
    }

    private interface Value {
        double get(ObjectiveVector objective);
    }

    private static double normalize(double value, List<ObjectiveVector> population, Value accessor) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (ObjectiveVector item : population) {
            double v = accessor.get(item);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (max - min < 1e-12) return 0.0;
        return (value - min) / (max - min);
    }
}
