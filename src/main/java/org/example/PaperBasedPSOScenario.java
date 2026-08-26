package org.example;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.io.FileWriter;
import java.util.*;

/**
 * SCENARIO 7 — Paper-Based PSO.
 *
 * This is a paper-inspired baseline using workflow preprocessing (depth and
 * length thresholds) followed by PSO. Its optimized objectives are makespan,
 * energy and load imbalance. The existing PSO scenario remains unchanged.
 *
 * CARBON-METRIC FIX (this thread): this scenario was calling
 * MetricsCalculator.compute(...)'s 4-arg overload, which defaults to a flat
 * 475 gCO2/kWh for every VM when no CarbonModel is passed -- i.e. this
 * scenario's reported CO2 number was never actually weighted by which VM
 * ran the work, just energy * 475. AdaptiveGreenPSOScenario, by contrast,
 * passes its real per-VM mixed-intensity CarbonModel (700 down to
 * 200 gCO2/kWh), so the two scenarios' "CO2 Grams" columns in results.csv
 * were not measuring the same thing -- Paper-Based PSO's number looked
 * artificially low/flat regardless of its actual VM placement. Now uses the
 * SAME CarbonModel AGPSO uses, so the comparison is apples-to-apples. This
 * will change Paper-Based PSO's own reported CO2 number on re-run -- that's
 * the fix taking effect, not a bug.
 */
public class PaperBasedPSOScenario {
    private static final int PARTICLES = 30;
    private static final int ITERATIONS = 150;
    private static final double W = 0.729;
    private static final double C1 = 1.494;
    private static final double C2 = 1.494;

    private final Random rng = new Random(42);
    private final CarbonModel carbonModel = CarbonModel.mixedIntensityProfile();

