package org.example;

/**
 * Updates objective weights according to measured scheduling pressure.
 *
 * The policy starts from fixed baseline weights and increases the objectives
 * whose targets are currently under pressure, then normalizes the result.
 */
public class AdaptiveWeightPolicy {
    /*
     * Targets are calibrated against this project's own observed baseline
     * range (see comparison_report.html across Static/RoundRobin/WeightedRR/
     * Adaptive/MinMin/PSO/Paper-Based PSO/FaultTolerant), not arbitrary
     * round numbers. Each target is set near the best value already
     * achieved by a non-green baseline on this workload, so "pressure > 1"
     * means "worse than what is already demonstrably achievable here" and
     * a run near the achievable best correctly reads as low-pressure.
     *
     * Previous constants (0.10 SLA ratio, 0.00003 kWh/task, 1.50 load
     * imbalance) were far below anything ever observed on this workload
     * (actual SLA ratios ran 33-100%, energy/task ~0.000085-0.000127 kWh,
     * load imbalance 1.76-16.0). That made slaPressure and energyPressure
     * permanently 3-6x on every run, which dominates the weight
     * normalization and crowds out the carbon and load-balance shares
     * regardless of their own pressure — see AdaptiveWeightPolicy issue
     * log. These targets fix that by reflecting real achievable values.
     */
    private static final double TARGET_SLA_RATIO = 0.33;             // best observed: PSO/Adaptive, 5/15 tasks
    private static final double TARGET_ENERGY_PER_TASK_KWH = 0.00009; // best observed: Paper-Based PSO, ~0.000085
    private static final double TARGET_LOAD_IMBALANCE = 5.0;          // achievable mid-point for workflow-aware scheduling

    private static final double GAMMA_MAKESPAN = 0.40;
    private static final double GAMMA_ENERGY = 0.40;
    // Raised to match GAMMA_SLA: carbon-awareness is this algorithm's
    // primary contribution over the paper baselines, so it should respond
    // to pressure at least as strongly as SLA does, not less strongly.
    private static final double GAMMA_CARBON = 0.60;
    private static final double GAMMA_SLA = 0.60;
    private static final double GAMMA_LOAD = 0.30;

    private final CarbonModel carbonModel;

    public AdaptiveWeightPolicy(CarbonModel carbonModel) {
        this.carbonModel = carbonModel;
    }

    public ObjectiveWeights update(ObjectiveVector observedBest, int taskCount) {
        ObjectiveWeights base = ObjectiveWeights.baseline();
        double energyPerTask = observedBest.energyKWh / Math.max(1, taskCount);
        double effectiveCarbonIntensity = observedBest.energyKWh <= 1e-12
                ? carbonModel.averageIntensity()
                : observedBest.carbonGrams / observedBest.energyKWh;

        double slaPressure = pressure(observedBest.slaViolationRatio, TARGET_SLA_RATIO);
        double energyPressure = pressure(energyPerTask, TARGET_ENERGY_PER_TASK_KWH);
        double carbonPressure = pressure(effectiveCarbonIntensity, carbonModel.averageIntensity());
        double loadPressure = pressure(observedBest.loadImbalance, TARGET_LOAD_IMBALANCE);

        double makespan = base.makespan * (1.0 + GAMMA_MAKESPAN * Math.max(0, slaPressure - 1.0));
        double energy = base.energy * (1.0 + GAMMA_ENERGY * Math.max(0, energyPressure - 1.0));
        double carbon = base.carbon * (1.0 + GAMMA_CARBON * Math.max(0, carbonPressure - 1.0));
        double sla = base.sla * (1.0 + GAMMA_SLA * Math.max(0, slaPressure - 1.0));
        double load = base.loadBalance * (1.0 + GAMMA_LOAD * Math.max(0, loadPressure - 1.0));

        return new ObjectiveWeights(makespan, energy, carbon, sla, load);
    }

    private static double pressure(double observed, double target) {
        return observed / Math.max(target, 1e-12);
    }
}
