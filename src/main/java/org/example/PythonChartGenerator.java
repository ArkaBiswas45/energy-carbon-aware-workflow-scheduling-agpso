package org.example;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Launches the Python chart generator after all scenarios finish.
 * Tries the virtual-environment Python first, then falls back to system Python.
 */
public class PythonChartGenerator {

    public static void run() {
        System.out.println("\n" + "=".repeat(65));
        System.out.println("  Generating charts via Python...");
        System.out.println("=".repeat(65));

        String root   = System.getProperty("user.dir");
        String script = root + "/src/main/python/plot_results.py";

        // Try venv first, then system python3 / python
        String[] candidates = {
                root + "/venv/bin/python",
                "python3",
                "python"
        };

        for (String python : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(python, script);
                pb.directory(new java.io.File(root));
                pb.redirectErrorStream(true);
                Process p = pb.start();

                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null)
                        System.out.println("  [py] " + line);
                }

                int code = p.waitFor();
                if (code == 0) {
                    System.out.println("\n  ✔ Charts saved:");
                    System.out.println("      " + root + "/comparison_chart.png");
                    System.out.println("      " + root + "/gantt_chart.png");
                    return;
                }
            } catch (Exception ignored) { }
        }

        System.out.println("  ✘ Could not run Python. Ensure matplotlib is installed:");
        System.out.println("      pip install matplotlib");
    }
}