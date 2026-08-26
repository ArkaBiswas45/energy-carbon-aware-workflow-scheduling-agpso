package org.example;

import java.io.FileWriter;


public class Main {

    public static void main(String[] args) {

        System.out.println("\n" + "=".repeat(65));
        System.out.println("   ADVANCED CLOUDSIM LOAD BALANCING SIMULATION");
        System.out.println("=".repeat(65));

        // Clear and write CSV header
        try (FileWriter fw = new FileWriter("results.csv", false)) {
            fw.write("Algorithm,AvgResponseTime,Makespan,Throughput,ImbalanceIndex," +
                    "SLAViolations,EnergyKWh,CostUSD,CO2Grams\n");

        } catch (Exception e) {
            System.err.println("Warning: could not reset results.csv – " + e.getMessage());
        }

        new StaticScenario().run();
        new RoundRobinScenario().run();
        new WeightedRoundRobinScenario().run();
        new AdaptiveScenario().run();
        // 2. Add PSO after Adaptive:
        new MinMinScenario().run();
        new PSOScenario().run();
        new PaperBasedPSOScenario().run();
        new AdaptiveGreenPSOScenario().run();
        new FaultToleranceScenario().run();

        System.out.println("\n" + "=".repeat(65));
        System.out.println("   ALL SCENARIOS COMPLETE  →  results.csv written");
        System.out.println("=".repeat(65));

        PythonChartGenerator.run();
        HtmlReportGenerator.generate();
    }
}
