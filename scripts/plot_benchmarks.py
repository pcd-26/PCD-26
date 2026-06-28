#!/usr/bin/env python3
"""Generate report-ready charts from benchmark CSV files."""

from __future__ import annotations

import argparse
import base64
import math
import sys
from pathlib import Path

import pandas as pd

try:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
except ModuleNotFoundError:
    plt = None

IMPL_ORDER = ["sequential", "threads", "executor"]
IMPL_COLORS = {
    "sequential": "#1f77b4",
    "threads": "#d62728",
    "executor": "#2ca02c",
}
BALL_ORDER = [100, 500, 1000, 2000, 5000]
REPORT_CHART_DPI = 300

if plt is not None:
    matplotlib.rcParams.update(
        {
            "font.family": "DejaVu Sans",
            "axes.titlesize": 15,
            "axes.titleweight": "bold",
            "axes.labelsize": 12,
            "legend.fontsize": 11,
            "xtick.labelsize": 10,
            "ytick.labelsize": 10,
            "figure.titlesize": 17,
        }
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input-dir",
        type=Path,
        default=Path("benchmarks", "results"),
        help="Benchmark results directory containing the CSV files.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("benchmarks", "charts"),
        help="Directory where the generated chart images will be written.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    input_dir = args.input_dir
    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    if (input_dir / "aggregated-results.csv").exists():
        render_new_layout(input_dir, output_dir)
    elif (input_dir / "benchmark-summary.csv").exists():
        render_legacy_layout(input_dir, output_dir)
    else:
        raise FileNotFoundError(
            f"missing benchmark CSV layout in {input_dir}; expected either aggregated-results.csv or benchmark-summary.csv"
        )

    print(f"charts_written output_dir={output_dir}")


def render_new_layout(input_dir: Path, output_dir: Path) -> None:
    headless = read_csv(input_dir / "aggregated-results.csv", required=True)
    speedup = read_csv(input_dir / "speedup-results.csv", required=True)
    scalability = read_csv(input_dir / "aggregated-scalability-results.csv", required=True)
    gui = read_csv(input_dir / "aggregated-gui-results.csv", required=True)

    charts = [
        (headless, output_dir / "execution-time-vs-balls", "avgElapsedMs", "Mean execution time (ms)", "Execution time vs number of balls", "min", plot_best_by_ball),
        (speedup, output_dir / "speedup-vs-balls", "speedup", "Speedup", "Speedup vs number of balls", "max", plot_best_by_ball),
        (headless, output_dir / "throughput-vs-balls", "avgThroughput", "Mean throughput (steps/s)", "Throughput vs number of balls", "max", plot_best_by_ball),
        (scalability, output_dir / "scalability-elapsed-time-vs-workers", "avgElapsedMs", "Elapsed time (ms)", "Scalability elapsed time vs worker threads", None, plot_worker_panels),
        (scalability, output_dir / "scalability-throughput-vs-workers", "avgThroughput", "Throughput (steps/s)", "Scalability throughput vs worker threads", None, plot_worker_panels),
        (scalability, output_dir / "coordination-overhead-vs-workers", "avgCoordinationMs", "Coordination time (ms)", "Coordination overhead vs worker threads", None, plot_worker_panels),
        (gui, output_dir / "gui-frame-time-vs-balls", "avgFrameMs", "Average frame time (ms)", "GUI frame time vs number of balls", "min", plot_best_by_ball),
        (gui, output_dir / "gui-fps-vs-balls", "avgFps", "Average frames per second", "GUI FPS vs number of balls", "max", plot_best_by_ball),
    ]

    for df, output_stem, value_col, ylabel, title, best_agg, plotter in charts:
        if plt is not None:
            if plotter is plot_best_by_ball:
                plot_best_by_ball(df, output_stem.with_suffix(".png"), value_col, ylabel, title, best_agg)
            else:
                plot_worker_panels(df, output_stem.with_suffix(".png"), value_col, ylabel, title)
        else:
            write_placeholder_pair(output_stem, title)


def render_legacy_layout(input_dir: Path, output_dir: Path) -> None:
    summary = read_csv(input_dir / "benchmark-summary.csv", required=True)
    speedup = read_csv(input_dir / "speedup-table.csv", required=True)
    efficiency = read_csv(input_dir / "efficiency-table.csv", required=True)
    runs = read_csv(input_dir / "benchmark-runs.csv", required=True)
    gui = read_csv(input_dir / "gui-responsiveness.csv", required=False)

    if plt is not None:
        plot_best_by_ball(
            summary,
            output_dir / "01_best_execution_time_vs_balls.png",
            value_col="meanMillis",
            ylabel="Mean execution time (ms)",
            title="Best execution time vs number of balls",
            best_agg="min",
        )
        plot_best_by_ball(
            summary,
            output_dir / "02_best_throughput_vs_balls.png",
            value_col="meanThroughput",
            ylabel="Mean throughput (steps/s)",
            title="Best throughput vs number of balls",
            best_agg="max",
        )
        plot_thread_metric_panels(
            speedup,
            output_dir / "03_speedup_vs_thread_count.png",
            value_col="speedup",
            ylabel="Speedup",
            title="Speedup vs worker threads",
            implementations=("threads", "executor"),
        )
        plot_thread_metric_panels(
            efficiency,
            output_dir / "04_efficiency_vs_thread_count.png",
            value_col="efficiency",
            ylabel="Efficiency",
            title="Efficiency vs worker threads",
            implementations=("threads", "executor"),
        )
        plot_coordination_overhead_panels(
            runs,
            output_dir / "05_coordination_overhead_vs_thread_count.png",
        )
        plot_best_by_ball(
            summary,
            output_dir / "06_cpu_utilization_vs_thread_count.png",
            value_col="meanCpuUtilizationPercent",
            ylabel="CPU utilization (%)",
            title="CPU utilization vs worker threads",
            best_agg="max",
        )
        if not gui.empty and should_plot_gui_latency(gui):
            plot_gui_latency(gui, output_dir / "07_gui_latency_vs_balls.png")
    else:
        fallback_plot_best_by_ball(
            summary,
            output_dir / "01_best_execution_time_vs_balls.png",
            value_col="meanMillis",
            best_agg="min",
        )
        fallback_plot_best_by_ball(
            summary,
            output_dir / "02_best_throughput_vs_balls.png",
            value_col="meanThroughput",
            best_agg="max",
        )
        fallback_plot_thread_metric_panels(
            speedup,
            output_dir / "03_speedup_vs_thread_count.png",
            value_col="speedup",
            implementations=("threads", "executor"),
        )
        fallback_plot_thread_metric_panels(
            efficiency,
            output_dir / "04_efficiency_vs_thread_count.png",
            value_col="efficiency",
            implementations=("threads", "executor"),
        )
        fallback_plot_coordination_overhead_panels(
            runs,
            output_dir / "05_coordination_overhead_vs_thread_count.png",
        )
        fallback_plot_best_by_ball(
            summary,
            output_dir / "06_cpu_utilization_vs_thread_count.png",
            value_col="meanCpuUtilizationPercent",
            best_agg="max",
        )
        if not gui.empty and should_plot_gui_latency(gui):
            fallback_plot_gui_latency(gui, output_dir / "07_gui_latency_vs_balls.png")


def read_csv(path: Path, required: bool = False) -> pd.DataFrame:
    if not path.exists():
        if required:
            raise FileNotFoundError(f"missing required benchmark CSV: {path}")
        return pd.DataFrame()
    return pd.read_csv(path)


def plot_best_by_ball(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
    ylabel: str,
    title: str,
    best_agg: str,
) -> None:
    required = {"balls", "implementation", value_col}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"missing required columns for {output_file.name}: {sorted(missing)}")

    fig, ax = plt.subplots(figsize=(10.5, 6.2), constrained_layout=True)
    fig.suptitle(title, fontsize=16, fontweight="bold", y=0.98)
    ax.set_title("Best measured worker count per implementation", fontsize=10, pad=10)

    for implementation in IMPL_ORDER:
        subset = df[df["implementation"].astype(str).str.lower() == implementation].copy()
        if subset.empty:
            continue
        if best_agg == "min":
            idx = subset.groupby("balls")[value_col].idxmin()
        else:
            idx = subset.groupby("balls")[value_col].idxmax()
        best = subset.loc[idx].sort_values("balls")
        ax.plot(
            best["balls"],
            best[value_col],
            marker="o",
            linewidth=2.2,
            color=IMPL_COLORS.get(implementation),
            label=implementation,
        )

    ax.set_xlabel("Number of balls")
    ax.set_ylabel(ylabel)
    ax.set_xticks(_xticks(df["balls"]))
    ax.grid(True, alpha=0.25)
    ax.legend(frameon=False)
    save_figure(fig, output_file)


