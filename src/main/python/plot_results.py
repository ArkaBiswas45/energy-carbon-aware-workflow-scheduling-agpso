"""
plot_results.py
Reads results.csv and produces two charts:
  1. comparison_chart.png  – grouped bar chart (5 metrics × 4 algorithms)
  2. gantt_chart.png       – simulated Gantt chart showing task timelines
"""

import csv
import os
import math
import matplotlib
matplotlib.use("Agg")           # headless (no display required)
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
CSV = os.path.join(ROOT, "results.csv")
CHART1  = os.path.join(ROOT, "comparison_chart.png")
CHART2  = os.path.join(ROOT, "gantt_chart.png")

# ── Colour palette (one per algorithm) ───────────────────────────────────────
COLORS = {
    "Static":     "#E24B4A",
    "RoundRobin": "#EF9F27",
    "WeightedRR": "#1D9E75",
    "Adaptive":   "#534AB7",
    "MinMin":     "#2D7FF9",
    "PSO":        "#D94FD1",
    "Paper-Based PSO": "#7A6FF0",
    "AdaptiveGreenPSO": "#008C7A",
    "FaultTolerant": "#13B8A6",
}

# ─────────────────────────────────────────────────────────────────────────────
# 1. Read CSV
# ─────────────────────────────────────────────────────────────────────────────
# Replace the existing csv.DictReader loop with:
algorithms, avg_rt, makespans, throughputs, imbalances, sla_viols, energy_kwh, cost_usd, co2_grams = \
    [], [], [], [], [], [], [], [], []

with open(CSV, newline="") as f:
    reader = csv.DictReader(f)
    for row in reader:
        algorithms.append(row["Algorithm"])
        avg_rt.append(float(row["AvgResponseTime"]))
        makespans.append(float(row["Makespan"]))
        throughputs.append(float(row["Throughput"]))
        imbalances.append(float(row["ImbalanceIndex"]))
        sla_viols.append(int(row["SLAViolations"]))
        energy_kwh.append(float(row["EnergyKWh"]))
        cost_usd.append(float(row["CostUSD"]))
        co2_grams.append(float(row["CO2Grams"]))
n = len(algorithms)

# ─────────────────────────────────────────────────────────────────────────────
# 2. Chart 1 – Comparison grouped bar chart
# ─────────────────────────────────────────────────────────────────────────────
metrics = [
    ("Avg Response Time (s)",    avg_rt),
    ("Makespan (s)",             makespans),
    ("Throughput (tasks/s)",     throughputs),
    ("Load Imbalance Index",     imbalances),
    ("SLA Violations (tasks)",   sla_viols),
    ("Energy (kWh)",             energy_kwh),
    ("CO2 (grams)",              co2_grams),
]

fig, axes = plt.subplots(2, 4, figsize=(19, 9))
fig.suptitle("CloudSim Load Balancing – Algorithm Comparison", fontsize=16, fontweight="bold", y=1.01)
axes = axes.flatten()

bar_width = 0.55
x = np.arange(n)

for i, (title, data) in enumerate(metrics):
    ax = axes[i]
    bars = ax.bar(
        x, data,
        width=bar_width,
        color=[COLORS.get(a, "#888") for a in algorithms],
        edgecolor="white",
        linewidth=0.8,
    )

    # Value labels on top of each bar
    for bar, val in zip(bars, data):
        ax.text(
            bar.get_x() + bar.get_width() / 2,
            bar.get_height() * 1.015,
            f"{val:.2f}",
            ha="center", va="bottom", fontsize=9, fontweight="500",
            )

    # Highlight the best bar: throughput is higher-is-better, the rest are lower-is-better.
    best_idx = int(np.argmax(data)) if "Throughput" in title else int(np.argmin(data))
    bars[best_idx].set_edgecolor("#222")
    bars[best_idx].set_linewidth(2)
    ax.text(
        bars[best_idx].get_x() + bars[best_idx].get_width() / 2,
        -max(data) * 0.13,
        "★ best",
        ha="center", va="top", fontsize=8, color="#333", style="italic",
        )

    ax.set_title(title, fontsize=11, fontweight="500")
    ax.set_xticks(x)
    ax.set_xticklabels(algorithms, fontsize=9)
    ax.set_ylim(bottom=0, top=max(data) * 1.28)
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", linestyle="--", alpha=0.4)

