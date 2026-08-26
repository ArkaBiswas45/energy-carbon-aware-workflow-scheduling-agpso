package org.example;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.io.FileWriter;
import java.util.*;

/**
 * SCENARIO 6 – Particle Swarm Optimization (PSO) Scheduler
 *
 * Bio-inspired metaheuristic. Each particle encodes a complete
 * task-to-VM assignment. The swarm evolves over 150 iterations
 * toward the assignment with the best multi-objective fitness.
 *
 * Fitness (minimise):
 *   f = estimated_makespan + SLA_PENALTY × sla_violation_count
 *
 * PSO update rule (discrete adaptation):
 *   v(t+1) = W·v(t) + C1·r1·(pBest - x) + C2·r2·(gBest - x)
 *   x(t+1) = clamp( round(x(t) + v(t+1)), [0, nVMs-1] )
 *
 * References:
 *   Kennedy & Eberhart (1995); Braun et al. (2001) Min-Min/PSO comparison.
 */
public class PSOScenario {

    // ── Hyper-parameters ────────────────────────────────────────────────
    private static final int    PARTICLES    = 30;
    private static final int    ITERATIONS   = 150;
    private static final double W            = 0.729;  // inertia weight (constriction)
    private static final double C1           = 1.494;  // cognitive coefficient
    private static final double C2           = 1.494;  // social coefficient
    private static final double SLA_PENALTY  = 10.0;   // penalty per SLA-violated task

    private final Random rng = new Random(42); // fixed seed = reproducible results