def plot_worker_panels(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
    ylabel: str,
    title: str,
    implementations: tuple[str, ...] = ("threads", "executor"),
) -> None:
    required = {"balls", "implementation", "workers", value_col}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"missing required columns for {output_file.name}: {sorted(missing)}")

    balls_values = [ball for ball in BALL_ORDER if ball in set(df["balls"].tolist())]
    if not balls_values:
        balls_values = sorted(df["balls"].dropna().astype(int).unique().tolist())

    cols = min(3, max(1, len(balls_values)))
    rows = max(1, math.ceil(len(balls_values) / cols))
    fig, axes = plt.subplots(
        nrows=rows,
        ncols=cols,
        figsize=(5.1 * cols, 4.4 * rows),
        sharex=False,
        sharey=True,
        constrained_layout=True,
    )
    fig.suptitle(title, fontsize=16, fontweight="bold", y=1.02)
    axes_list = axes.flatten() if hasattr(axes, "flatten") else [axes]

    for index, ball in enumerate(balls_values):
        ax = axes_list[index]
        subset = df[df["balls"] == ball]
        for implementation in implementations:
            impl_subset = subset[subset["implementation"].astype(str).str.lower() == implementation].copy()
            if impl_subset.empty:
                continue
            impl_subset = impl_subset.sort_values("workers")
            ax.plot(
                impl_subset["workers"],
                impl_subset[value_col],
                marker="o",
                linewidth=2.0,
                color=IMPL_COLORS.get(implementation),
                label=implementation,
            )
        ax.set_title(f"{ball} balls", fontsize=11)
        ax.set_xlabel("Worker threads")
        ax.set_ylabel(ylabel)
        ax.set_xticks(_xticks(subset["workers"]))
        ax.grid(True, alpha=0.25)

    for ax in axes_list[len(balls_values):]:
        ax.axis("off")

    handles, labels = axes_list[0].get_legend_handles_labels()
    if handles:
        fig.legend(handles, labels, loc="lower center", bbox_to_anchor=(0.5, 0.0), ncol=len(handles), frameon=False)
    save_figure(fig, output_file)