# Legend in the final subplot area
axes[7].axis("off")
patches = [mpatches.Patch(color=COLORS[a], label=a) for a in algorithms if a in COLORS]
axes[7].legend(handles=patches, loc="center", fontsize=11, title="Algorithm", title_fontsize=12)

plt.tight_layout()
plt.savefig(CHART1, dpi=150, bbox_inches="tight")
plt.close()
print(f"Saved: {CHART1}")

# ─────────────────────────────────────────────────────────────────────────────
# 3. Chart 2 – Simulated Gantt chart
#    (reconstructed from makespan values + task count assumptions)
# ─────────────────────────────────────────────────────────────────────────────
# We simulate task timing using:
#   task_duration = task_length_MI / vm_mips
# ── Update task lengths for 15 tasks (5 HIGH + 5 MEDIUM + 5 LOW) ────────
TASK_LENGTHS = [2000]*5 + [5000]*5 + [9000]*5

# ── Update VM_MIPS to include all algorithms and 5 VMs ──────────────────
VM_MIPS = {
    "Static":     [2000, 1500, 1000, 750, 500],
    "RoundRobin": [2000, 1500, 1000, 750, 500],
    "WeightedRR": [2000, 1500, 1000, 750, 500],
    "Adaptive":   [2000, 1500, 1000, 750, 500, 1500],  # VM-5 auto-scaled
    "MinMin":     [2000, 1500, 1000, 750, 500],
    "PSO":        [2000, 1500, 1000, 750, 500],
    "Paper-Based PSO": [2000, 1500, 1000, 750, 500],
    "AdaptiveGreenPSO": [2000, 1500, 1000, 750, 500],
    "FaultTolerant": [1500, 1000, 750, 500],
}