    public void run() {

        System.out.println("\n" + "=".repeat(65));
        System.out.println("  SCENARIO 6 — PSO (Particle Swarm Optimization)");
        System.out.println("=".repeat(65));

        // ── Setup ───────────────────────────────────────────────────────
        List<CloudletSimple> cloudlets = InfrastructureFactory.createCloudlets();
        List<VmSimple>       vms       = InfrastructureFactory.createVMs();

        int      nTasks      = cloudlets.size();
        int      nVms        = vms.size();
        long[]   taskLengths = cloudlets.stream().mapToLong(CloudletSimple::getLength).toArray();
        double[] vmMips      = vms.stream().mapToDouble(VmSimple::getMips).toArray();
        int[]    priorities  = cloudlets.stream()
                .mapToInt(CloudletSimple::getNetServiceLevel)
                .toArray();

        System.out.printf("%n  Swarm size: %d particles  |  Iterations: %d%n",
                PARTICLES, ITERATIONS);
        System.out.printf("  Fitness = makespan + %.0f × SLA_violations%n%n", SLA_PENALTY);

        // ── Initialise swarm ─────────────────────────────────────────────
        int[][]    pos      = new int[PARTICLES][nTasks];
        double[][] vel      = new double[PARTICLES][nTasks];
        int[][]    pBest    = new int[PARTICLES][nTasks];
        double[]   pBestFit = new double[PARTICLES];
        int[]      gBest    = null;
        double     gBestFit = Double.MAX_VALUE;

        for (int p = 0; p < PARTICLES; p++) {
            for (int t = 0; t < nTasks; t++) {
                pos[p][t] = rng.nextInt(nVms);
                vel[p][t] = (rng.nextDouble() - 0.5) * nVms;
            }
            pBest[p]    = pos[p].clone();
            pBestFit[p] = fitness(pos[p], taskLengths, vmMips, priorities);
            if (pBestFit[p] < gBestFit) {
                gBestFit = pBestFit[p];
                gBest    = pBest[p].clone();
            }
        }

        // ── Convergence tracking ─────────────────────────────────────────
        List<Double> convergence = new ArrayList<>();
        convergence.add(gBestFit);

        // ── Main PSO loop ─────────────────────────────────────────────────
        for (int iter = 1; iter < ITERATIONS; iter++) {
            for (int p = 0; p < PARTICLES; p++) {
                for (int t = 0; t < nTasks; t++) {
                    double r1 = rng.nextDouble(), r2 = rng.nextDouble();
                    vel[p][t] = W  * vel[p][t]
                            + C1 * r1 * (pBest[p][t] - pos[p][t])
                            + C2 * r2 * (gBest[t]    - pos[p][t]);
                    int np = (int) Math.round(pos[p][t] + vel[p][t]);
                    pos[p][t] = Math.max(0, Math.min(nVms - 1, np));
                }
                double f = fitness(pos[p], taskLengths, vmMips, priorities);
                if (f < pBestFit[p]) {
                    pBestFit[p] = f;
                    pBest[p]    = pos[p].clone();
                    if (f < gBestFit) {
                        gBestFit = f;
                        gBest    = pBest[p].clone();
                    }
                }
            }
            convergence.add(gBestFit);

            // Progress log every 30 iterations
            if (iter % 30 == 0)
                System.out.printf("    Iter %3d → best fitness: %.4f%n", iter, gBestFit);
        }

        System.out.printf("%n  ✔ PSO converged. Best fitness: %.4f%n%n", gBestFit);

        // ── Apply best assignment ─────────────────────────────────────────
        System.out.println("  Task assignment (PSO-optimized):");
        for (int t = 0; t < nTasks; t++) {
            VmSimple vm = vms.get(gBest[t]);
            cloudlets.get(t).setVm(vm);
            System.out.printf("    Task %2d [MI=%5d, Pri=%s] → VM-%d (%4.0f MIPS)%n",
                    t, cloudlets.get(t).getLength(),
                    priorityLabel(priorities[t]), vm.getId(), vm.getMips());
        }

        // ── Save convergence CSV ──────────────────────────────────────────
        try (FileWriter fw = new FileWriter("pso_convergence.csv", false)) {
            fw.write("Iteration,BestFitness\n");
            for (int i = 0; i < convergence.size(); i++)
                fw.write(String.format(Locale.US, "%d,%.6f%n", i, convergence.get(i)));
            System.out.println("\n  ✔ Convergence data saved to pso_convergence.csv");
        } catch (Exception e) {
            System.err.println("  ✘ Could not save convergence CSV: " + e.getMessage());
        }

        // ── CloudSim simulation ───────────────────────────────────────────
        CloudSim sim = new CloudSim();
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(sim);
        new DatacenterSimple(sim, InfrastructureFactory.createHosts());
        broker.submitVmList(vms);

        Workflow workflow = WorkflowUtil.standardWorkflow(cloudlets);
        WorkflowUtil.applyDependencyDelays(cloudlets, vms, workflow.topologicalOrder(), vmMips);

        broker.submitCloudletList(cloudlets);
        sim.start();

        MetricsCalculator.Result result = MetricsCalculator.compute(
                "PSO",
                broker.getCloudletFinishedList(),
                new ArrayList<>(vms),
                sim);

        MetricsCalculator.printAndSave(result,
                broker.getCloudletFinishedList(),
                new ArrayList<>(vms));
    }

    /**
     * Multi-objective fitness function.
     * Estimates makespan and SLA violations WITHOUT running CloudSim
     * (pure arithmetic — runs thousands of times per second).
     */
    private double fitness(int[] assignment,
                           long[]   taskLengths,
                           double[] vmMips,
                           int[]    priorities) {
        double[] vmTime = new double[vmMips.length];
        int slaViol = 0;

        for (int t = 0; t < taskLengths.length; t++) {
            vmTime[assignment[t]] += (double) taskLengths[t] / vmMips[assignment[t]];
            double deadline = InfrastructureFactory.SLA_DEADLINE[
                    Math.min(priorities[t], InfrastructureFactory.SLA_DEADLINE.length - 1)];
            if (vmTime[assignment[t]] > deadline) slaViol++;
        }

        double makespan = Arrays.stream(vmTime).max().getAsDouble();
        return makespan + SLA_PENALTY * slaViol;
    }

    private static String priorityLabel(int p) {
        return switch (p) { case 0 -> "HIGH  "; case 1 -> "MEDIUM"; default -> "LOW   "; };
    }
}