def plot_thread_metric_panels(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
    ylabel: str,
    title: str,
    implementations: tuple[str, ...] = ("threads", "executor"),
) -> None:
    plot_worker_panels(df, output_file, value_col, ylabel, title, implementations=implementations)


def plot_coordination_overhead_panels(runs: pd.DataFrame, output_file: Path) -> None:
    required = {
        "balls",
        "implementation",
        "threads",
        "status",
        "syncTimeMillis",
        "aggregationTimeMillis",
        "taskSubmissionTimeMillis",
        "joinOrFutureWaitMillis",
    }
    missing = required - set(runs.columns)
    if missing:
        raise ValueError(f"missing required columns for {output_file.name}: {sorted(missing)}")

    filtered = runs[runs["status"] == "SUCCESS"].copy()
    filtered["coordinationMillis"] = (
        filtered["syncTimeMillis"].fillna(0.0)
        + filtered["aggregationTimeMillis"].fillna(0.0)
        + filtered["taskSubmissionTimeMillis"].fillna(0.0)
        + filtered["joinOrFutureWaitMillis"].fillna(0.0)
    )
    grouped = filtered.groupby(["balls", "implementation", "threads"], as_index=False)["coordinationMillis"].mean()
    plot_thread_metric_panels(
        grouped,
        output_file,
        value_col="coordinationMillis",
        ylabel="Coordination time (ms)",
        title="Coordination overhead vs worker threads",
        implementations=("threads", "executor"),
    )


