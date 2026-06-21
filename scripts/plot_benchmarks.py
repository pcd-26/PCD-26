#!/usr/bin/env python3
"""Generate report-ready charts from benchmark CSV files."""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Iterable, Sequence

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

IMPL_ORDER = ["sequential", "threads", "executor"]
IMPL_COLORS = {
    "sequential": "#1f77b4",
    "threads": "#d62728",
    "executor": "#2ca02c",
}
BALL_ORDER = [100, 500, 1000, 2000, 5000]
THREAD_ORDER = [1, 2, 4, 8]


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

    summary = read_csv(input_dir / "benchmark-summary.csv", required=True)
    speedup = read_csv(input_dir / "speedup-table.csv", required=True)
    efficiency = read_csv(input_dir / "efficiency-table.csv", required=True)
    runs = read_csv(input_dir / "benchmark-runs.csv", required=True)
    gui = read_csv(input_dir / "gui-responsiveness.csv", required=True)

    plot_best_by_ball(
        summary,
        output_dir / "execution-time-vs-balls.png",
        value_col="meanMillis",
        ylabel="Mean execution time (ms)",
        title="Execution Time vs Balls",
        best_agg="min",
        include_thread_annotations=True,
    )
    plot_best_by_ball(
        summary,
        output_dir / "throughput-vs-balls.png",
        value_col="meanThroughput",
        ylabel="Mean throughput (steps/s)",
        title="Throughput vs Balls",
        best_agg="max",
        include_thread_annotations=True,
    )

    plot_thread_metric_panels(
        speedup,
        output_dir / "speedup-vs-thread-count.png",
        value_col="speedup",
        ylabel="Speedup",
        title="Speedup vs Thread Count",
        add_reference_line=1.0,
    )
    plot_thread_metric_panels(
        efficiency,
        output_dir / "efficiency-vs-thread-count.png",
        value_col="efficiency",
        ylabel="Efficiency",
        title="Efficiency vs Thread Count",
        add_reference_line=1.0,
    )
    plot_thread_metric_panels(
        summary,
        output_dir / "cpu-utilization-vs-thread-count.png",
        value_col="meanCpuUtilizationPercent",
        ylabel="CPU utilization (%)",
        title="CPU Utilization vs Thread Count",
    )
    plot_sync_overhead_panels(
        runs,
        output_dir / "synchronization-overhead-vs-thread-count.png",
    )
    plot_gui_latency(
        gui,
        output_dir / "gui-latency-vs-balls.png",
    )

    print(f"charts_written output_dir={output_dir}")


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
    include_thread_annotations: bool = False,
) -> None:
    required = {"balls", "implementation", value_col, "threads"}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"missing required columns for {output_file.name}: {sorted(missing)}")

    fig, ax = plt.subplots(figsize=(10, 6), constrained_layout=True)
    fig.suptitle(title, fontsize=15, fontweight="bold")
    for implementation in IMPL_ORDER:
        subset = df[df["implementation"] == implementation].copy()
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
        if include_thread_annotations:
            for _, row in best.iterrows():
                ax.annotate(
                    f"t{int(row['threads'])}",
                    (row["balls"], row[value_col]),
                    textcoords="offset points",
                    xytext=(0, 7),
                    ha="center",
                    fontsize=8,
                    color=IMPL_COLORS.get(implementation),
                )

    ax.set_xlabel("Balls")
    ax.set_ylabel(ylabel)
    ax.set_xticks(BALL_ORDER)
    ax.grid(True, alpha=0.25)
    ax.legend(frameon=False)
    save_figure(fig, output_file)


def plot_thread_metric_panels(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
    ylabel: str,
    title: str,
    add_reference_line: float | None = None,
) -> None:
    required = {"balls", "implementation", "threads", value_col}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"missing required columns for {output_file.name}: {sorted(missing)}")

    balls_values = [ball for ball in BALL_ORDER if ball in set(df["balls"].tolist())]
    if not balls_values:
        balls_values = sorted(df["balls"].dropna().astype(int).unique().tolist())

    fig, axes = plt.subplots(
        nrows=2,
        ncols=3,
        figsize=(15, 8),
        sharex=False,
        sharey=True,
        constrained_layout=True,
    )
    fig.suptitle(title, fontsize=15, fontweight="bold")
    axes_list = axes.flatten()

    for index, ball in enumerate(balls_values):
        ax = axes_list[index]
        subset = df[df["balls"] == ball]
        for implementation in IMPL_ORDER:
            impl_subset = subset[subset["implementation"] == implementation].copy()
            if impl_subset.empty:
                continue
            impl_subset = impl_subset.sort_values("threads")
            ax.plot(
                impl_subset["threads"],
                impl_subset[value_col],
                marker="o",
                linewidth=2.0,
                color=IMPL_COLORS.get(implementation),
                label=implementation,
            )
        if add_reference_line is not None:
            ax.axhline(add_reference_line, color="#666666", linestyle="--", linewidth=1.0, alpha=0.7)
        ax.set_title(f"{ball} balls", fontsize=11)
        ax.set_xlabel("Threads")
        ax.set_ylabel(ylabel)
        ax.set_xticks(sorted(set(int(v) for v in subset["threads"].tolist())))
        ax.grid(True, alpha=0.25)

    for ax in axes_list[len(balls_values):]:
        ax.axis("off")

    handles, labels = axes_list[0].get_legend_handles_labels()
    if handles:
        fig.legend(handles, labels, loc="upper center", ncol=len(handles), frameon=False)
    save_figure(fig, output_file)


def plot_sync_overhead_panels(runs: pd.DataFrame, output_file: Path) -> None:
    required = {
        "balls",
        "implementation",
        "threads",
        "status",
        "syncTimeMillis",
        "aggregationTimeMillis",
        "taskSubmissionTimeMillis",
        "joinOrFutureWaitMillis",
        "elapsedMillis",
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
        title="Synchronization Overhead vs Thread Count",
    )


def plot_gui_latency(gui: pd.DataFrame, output_file: Path) -> None:
    required = {"balls", "implementation", "meanUpdateLatencyMillis", "maxUpdateLatencyMillis"}
    missing = required - set(gui.columns)
    if missing:
        raise ValueError(f"missing required columns for {output_file.name}: {sorted(missing)}")

    fig, ax = plt.subplots(figsize=(10, 6), constrained_layout=True)
    fig.suptitle("GUI Latency vs Balls", fontsize=15, fontweight="bold")

    for implementation in IMPL_ORDER:
        subset = gui[gui["implementation"] == implementation].copy()
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

    ax.set_xlabel("Balls")
    ax.set_ylabel("Mean update latency (ms)")
    ax.set_xticks(BALL_ORDER)
    ax.grid(True, alpha=0.25)
    ax.legend(frameon=False)
    save_figure(fig, output_file)


def save_figure(fig: plt.Figure, output_file: Path) -> None:
    fig.savefig(output_file, dpi=180)
    plt.close(fig)


if __name__ == "__main__":
    main()