def simulate_assignment(algo, task_lengths):
    mips_list = VM_MIPS.get(algo, [2000, 1500, 1000, 750, 500])
    n_vms     = len(mips_list)
    assignment = []

    if algo == "Static":
        for l in task_lengths:
            assignment.append((0, l / mips_list[0]))

    elif algo == "RoundRobin":
        for i, l in enumerate(task_lengths):
            vm = i % n_vms
            assignment.append((vm, l / mips_list[vm]))

    elif algo == "WeightedRR":
        from math import gcd
        from functools import reduce
        g = reduce(gcd, mips_list)
        pool = []
        for v, m in enumerate(mips_list):
            pool.extend([v] * (m // g))
        for i, l in enumerate(task_lengths):
            vm = pool[i % len(pool)]
            assignment.append((vm, l / mips_list[vm]))

    elif algo in ("Adaptive",):
        THRESHOLD = 3
        load = [0] * n_vms
        assign_map = {}
        for idx, l in enumerate(task_lengths):
            best, best_load = None, 9999
            for v in range(n_vms):
                if load[v] < THRESHOLD and (load[v] < best_load or best is None):
                    best, best_load = v, load[v]
            if best is None:
                for v in range(n_vms):
                    if load[v] < best_load or best is None:
                        best, best_load = v, load[v]
            load[best] += 1
            assign_map[idx] = (best, l / mips_list[best])
        assignment = [assign_map[i] for i in range(len(task_lengths))]

    elif algo in ("MinMin", "PSO", "Paper-Based PSO", "AdaptiveGreenPSO", "FaultTolerant"):
        # Min-Min heuristic (PSO finds similar optimal solution)
        ready = [0.0] * n_vms
        unassigned = list(range(len(task_lengths)))
        assign_map = {}
        while unassigned:
            best_ui, best_vm, best_ect = None, None, float('inf')
            for ui in unassigned:
                for v in range(n_vms):
                    ect = ready[v] + task_lengths[ui] / mips_list[v]
                    if ect < best_ect:
                        best_ect, best_ui, best_vm = ect, ui, v
            ready[best_vm] = best_ect
            assign_map[best_ui] = (best_vm, task_lengths[best_ui] / mips_list[best_vm])
            unassigned.remove(best_ui)
        assignment = [assign_map[i] for i in range(len(task_lengths))]

    return assignment
PRIORITY_COLORS = ["#E24B4A", "#EF9F27", "#1D9E75"]   # high / medium / low

fig, axes = plt.subplots(len(algorithms), 1, figsize=(14, 10), sharex=False)
fig.suptitle("Simulated Gantt Chart – Task Execution Timeline per Algorithm",
             fontsize=14, fontweight="bold")

for ax_i, algo in enumerate(algorithms):
    ax = axes[ax_i]
    assignment = simulate_assignment(algo, TASK_LENGTHS)
    vm_cursor  = {}   # current time cursor per VM

    for task_i, (vm_id, duration) in enumerate(assignment):
        start = vm_cursor.get(vm_id, 0)
        end   = start + duration
        vm_cursor[vm_id] = end

        priority  = min(task_i // 5, 2)
        color     = PRIORITY_COLORS[priority % len(PRIORITY_COLORS)]

        ax.barh(vm_id, duration, left=start, height=0.5,
                color=color, edgecolor="white", linewidth=0.6)
        if duration > 0.3:
            ax.text(start + duration / 2, vm_id, f"T{task_i}",
                    ha="center", va="center", fontsize=7, color="white", fontweight="500")

    n_vms = len(VM_MIPS[algo])
    ax.set_yticks(range(n_vms))
    ax.set_yticklabels([f"VM-{v}\n({VM_MIPS[algo][v]} MIPS)" for v in range(n_vms)], fontsize=8)
    ax.set_title(algo, fontsize=10, fontweight="500", loc="left", pad=4)
    ax.spines[["top", "right"]].set_visible(False)
    ax.set_xlabel("Time (s)", fontsize=8)
    ax.grid(axis="x", linestyle="--", alpha=0.3)

# Legend
legend_patches = [
    mpatches.Patch(color=PRIORITY_COLORS[0], label="HIGH priority tasks"),
    mpatches.Patch(color=PRIORITY_COLORS[1], label="MEDIUM priority tasks"),
    mpatches.Patch(color=PRIORITY_COLORS[2], label="LOW priority tasks"),
]
fig.legend(handles=legend_patches, loc="lower center", ncol=3,
           fontsize=9, bbox_to_anchor=(0.5, -0.01))

plt.tight_layout()
plt.savefig(CHART2, dpi=150, bbox_inches="tight")
plt.close()
print(f"Saved: {CHART2}")


# ─────────────────────────────────────────────────────────────────────────────
# 4. Chart 3 – Radar / Spider chart (multi-dimensional comparison)
# ─────────────────────────────────────────────────────────────────────────────
import matplotlib.patches as mpatches
from matplotlib.patches import FancyArrowPatch

CHART3 = os.path.join(ROOT, "radar_chart.png")

# Metrics to show on radar (normalise each to [0,1] where 1 = worst)
# For throughput: higher is better → invert before normalising
radar_labels  = ["Avg\nResponse", "Makespan", "Throughput\n(inv)", "Imbalance", "SLA\nViolations", "Energy\n(kWh)", "Cost ($)", "CO2"]
radar_data_raw = list(zip(avg_rt, makespans,
                          [-t for t in throughputs],   # invert
                          imbalances,
                          sla_viols,
                          energy_kwh,
                          cost_usd,
                          co2_grams))

# Read energy, cost and carbon from CSV
energy_kwh, cost_usd, co2_grams = [], [], []
# (re-open CSV to get new columns)
with open(CSV, newline="") as f:
    reader = csv.DictReader(f)
    for row in reader:
        energy_kwh.append(float(row["EnergyKWh"]))
        cost_usd.append(float(row["CostUSD"]))
        co2_grams.append(float(row["CO2Grams"]))

# Normalise each dimension to [0, 1]
def normalise(col):
    mn, mx = min(col), max(col)
    return [(v - mn) / (mx - mn + 1e-9) for v in col]

dims = [avg_rt, makespans, [-t for t in throughputs],
        imbalances, sla_viols, energy_kwh, cost_usd, co2_grams]
norm_dims = [normalise(d) for d in dims]

# Reshape: one list per algorithm
n_metrics = len(radar_labels)
angles = [k / float(n_metrics) * 2 * np.pi for k in range(n_metrics)]
angles += angles[:1]   # close the polygon

fig, ax = plt.subplots(figsize=(8, 8), subplot_kw=dict(polar=True))
fig.suptitle("Algorithm Comparison — Radar Chart\n(lower area = better overall)",
             fontsize=14, fontweight="bold")

for i, algo in enumerate(algorithms):
    values = [norm_dims[d][i] for d in range(n_metrics)]
    values += values[:1]
    color = COLORS.get(algo, "#888")
    ax.plot(angles, values, color=color, linewidth=2, linestyle="solid", label=algo)
    ax.fill(angles, values, color=color, alpha=0.10)

ax.set_xticks(angles[:-1])
ax.set_xticklabels(radar_labels, size=10)
ax.set_ylim(0, 1)
ax.set_yticks([0.25, 0.5, 0.75, 1.0])
ax.set_yticklabels(["0.25", "0.50", "0.75", "1.00"], size=8, color="gray")
ax.spines["polar"].set_color("lightgray")
ax.grid(color="lightgray", linestyle="--", linewidth=0.5)
ax.legend(loc="upper right", bbox_to_anchor=(1.35, 1.15), fontsize=10)

plt.tight_layout()
plt.savefig(CHART3, dpi=150, bbox_inches="tight")
plt.close()
print(f"Saved: {CHART3}")

# ─────────────────────────────────────────────────────────────────────────────
# 4. Chart 4 – PSO Convergence Curve
# ─────────────────────────────────────────────────────────────────────────────
import os
def render_convergence(csv_path, chart_path, title, color, ylabel):
    iters, fitness = [], []
    with open(csv_path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            iters.append(int(row["Iteration"]))
            fitness.append(float(row["BestFitness"]))

    if not iters:
        return

    fig, ax = plt.subplots(figsize=(10, 5))
    fig.suptitle(title, fontsize=14, fontweight="bold")
    ax.plot(iters, fitness, color=color, linewidth=2)
    ax.fill_between(iters, fitness, alpha=0.15, color=color)
    ax.set_xlabel("Iteration", fontsize=11)
    ax.set_ylabel(ylabel, fontsize=11)
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(linestyle="--", alpha=0.4)

    # Annotate final value
    ax.annotate(f"Final: {fitness[-1]:.4f}",
                xy=(iters[-1], fitness[-1]),
                xytext=(-80, 20), textcoords="offset points",
                arrowprops=dict(arrowstyle="->", color="#333"),
                fontsize=10)

    plt.tight_layout()
    plt.savefig(chart_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"Saved: {chart_path}")

CHART4 = os.path.join(ROOT, "pso_convergence.png")
CONV_CSV = os.path.join(ROOT, "pso_convergence.csv")
if os.path.exists(CONV_CSV):
    render_convergence(CONV_CSV, CHART4,
                       "PSO Convergence Curve - Best Fitness per Iteration",
                       COLORS["PSO"],
                       "Best Fitness (makespan + SLA penalty)")

CHART5 = os.path.join(ROOT, "adaptive_green_pso_convergence.png")
AGPSO_CONV_CSV = os.path.join(ROOT, "adaptive_green_pso_convergence.csv")
if os.path.exists(AGPSO_CONV_CSV):
    render_convergence(AGPSO_CONV_CSV, CHART5,
                       "Adaptive Green PSO Convergence Curve - Best Fitness per Iteration",
                       COLORS["AdaptiveGreenPSO"],
                       "Best Normalized Adaptive Fitness")