def fallback_plot_best_by_ball(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
    best_agg: str,
) -> None:
    if plt is not None:
        raise RuntimeError("fallback_plot_best_by_ball should only be used without matplotlib")
    write_placeholder_pair(output_file.with_suffix(""), output_file.stem)


def fallback_plot_thread_metric_panels(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
    implementations: tuple[str, ...] = ("threads", "executor"),
) -> None:
    if plt is not None:
        raise RuntimeError("fallback_plot_thread_metric_panels should only be used without matplotlib")
    write_placeholder_pair(output_file.with_suffix(""), output_file.stem)


def fallback_plot_coordination_overhead_panels(runs: pd.DataFrame, output_file: Path) -> None:
    if plt is not None:
        raise RuntimeError("fallback_plot_coordination_overhead_panels should only be used without matplotlib")
    write_placeholder_pair(output_file.with_suffix(""), output_file.stem)


def should_plot_gui_latency(gui: pd.DataFrame) -> bool:
    required = {"balls", "implementation", "meanUpdateLatencyMillis", "maxUpdateLatencyMillis"}
    missing = required - set(gui.columns)
    if missing:
        return False
    return not gui.empty


def plot_gui_latency(gui: pd.DataFrame, output_file: Path) -> None:
    required = {"balls", "implementation", "meanUpdateLatencyMillis", "maxUpdateLatencyMillis"}
    missing = required - set(gui.columns)
    if missing:
        raise ValueError(f"missing required columns for {output_file.name}: {sorted(missing)}")

    fig, ax = plt.subplots(figsize=(10.5, 6.2), constrained_layout=True)
    fig.suptitle("GUI update latency vs number of balls", fontsize=16, fontweight="bold", y=0.98)

    for implementation in IMPL_ORDER:
        subset = gui[gui["implementation"].astype(str).str.lower() == implementation].copy()
        if subset.empty:
            continue
        grouped = subset.groupby("balls", as_index=False)[["meanUpdateLatencyMillis", "maxUpdateLatencyMillis"]].mean()
        grouped = grouped.sort_values("balls")
        lower = grouped["meanUpdateLatencyMillis"].to_numpy() * 0.0
        upper = (grouped["maxUpdateLatencyMillis"] - grouped["meanUpdateLatencyMillis"]).clip(lower=0.0).to_numpy()
        ax.errorbar(
            grouped["balls"],
            grouped["meanUpdateLatencyMillis"],
            yerr=[lower, upper],
            marker="o",
            linewidth=2.0,
            capsize=3,
            color=IMPL_COLORS.get(implementation),
            label=implementation,
        )

    ax.set_xlabel("Number of balls")
    ax.set_ylabel("Mean update latency (ms)")
    ax.set_xticks(_xticks(gui["balls"]))
    ax.grid(True, alpha=0.25)
    ax.legend(frameon=False)
    save_figure(fig, output_file)


def save_figure(fig: plt.Figure, output_file: Path) -> None:
    fig.savefig(output_file, dpi=REPORT_CHART_DPI, bbox_inches="tight")
    fig.savefig(output_file.with_suffix(".svg"), dpi=REPORT_CHART_DPI, bbox_inches="tight")
    plt.close(fig)


def write_placeholder_pair(output_stem: Path, title: str) -> None:
    png = output_stem.with_suffix(".png")
    svg = output_stem.with_suffix(".svg")
    png.write_bytes(_placeholder_png())
    svg.write_text(
        f"""<svg xmlns="http://www.w3.org/2000/svg" width="400" height="200">
  <rect width="100%" height="100%" fill="white"/>
  <text x="20" y="60" font-family="DejaVu Sans, sans-serif" font-size="18" fill="#222">{title}</text>
</svg>
""",
        encoding="utf-8",
    )


def _placeholder_png() -> bytes:
    # 1x1 white PNG. This is only used when matplotlib is unavailable.
    return base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO9S7ZkAAAAASUVORK5CYII="
    )


def _xticks(values: pd.Series) -> list[int]:
    unique = sorted({int(value) for value in values.dropna().tolist()})
    return unique if unique else BALL_ORDER


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"chart_generation_failed message={exc}", file=sys.stderr)
        raise
