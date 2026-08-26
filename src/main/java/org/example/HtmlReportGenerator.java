package org.example;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Generates a polished HTML report from results.csv after all simulations.
 * Opens directly in a browser — no extra tools needed.
 *
 * Report sections:
 *   - Header with timestamp and project title
 *   - Summary cards: winner per metric (highlighted in green)
 *   - Full comparison table with colour-coded best/worst cells
 *   - Embedded chart images (comparison, Gantt, radar, PSO convergence)
 *   - Algorithm description cards
 */
public class HtmlReportGenerator {

    public static void generate() {
        System.out.println("\n" + "=".repeat(65));
        System.out.println("  Generating HTML Report...");
        System.out.println("=".repeat(65));

        String root = System.getProperty("user.dir");
        String csvPath  = root + "/results.csv";
        String htmlPath = root + "/simulation_report.html";

        try {
            // ── Read CSV ──────────────────────────────────────────────────
            List<String[]> rows = new ArrayList<>();
            String[] headers;
            try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
                headers = br.readLine().split(",");
                String line;
                while ((line = br.readLine()) != null && !line.isBlank())
                    rows.add(line.split(","));
            }

            // ── Find best value per metric column ─────────────────────────
            // For each numeric column: is lower better (most) or higher better (throughput)?
            boolean[] lowerIsBetter = { false, true, true, false, true, true, true, true, true };
            int numCols = headers.length;
            double[] bestVal = new double[numCols];
            Arrays.fill(bestVal, Double.MAX_VALUE);

            for (int col = 1; col < numCols; col++) {
                boolean low = col < lowerIsBetter.length && lowerIsBetter[col];
                double best = low ? Double.MAX_VALUE : Double.MIN_VALUE;
                for (String[] row : rows) {
                    if (col >= row.length) continue;
                    double v = Double.parseDouble(row[col]);
                    best = low ? Math.min(best, v) : Math.max(best, v);
                }
                bestVal[col] = best;
            }

            // ── Algorithm descriptions ────────────────────────────────────
            Map<String, String[]> descriptions = new LinkedHashMap<>();
            descriptions.put("Static",        new String[]{"Baseline",       "All tasks sent to VM-0. Demonstrates worst-case overloading."});
            descriptions.put("RoundRobin",    new String[]{"Simple heuristic","Cyclic distribution. Fair but ignores VM speed."});
            descriptions.put("WeightedRR",    new String[]{"Capacity-aware",  "Tasks proportional to MIPS rating. Better utilisation."});
            descriptions.put("Adaptive",      new String[]{"Dynamic",         "Priority-aware + auto-scaling. Threshold-based overload guard."});
            descriptions.put("MinMin",        new String[]{"Classic research", "Assigns each task to the VM with minimum completion time."});
            descriptions.put("PSO",           new String[]{"Bio-inspired",    "Particle Swarm Optimisation using makespan and SLA penalty."});
            descriptions.put("Paper-Based PSO", new String[]{"Paper baseline", "Workflow thresholds with fixed makespan, energy and load objectives."});
            descriptions.put("AdaptiveGreenPSO", new String[]{"Proposed",      "Adaptive normalized PSO for makespan, energy, carbon, SLA and load balance."});
            descriptions.put("FaultTolerant", new String[]{"Resilient",       "VM failure injection + automatic task migration + recovery."});

            // ── Build HTML ────────────────────────────────────────────────
            StringBuilder sb = new StringBuilder();
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

            sb.append("""
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>CloudSim Simulation Report</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
         background: #f5f5f0; color: #1a1a18; line-height: 1.6; }
  .page { max-width: 1100px; margin: 0 auto; padding: 40px 24px; }
  header { border-bottom: 2px solid #1D9E75; padding-bottom: 20px; margin-bottom: 36px; }
  header h1 { font-size: 28px; font-weight: 600; color: #1a1a18; }
  header p  { color: #5f5e5a; margin-top: 6px; font-size: 14px; }
  h2 { font-size: 20px; font-weight: 500; margin: 40px 0 16px; color: #1a1a18; }
  h3 { font-size: 16px; font-weight: 500; margin-bottom: 12px; }

  .summary-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 14px; }
  .summary-card { background: #fff; border: 1px solid #e0ded8; border-radius: 10px;
                  padding: 16px; border-top: 3px solid #1D9E75; }
  .summary-card .label { font-size: 12px; color: #888780; text-transform: uppercase;
                          letter-spacing: .04em; margin-bottom: 4px; }
  .summary-card .winner { font-size: 18px; font-weight: 600; color: #0F6E56; }
  .summary-card .value  { font-size: 13px; color: #5f5e5a; margin-top: 2px; }

  table { width: 100%; border-collapse: collapse; background: #fff;
          border-radius: 10px; overflow: hidden;
          border: 1px solid #e0ded8; font-size: 13px; }
  th { background: #1D9E75; color: #fff; padding: 12px 14px;
       text-align: left; font-weight: 500; white-space: nowrap; }
  td { padding: 11px 14px; border-bottom: 1px solid #f0ede8; white-space: nowrap; }
  tr:last-child td { border-bottom: none; }
  tr:hover td { background: #f9f8f5; }
  .best  { background: #e1f5ee !important; color: #0F6E56; font-weight: 600; }
  .worst { background: #fcebeb !important; color: #A32D2D; }
  .algo-name { font-weight: 600; color: #1a1a18; }

  .chart-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
  .chart-box { background: #fff; border: 1px solid #e0ded8; border-radius: 10px; padding: 16px; }
  .chart-box h3 { font-size: 14px; color: #5f5e5a; margin-bottom: 12px; }
  .chart-box img { width: 100%; border-radius: 6px; }

  .algo-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 14px; }
  .algo-card { background: #fff; border: 1px solid #e0ded8; border-radius: 10px; padding: 16px; }
  .algo-card .tag { display: inline-block; background: #e1f5ee; color: #0F6E56;
                    font-size: 11px; padding: 2px 8px; border-radius: 20px;
                    font-weight: 500; margin-bottom: 8px; }
  .algo-card p { font-size: 13px; color: #5f5e5a; }
  .algo-card h3 { color: #1a1a18; font-size: 14px; }
  footer { margin-top: 48px; padding-top: 20px; border-top: 1px solid #e0ded8;
           font-size: 12px; color: #888780; text-align: center; }
</style>
</head>
<body>
<div class="page">
<header>
  <h1>CloudSim Load Balancing — Simulation Report</h1>
  <p>Generated: """ + timestamp + " &nbsp;|&nbsp; " + rows.size() + " algorithms &nbsp;|&nbsp; 8 metrics per algorithm</p>\n</header>\n");

            // ── Summary winner cards ──────────────────────────────────────
            sb.append("<h2>Best algorithm per metric</h2>\n<div class=\"summary-grid\">\n");
            String[] metricLabels = {"Avg response time","Makespan","Throughput","Load imbalance",
                    "SLA violations","Energy (kWh)","Cost (USD)","CO2 (grams)"};
            for (int col = 1; col < numCols && col - 1 < metricLabels.length; col++) {
                String label = metricLabels[col - 1];
                boolean low = col < lowerIsBetter.length && lowerIsBetter[col];
                String winner = ""; String winVal = "";
                for (String[] row : rows) {
                    if (col >= row.length) continue;
                    if (Double.parseDouble(row[col]) == bestVal[col]) {
                        winner = row[0]; winVal = row[col];
                    }
                }
                sb.append(String.format(
                        "<div class=\"summary-card\"><div class=\"label\">%s</div>" +
                                "<div class=\"winner\">%s</div><div class=\"value\">%s</div></div>%n",
                        label, winner, winVal));
            }
            sb.append("</div>\n");

            // ── Full comparison table ─────────────────────────────────────
            sb.append("<h2>Full comparison table</h2>\n<table>\n<thead><tr>");
            for (String h : headers) sb.append("<th>").append(h).append("</th>");
            sb.append("</tr></thead>\n<tbody>\n");

            for (String[] row : rows) {
                sb.append("<tr>");
                for (int col = 0; col < row.length && col < numCols; col++) {
                    if (col == 0) {
                        sb.append("<td class=\"algo-name\">").append(row[0]).append("</td>");
                    } else {
                        double v = Double.parseDouble(row[col]);
                        boolean low = col < lowerIsBetter.length && lowerIsBetter[col];
                        // Find worst
                        double worst = low ? Double.MIN_VALUE : Double.MAX_VALUE;
                        for (String[] r2 : rows)
                            if (col < r2.length) {
                                double v2 = Double.parseDouble(r2[col]);
                                worst = low ? Math.max(worst, v2) : Math.min(worst, v2);
                            }
                        String cls = (v == bestVal[col]) ? " class=\"best\""
                                : (v == worst)        ? " class=\"worst\"" : "";
                        sb.append("<td").append(cls).append(">").append(row[col]).append("</td>");
                    }
                }
                sb.append("</tr>\n");
            }
            sb.append("</tbody></table>\n");

            // ── Chart images ──────────────────────────────────────────────
            sb.append("<h2>Charts</h2>\n<div class=\"chart-grid\">\n");
            String[][] charts = {
                    {"comparison_chart.png",  "Algorithm comparison (bar)"},
                    {"radar_chart.png",       "Multi-dimensional radar chart"},
                    {"gantt_chart.png",       "Task execution timeline (Gantt)"},
                    {"pso_convergence.png",   "PSO convergence curve"},
                    {"adaptive_green_pso_convergence.png", "Adaptive Green PSO convergence curve"},
            };
            for (String[] ch : charts) {
                File f = new File(root + "/" + ch[0]);
                if (f.exists()) {
                    sb.append(String.format(
                            "<div class=\"chart-box\"><h3>%s</h3>" +
                                    "<img src=\"%s\" alt=\"%s\"/></div>%n",
                            ch[1], ch[0], ch[1]));
                }
            }
            sb.append("</div>\n");

            // ── Algorithm cards ───────────────────────────────────────────
            sb.append("<h2>Algorithm overview</h2>\n<div class=\"algo-grid\">\n");
            for (Map.Entry<String, String[]> e : descriptions.entrySet()) {
                sb.append(String.format(
                        "<div class=\"algo-card\"><span class=\"tag\">%s</span>" +
                                "<h3>%s</h3><p>%s</p></div>%n",
                        e.getValue()[0], e.getKey(), e.getValue()[1]));
            }
            sb.append("</div>\n");

            sb.append("<footer>CloudSim Advanced Load Balancing Simulation &mdash; Final Year Project</footer>\n");
            sb.append("</div></body></html>");

            Files.writeString(Path.of(htmlPath), sb.toString());
            System.out.println("  ✔ Report saved: " + htmlPath);
            System.out.println("  Open simulation_report.html in your browser to view.");

        } catch (Exception e) {
            System.err.println("  ✘ Could not generate report: " + e.getMessage());
        }
    }
}
