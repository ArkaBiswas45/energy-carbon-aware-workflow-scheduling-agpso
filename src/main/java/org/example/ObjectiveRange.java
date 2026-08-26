package org.example;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Sliding-window min/max bounds per objective, pooled over the last
 * WINDOW_ITERATIONS iterations of a PSO run.
 *
 * HISTORY (2025-08-26): v1 of this class used never-shrinking, whole-run
 * bounds. That fixed the original problem -- per-iteration population
 * bounds shrinking as the swarm converges, which inflated small
 * differences late in the run -- but introduced a worse one: an outlier
 * objective value from early random initialization (or from a
 * stagnation-triggered reinit, which deliberately reseeds particles to
 * random positions) permanently widens the range for the rest of the run
 * and never fades. Once the range is dominated by a few early bad-makespan
 * outliers, later genuinely meaningful differences between candidate
 * solutions get compressed toward zero and the search loses selection
 * pressure on that objective entirely. That's consistent with the
 * 2025-08-26 rerun: makespan more than doubled (14.05s -> ~29-32s across
 * all 5 seeds), SLA violations jumped to 40-53%, and carbon got WORSE
 * (0.736g -> ~0.82g) even though tasks were nominally drifting toward
 * cleaner VMs -- the poorly-packed, stretched-out schedules that resulted
 * left every VM (including the dirty fast ones) idle for much longer,
 * and idle-power emissions across a longer makespan outweighed the
 * per-task carbon savings.
 *
 * FIX: pool bounds over a rolling window of recent iterations instead of
 * the whole run. This keeps normalization stable across nearby iterations
 * (so pBest/gBest comparisons within the window are apples-to-apples,
 * fixing the original shrink problem) while letting old outliers age out
 * of the window instead of poisoning the rest of the run.
 */
public class ObjectiveRange {
    // Roughly one stagnation-reinit cycle (STAGNATION_LIMIT in
    // AdaptiveGreenPSOScenario) -- long enough for comparisons to stay
    // meaningful iteration-to-iteration, short enough that a reinit's
    // random outliers don't linger indefinitely.
    private static final int WINDOW_ITERATIONS = 20;

    private final Deque<List<ObjectiveVector>> history = new ArrayDeque<>();
    private double makespanMin, makespanMax;
    private double energyMin, energyMax;
    private double carbonMin, carbonMax;
    private double slaMin, slaMax;
    private double loadMin, loadMax;

    /** Push this iteration's population into the window and recompute bounds. */
    public void expand(List<ObjectiveVector> population) {
        history.addLast(population);
        while (history.size() > WINDOW_ITERATIONS) history.removeFirst();
        recomputeBounds();
    }

    private void recomputeBounds() {
        makespanMin = energyMin = carbonMin = slaMin = loadMin = Double.POSITIVE_INFINITY;
        makespanMax = energyMax = carbonMax = slaMax = loadMax = Double.NEGATIVE_INFINITY;
        for (List<ObjectiveVector> population : history) {
            for (ObjectiveVector v : population) {
                makespanMin = Math.min(makespanMin, v.makespan);
                makespanMax = Math.max(makespanMax, v.makespan);
                energyMin = Math.min(energyMin, v.energyKWh);
                energyMax = Math.max(energyMax, v.energyKWh);
                carbonMin = Math.min(carbonMin, v.carbonGrams);
                carbonMax = Math.max(carbonMax, v.carbonGrams);
                slaMin = Math.min(slaMin, v.slaViolationRatio);
                slaMax = Math.max(slaMax, v.slaViolationRatio);
                loadMin = Math.min(loadMin, v.loadImbalance);
                loadMax = Math.max(loadMax, v.loadImbalance);
            }
        }
    }

    double normalizeMakespan(double v) { return norm(v, makespanMin, makespanMax); }
    double normalizeEnergy(double v) { return norm(v, energyMin, energyMax); }
    double normalizeCarbon(double v) { return norm(v, carbonMin, carbonMax); }
    double normalizeSla(double v) { return norm(v, slaMin, slaMax); }
    double normalizeLoad(double v) { return norm(v, loadMin, loadMax); }

    private static double norm(double v, double min, double max) {
        if (max - min < 1e-12) return 0.0;
        return (v - min) / (max - min);
    }
}