    public void run() {
        System.out.println("\n" + "=".repeat(65));
        System.out.println("  SCENARIO 7 — PAPER-BASED PSO (WORKFLOW + GREEN OBJECTIVES)");
        System.out.println("=".repeat(65));

        List<CloudletSimple> cloudlets = InfrastructureFactory.createCloudlets();
        List<VmSimple> vms = InfrastructureFactory.createVMs();
        Workflow workflow = WorkflowUtil.standardWorkflow(cloudlets);
        WorkflowPreprocessor.Result prep = new WorkflowPreprocessor().process(workflow);

        System.out.printf("\n  Tasks: %d | VMs: %d | Particles: %d | Iterations: %d%n",
                cloudlets.size(), vms.size(), PARTICLES, ITERATIONS);
        System.out.printf("  Depth threshold: %.2f | Length threshold: %.2f MI%n",
                prep.depthThreshold, prep.lengthThreshold);
        System.out.printf("  Depth queue: %d | Length queue: %d | Critical queue: %d%n",
                prep.depthQueue.size(), prep.lengthQueue.size(), prep.criticalQueue.size());

        int nTasks = workflow.getTasks().size();
        int nVms = vms.size();
        double[] vmMips = vms.stream().mapToDouble(VmSimple::getMips).toArray();
        double[] vmPower = InfrastructureFactory.VM_POWER_WATTS.clone();

        int[][] pos = new int[PARTICLES][nTasks];
        double[][] vel = new double[PARTICLES][nTasks];
        int[][] pBest = new int[PARTICLES][nTasks];
        double[] pBestFit = new double[PARTICLES];
        int[] gBest = null;
        double gBestFit = Double.POSITIVE_INFINITY;
        List<Double> convergence = new ArrayList<>();

        for (int p = 0; p < PARTICLES; p++) {
            initializeParticle(pos[p], vel[p], prep, nVms);
            pBest[p] = pos[p].clone();
            pBestFit[p] = Double.POSITIVE_INFINITY;
        }

        for (int iter = 0; iter < ITERATIONS; iter++) {
            List<PaperPSOFitness.Objectives> objectives = new ArrayList<>(PARTICLES);
            for (int p = 0; p < PARTICLES; p++) {
                objectives.add(PaperPSOFitness.evaluate(pos[p], prep.topologicalOrder, vmMips, vmPower));
            }

            for (int p = 0; p < PARTICLES; p++) {
                double fit = PaperPSOFitness.fitness(objectives.get(p), objectives);
                if (fit < pBestFit[p]) {
                    pBestFit[p] = fit;
                    pBest[p] = pos[p].clone();
                }
            }

            gBest = bestParticle(pBest, pBestFit);
            gBestFit = bestValue(pBestFit);
            convergence.add(gBestFit);

            if (iter < ITERATIONS - 1) {
                for (int p = 0; p < PARTICLES; p++) {
                    for (int t = 0; t < nTasks; t++) {
                        double r1 = rng.nextDouble();
                        double r2 = rng.nextDouble();
                        vel[p][t] = W * vel[p][t]
                                + C1 * r1 * (pBest[p][t] - pos[p][t])
                                + C2 * r2 * (gBest[t] - pos[p][t]);
                        int next = (int) Math.round(pos[p][t] + vel[p][t]);
                        pos[p][t] = Math.max(0, Math.min(nVms - 1, next));
                    }
                }
            }

            if ((iter + 1) % 30 == 0)
                System.out.printf("    Iter %3d → best normalized fitness: %.6f%n", iter + 1, gBestFit);
        }

        // Apply the final best mapping to CloudSim cloudlets.
        for (int t = 0; t < nTasks; t++) cloudlets.get(t).setVm(vms.get(gBest[t]));

        // Enforce this DAG against the real CloudSim run too (see WorkflowUtil).
        WorkflowUtil.applyDependencyDelays(cloudlets, vms, prep.topologicalOrder, vmMips);

        PaperPSOFitness.Objectives finalObjectives =
                PaperPSOFitness.evaluate(gBest, prep.topologicalOrder, vmMips, vmPower);
        System.out.printf("\n  ✔ Paper-based PSO complete. Fitness: %.6f%n", gBestFit);
        System.out.printf("  Analytical makespan: %.4f s | energy: %.8f kWh | load imbalance: %.4f%n",
                finalObjectives.makespan, finalObjectives.energyKWh, finalObjectives.loadImbalance);

        saveConvergence(convergence);

        CloudSim sim = new CloudSim();
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(sim);
        new DatacenterSimple(sim, InfrastructureFactory.createHosts());
        broker.submitVmList(vms);
        broker.submitCloudletList(cloudlets);
        sim.start();

        MetricsCalculator.Result result = MetricsCalculator.compute(
                "Paper-Based PSO", broker.getCloudletFinishedList(), new ArrayList<>(vms), sim, carbonModel);
        MetricsCalculator.printAndSave(result, broker.getCloudletFinishedList(), new ArrayList<>(vms));
    }

    private void initializeParticle(int[] position, double[] velocity,
                                     WorkflowPreprocessor.Result prep, int nVms) {
        Arrays.fill(position, -1);
        // Seed critical tasks onto faster VMs; remaining tasks are random.
        for (WorkflowTask task : prep.criticalQueue) {
            position[task.getId()] = rng.nextInt(Math.min(2, nVms));
        }
        for (WorkflowTask task : prep.topologicalOrder) {
            if (position[task.getId()] < 0) position[task.getId()] = rng.nextInt(nVms);
            velocity[task.getId()] = (rng.nextDouble() - 0.5) * nVms;
        }
    }

    private static int[] bestParticle(int[][] particles, double[] fitness) {
        int best = 0;
        for (int i = 1; i < fitness.length; i++) if (fitness[i] < fitness[best]) best = i;
        return particles[best].clone();
    }

    private static double bestValue(double[] fitness) {
        return Arrays.stream(fitness).min().orElse(Double.POSITIVE_INFINITY);
    }

    private static void saveConvergence(List<Double> values) {
        try (FileWriter fw = new FileWriter("paper_pso_convergence.csv", false)) {
            fw.write("Iteration,BestFitness\n");
            for (int i = 0; i < values.size(); i++)
                fw.write(String.format(Locale.US, "%d,%.8f%n", i, values.get(i)));
            System.out.println("  ✔ Saved paper PSO convergence to paper_pso_convergence.csv");
        } catch (Exception e) {
            System.err.println("  ✘ Could not save paper PSO convergence CSV: " + e.getMessage());
        }
    }

}
