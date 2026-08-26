package org.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Carbon-aware local search applied to AGPSO's best PSO solution.
 *
 * WHY THIS EXISTS: watts-per-MIPS rises as VMs get slower, but carbon
 * intensity falls even faster on this infrastructure, so carbon cost per
 * unit of work is lowest on the slowest VM (VM-4) and highest on the
 * fastest (VM-0) -- the reverse of what makespan/SLA pressure pushes
 * toward. A single weighted-sum PSO fitness can't represent both
 * "prefer slow/clean VMs for carbon" and "prefer fast VMs for deadlines"
 * at once without one side dominating, and with SLA in the objective set,
 * makespan/SLA pressure always wins that fight. So this runs as a
 * deterministic post-pass instead of trying to fix it inside the blended
 * fitness.
 *
 * v1 (single-task moves, first-improvement, gated by "no worse than PSO's
 * own already-tight makespan/violations") found ~zero moves: AGPSO's own
 * solution runs with essentially no slack, so almost every candidate move
 * tripped the zero-regression gate before it could matter (2025-08-26 log:
 * 0.81863g -> 0.81863g).
 *
 * v2 (full-objective scoring + steepest-descent + swaps, budget loosened to
 * match the Paper-Based PSO baseline's own makespan/violations) reached
 * ~0.80g in isolation but spent nearly the whole budget in the real run:
 * SLA violations jumped 7->11 (giving away almost all of AGPSO's SLA
 * advantage) for only ~1.7% carbon improvement. Root cause: a greedy
 * single-incumbent search has no cost-benefit sense -- given a budget it
 * fully spends it even once marginal carbon-per-violation has collapsed.
 *
 * v3 (fixed +1 violation / +2.0s slack over PSO's own result, hard-capped
 * by the paper baseline) fixed the overspending but is a single hand-picked
 * constant -- it doesn't adapt to how much genuine carbon-reducing slack a
 * given PSO run's solution actually has, and being a single-incumbent
 * hill-climb, it can only walk downhill from one starting point: it can
 * miss any move that only pays off as a 3+ task recombination.
 *
 * v4 (this version) replaces the single incumbent with a genuine
 * multi-objective search (NSGA-II-lite: non-dominated sorting + crowding
 * distance + crossover + mutation over a population of assignments,
 * scored on (carbon, SLA violations, makespan)). It still uses the SAME
 * hard safety cap as v2/v3 (never allowed past the Paper-Based PSO
 * baseline's own makespan/violation numbers) as a feasibility filter, but
 * instead of hill-climbing to the edge of that cap, it builds the full
 * feasible Pareto archive and then picks a KNEE point: it walks outward
 * from PSO's own violation count only while the marginal carbon gain per
 * extra violation is still at least MIN_MARGINAL_GAIN_RATIO of the first
 * marginal gain, then stops. This generalizes v3's fixed "+1" guess into
 * something derived from each run's actual diminishing-returns curve,
 * while keeping v2's mistake (buying violations past the point they're
 * worth it) structurally impossible rather than just tuned around.
 *
 * Validated numerically in a Python port (pure-Java-equivalent, no CloudSim
 * dependency) across 5 PSO seeds before this file was written -- see the
 * project handoff notes for that validation. As always: re-validate in the
 * Python harness before changing MIN_MARGINAL_GAIN_RATIO or the GA
 * parameters below, don't hand-tune this file directly against a single
 * real run.
 */
public class CarbonRefinement {

    private static final int POPULATION_SIZE = 50;
    private static final int GENERATIONS = 180;
    private static final double MUTATION_RATE = 0.1;
    // How much of the FIRST marginal carbon gain (per extra SLA violation
    // beyond PSO's own count) a later gain must still be worth, to keep
    // "spending" violation budget. Lower = more willing to trade SLA for
    // carbon; higher = closer to v3's conservative behavior. 0.3 was
    // validated as a reasonable middle point across 5 seeds in the Python
    // harness -- see handoff notes before changing it.
    private static final double MIN_MARGINAL_GAIN_RATIO = 0.3;

    public static int[] refine(int[] assignment, List<WorkflowTask> order, double[] vmMips,
                                double[] vmPowerWatts, GreenObjectiveEvaluator evaluator,
                                double makespanCeiling, int violationCeiling, long rngSeed) {
        int nTasks = order.size();
        int nVms = vmMips.length;
        Random rng = new Random(rngSeed);

        ObjectiveVector incumbentObj = evaluator.evaluate(assignment, order, vmMips, vmPowerWatts);
        int psoViolations = countViolations(incumbentObj, nTasks);

        // Feasible archive: assignment (as a List<Integer> key) -> objectives.
        Map<List<Integer>, ObjectiveVector> archive = new HashMap<>();
        recordIfFeasible(assignment, order, vmMips, vmPowerWatts, evaluator,
                makespanCeiling, violationCeiling, archive);

        List<int[]> population = new ArrayList<>(POPULATION_SIZE);
        population.add(assignment.clone());
        for (int i = 1; i < POPULATION_SIZE; i++) {
            population.add(mutate(assignment, nVms, rng, 0.05 + rng.nextDouble() * 0.35));
        }

        for (int[] individual : population) {
            recordIfFeasible(individual, order, vmMips, vmPowerWatts, evaluator,
                    makespanCeiling, violationCeiling, archive);
        }

        for (int gen = 0; gen < GENERATIONS; gen++) {
            int popSize = population.size();
            ObjectiveVector[] objs = new ObjectiveVector[popSize];
            for (int i = 0; i < popSize; i++) {
                objs[i] = evaluator.evaluate(population.get(i), order, vmMips, vmPowerWatts);
                recordIfFeasible(population.get(i), order, vmMips, vmPowerWatts, evaluator,
                        makespanCeiling, violationCeiling, archive);
            }

            List<Integer> front = nonDominatedFront(objs);
            List<int[]> parents = new ArrayList<>();
            if (front.size() >= 4) {
                double[] crowd = crowdingDistance(front, objs);
                Integer[] frontArr = front.toArray(new Integer[0]);
                java.util.Arrays.sort(frontArr, (a, b) -> Double.compare(crowd[front.indexOf(b)], crowd[front.indexOf(a)]));
                for (int idx : frontArr) parents.add(population.get(idx));
            } else {
                // Early generations: not enough non-dominated individuals yet.
                // Fall back to the best-carbon half of the population.
                Integer[] byCarbon = new Integer[popSize];
                for (int i = 0; i < popSize; i++) byCarbon[i] = i;
                java.util.Arrays.sort(byCarbon, (a, b) -> Double.compare(objs[a].carbonGrams, objs[b].carbonGrams));
                for (int i = 0; i < Math.max(4, popSize / 2); i++) parents.add(population.get(byCarbon[i]));
            }

            List<int[]> children = new ArrayList<>();
            while (children.size() < POPULATION_SIZE) {
                int[] p1 = parents.get(rng.nextInt(parents.size()));
                int[] p2 = parents.get(rng.nextInt(parents.size()));
                int point = 1 + rng.nextInt(nTasks - 1);
                int[] c1 = crossover(p1, p2, point);
                int[] c2 = crossover(p2, p1, point);
                c1 = mutate(c1, nVms, rng, MUTATION_RATE);
                c2 = rng.nextBoolean() ? swapMutate(c2, rng) : mutate(c2, nVms, rng, MUTATION_RATE);
                children.add(c1);
                if (children.size() < POPULATION_SIZE) children.add(c2);
            }

            int keepParents = Math.min(parents.size(), POPULATION_SIZE / 3);
            List<int[]> nextGen = new ArrayList<>(POPULATION_SIZE);
            for (int i = 0; i < keepParents; i++) nextGen.add(parents.get(i));
            for (int i = 0; nextGen.size() < POPULATION_SIZE && i < children.size(); i++) {
                nextGen.add(children.get(i));
            }
            population = nextGen;
        }

        int[] knee = selectKnee(archive, psoViolations);
        return knee != null ? knee : assignment.clone();
    }

    // ---- knee-point selection over the feasible archive ----

    private static int[] selectKnee(Map<List<Integer>, ObjectiveVector> archive, int psoViolations) {
        // Best (lowest-carbon) assignment found at each "extra violations
        // beyond PSO's own count" level.
        Map<Integer, int[]> bestAssignmentAtLevel = new HashMap<>();
        Map<Integer, Double> bestCarbonAtLevel = new HashMap<>();

        for (Map.Entry<List<Integer>, ObjectiveVector> e : archive.entrySet()) {
            ObjectiveVector obj = e.getValue();
            int violations = (int) Math.round(obj.slaViolationRatio * totalTasksOf(e.getKey()));
            int extra = violations - psoViolations;
            if (extra < 0) continue;
            Double currentBest = bestCarbonAtLevel.get(extra);
            if (currentBest == null || obj.carbonGrams < currentBest) {
                bestCarbonAtLevel.put(extra, obj.carbonGrams);
                bestAssignmentAtLevel.put(extra, toArray(e.getKey()));
            }
        }

        if (!bestCarbonAtLevel.containsKey(0)) return null;

        List<Integer> levels = new ArrayList<>(bestCarbonAtLevel.keySet());
        java.util.Collections.sort(levels);

        int[] chosen = bestAssignmentAtLevel.get(0);
        double prevCarbon = bestCarbonAtLevel.get(0);
        Double firstGain = null;

        for (int lvl : levels) {
            if (lvl == 0) continue;
            double carbon = bestCarbonAtLevel.get(lvl);
            double gain = prevCarbon - carbon;
            if (firstGain == null) {
                firstGain = gain;
                if (gain > 1e-9) {
                    chosen = bestAssignmentAtLevel.get(lvl);
                    prevCarbon = carbon;
                }
                continue;
            }
            if (firstGain > 1e-9 && gain >= MIN_MARGINAL_GAIN_RATIO * firstGain) {
                chosen = bestAssignmentAtLevel.get(lvl);
                prevCarbon = carbon;
            } else {
                break;
            }
        }
        return chosen;
    }

    // ---- GA operators ----

    private static int[] crossover(int[] a, int[] b, int point) {
        int[] child = new int[a.length];
        System.arraycopy(a, 0, child, 0, point);
        System.arraycopy(b, point, child, point, a.length - point);
        return child;
    }

    private static int[] mutate(int[] assignment, int nVms, Random rng, double rate) {
        int[] child = assignment.clone();
        for (int i = 0; i < child.length; i++) {
            if (rng.nextDouble() < rate) child[i] = rng.nextInt(nVms);
        }
        return child;
    }

    private static int[] swapMutate(int[] assignment, Random rng) {
        int[] child = assignment.clone();
        int i = rng.nextInt(child.length);
        int j = rng.nextInt(child.length);
        int tmp = child[i];
        child[i] = child[j];
        child[j] = tmp;
        return child;
    }

    // ---- NSGA-II bookkeeping ----

    private static boolean dominates(ObjectiveVector a, ObjectiveVector b) {
        boolean le = a.carbonGrams <= b.carbonGrams
                && a.slaViolationRatio <= b.slaViolationRatio
                && a.makespan <= b.makespan;
        boolean lt = a.carbonGrams < b.carbonGrams
                || a.slaViolationRatio < b.slaViolationRatio
                || a.makespan < b.makespan;
        return le && lt;
    }

    private static List<Integer> nonDominatedFront(ObjectiveVector[] objs) {
        List<Integer> front = new ArrayList<>();
        for (int i = 0; i < objs.length; i++) {
            boolean dominated = false;
            for (int j = 0; j < objs.length; j++) {
                if (i != j && dominates(objs[j], objs[i])) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) front.add(i);
        }
        return front;
    }

    private static double[] crowdingDistance(List<Integer> front, ObjectiveVector[] objs) {
        double[] dist = new double[objs.length];
        crowdOneKey(front, objs, dist, 0);
        crowdOneKey(front, objs, dist, 1);
        crowdOneKey(front, objs, dist, 2);
        return dist;
    }

    private static void crowdOneKey(List<Integer> front, ObjectiveVector[] objs, double[] dist, int key) {
        Integer[] sorted = front.toArray(new Integer[0]);
        java.util.Arrays.sort(sorted, (a, b) -> Double.compare(valueOf(objs[a], key), valueOf(objs[b], key)));
        if (sorted.length == 0) return;
        dist[sorted[0]] = Double.POSITIVE_INFINITY;
        dist[sorted[sorted.length - 1]] = Double.POSITIVE_INFINITY;
        double vmin = valueOf(objs[sorted[0]], key);
        double vmax = valueOf(objs[sorted[sorted.length - 1]], key);
        double span = (vmax - vmin) == 0 ? 1.0 : (vmax - vmin);
        for (int k = 1; k < sorted.length - 1; k++) {
            double next = valueOf(objs[sorted[k + 1]], key);
            double prev = valueOf(objs[sorted[k - 1]], key);
            if (dist[sorted[k]] != Double.POSITIVE_INFINITY) {
                dist[sorted[k]] += (next - prev) / span;
            }
        }
    }

    private static double valueOf(ObjectiveVector obj, int key) {
        switch (key) {
            case 0: return obj.carbonGrams;
            case 1: return obj.slaViolationRatio;
            default: return obj.makespan;
        }
    }

    // ---- feasibility + archive helpers ----

    private static void recordIfFeasible(int[] assignment, List<WorkflowTask> order, double[] vmMips,
                                          double[] vmPowerWatts, GreenObjectiveEvaluator evaluator,
                                          double makespanCeiling, int violationCeiling,
                                          Map<List<Integer>, ObjectiveVector> archive) {
        if (!feasible(assignment, order, vmMips, makespanCeiling, violationCeiling)) return;
        List<Integer> key = toKey(assignment);
        if (archive.containsKey(key)) return;
        archive.put(key, evaluator.evaluate(assignment, order, vmMips, vmPowerWatts));
    }

    private static boolean feasible(int[] assignment, List<WorkflowTask> order, double[] vmMips,
                                     double makespanCeiling, int violationCeiling) {
        Map<Integer, Double> finish = new HashMap<>();
        double[] vmAvailable = new double[vmMips.length];
        int violations = 0;

        for (WorkflowTask task : order) {
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

            double deadline = InfrastructureFactory.SLA_DEADLINE[
                    Math.min(task.getPriority(), InfrastructureFactory.SLA_DEADLINE.length - 1)];
            if (end > deadline) violations++;
            if (violations > violationCeiling) return false;
        }
        double makespan = finish.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        return makespan <= makespanCeiling + 1e-9;
    }

    private static int countViolations(ObjectiveVector obj, int nTasks) {
        return (int) Math.round(obj.slaViolationRatio * nTasks);
    }

    private static List<Integer> toKey(int[] assignment) {
        List<Integer> key = new ArrayList<>(assignment.length);
        for (int v : assignment) key.add(v);
        return key;
    }

    private static int[] toArray(List<Integer> key) {
        int[] arr = new int[key.size()];
        for (int i = 0; i < key.size(); i++) arr[i] = key.get(i);
        return arr;
    }

    private static int totalTasksOf(List<Integer> key) {
        return key.size();
    }
}
