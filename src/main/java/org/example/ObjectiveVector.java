package org.example;

/** Analytical objective values for a task-to-VM assignment. */
public class ObjectiveVector {
    public final double makespan;
    public final double energyKWh;
    public final double carbonGrams;
    public final double slaViolationRatio;
    public final double loadImbalance;

    public ObjectiveVector(double makespan, double energyKWh, double carbonGrams,
                           double slaViolationRatio, double loadImbalance) {
        this.makespan = makespan;
        this.energyKWh = energyKWh;
        this.carbonGrams = carbonGrams;
        this.slaViolationRatio = slaViolationRatio;
        this.loadImbalance = loadImbalance;
    }
}
