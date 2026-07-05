#!/usr/bin/env python3
"""Generate report-ready charts from benchmark CSV files."""

from __future__ import annotations

import argparse
import base64
import math
import shutil
import sys
import textwrap
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
            "svg.fonttype": "none",
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
    """Parses command-line arguments for the benchmark plotting script.

    Specifies the input directory for results, the output directory for charts,
    and the rendering profile (full vs. speedup).
    """
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
    parser.add_argument(
        "--profile",
        choices=("full", "speedup"),
        default="full",
        help="Chart profile to render. The speedup profile keeps only the core sequential comparisons.",
    )
    return parser.parse_args()


def main() -> None:
    """Main execution function that parses arguments and delegates rendering based on detected CSV files."""
    args = parse_args()
    input_dir = args.input_dir
    output_dir = args.output_dir
    reset_chart_output(output_dir)

    if (input_dir / "aggregated-results.csv").exists():
        if (input_dir / "aggregated-scalability-results.csv").exists():
            render_new_layout(input_dir, output_dir, profile=args.profile)
        else:
            render_speedup_layout(input_dir, output_dir)
    elif (input_dir / "benchmark-summary.csv").exists():
        render_legacy_layout(input_dir, output_dir)
    else:
        raise FileNotFoundError(
            f"missing benchmark CSV layout in {input_dir}; expected either aggregated-results.csv or benchmark-summary.csv"
        )

    print(f"charts_written output_dir={output_dir}")


def reset_chart_output(output_dir: Path) -> None:
    """Safely cleans up the output directory by removing existing files/directories and recreating it empty."""
    if output_dir.exists():
        for child in output_dir.iterdir():
            if child.is_dir():
                shutil.rmtree(child)
            else:
                child.unlink()
    output_dir.mkdir(parents=True, exist_ok=True)


def render_new_layout(input_dir: Path, output_dir: Path, profile: str = "full") -> None:
    """Renders charts from the new CSV structure, including scalability and speedups.

    Reads results, checks matplotlib availability, and plots execution time, throughput,
    speedup, and coordination overhead metrics.
    """
    headless = read_csv(input_dir / "aggregated-results.csv", required=True)
    speedup = read_csv(input_dir / "speedup-results.csv", required=True)
    scalability = read_csv(input_dir / "aggregated-scalability-results.csv", required=True)
    worker_speedup = read_csv(input_dir / "speedup-by-worker-count.csv", required=False)
    gui_path = input_dir / "aggregated-gui-results.csv"
    gui = read_csv(gui_path, required=False)
    environment = read_csv(input_dir / "environment.csv", required=False)
    chart_context = build_chart_context(environment)

    if profile == "speedup":
        render_speedup_profile_layout(headless, speedup, scalability, worker_speedup, output_dir, chart_context)
        return

    charts = [
        (headless, output_dir / "execution-time-vs-balls", prefer_column(headless, "medianElapsedMs", "meanElapsedMs"), "Median execution time (ms)", "Execution time vs number of balls", plot_metric_by_ball),
        (speedup, output_dir / "speedup-vs-balls", "speedup", "Speedup", "Speedup vs number of balls", plot_metric_by_ball),
        (headless, output_dir / "throughput-vs-balls", prefer_column(headless, "medianThroughput", "meanThroughput"), "Median throughput (steps/s)", "Throughput vs number of balls", plot_metric_by_ball),
        (scalability, output_dir / "scalability-elapsed-time-vs-workers", prefer_column(scalability, "medianElapsedMs", "meanElapsedMs"), "Elapsed time (ms)", "Scalability elapsed time vs worker threads", plot_worker_panels),
        (scalability, output_dir / "scalability-throughput-vs-workers", prefer_column(scalability, "medianThroughput", "meanThroughput"), "Throughput (steps/s)", "Scalability throughput vs worker threads", plot_worker_panels),
        (scalability, output_dir / "coordination-overhead-vs-workers", prefer_column(scalability, "medianCoordinationMs", "meanCoordinationMs"), "Coordination time (ms)", "Coordination overhead vs worker threads", plot_worker_panels),
    ]
    if gui_path.exists() and not gui.empty:
        charts.extend([
            (gui, output_dir / "gui-frame-time-vs-balls", prefer_column(gui, "medianFrameMs", "meanFrameMs", "avgFrameMs"), "Median frame time (ms)", "GUI frame time vs number of balls", plot_metric_by_ball),
            (gui, output_dir / "gui-fps-vs-balls", prefer_column(gui, "medianFps", "meanFps", "avgFps"), "Median frames per second", "GUI FPS vs number of balls", plot_metric_by_ball),
        ])

    for df, output_stem, value_col, ylabel, title, plotter in charts:
        if plt is not None:
            if plotter is plot_metric_by_ball:
                plot_metric_by_ball(df, output_stem.with_suffix(".png"), value_col, ylabel, title, chart_context)
            else:
                plot_worker_panels(df, output_stem.with_suffix(".png"), value_col, ylabel, title, chart_context=chart_context)
        else:
            write_placeholder_pair(output_stem, title)

    if not worker_speedup.empty:
        worker_speedup_plot = worker_speedup.rename(columns={"engine_name": "implementation"})
        if plt is not None:
            plot_worker_panels(
                worker_speedup_plot,
                output_dir / "speedup-vs-workers.png",
                value_col="speedup_vs_sequential",
                ylabel="Speedup",
                title="Speedup vs worker count by implementation",
                x_col="worker_count",
                chart_context=chart_context,
            )
        else:
            write_placeholder_pair(output_dir / "speedup-vs-workers", "Speedup vs worker count by implementation")

    thread_pool_speedup = build_thread_pool_speedup(scalability, x_col="workers", value_col="meanElapsedMs")
    if not thread_pool_speedup.empty:
        if plt is not None:
            plot_thread_pool_speedup_panels(
                thread_pool_speedup,
                output_dir / "speedup-vs-thread-pool.png",
                x_col="workers",
                ylabel="Speedup",
                title="Task-based speedup vs threads/pool",
                chart_context=chart_context,
            )
        else:
            write_placeholder_pair(output_dir / "speedup-vs-thread-pool", "Task-based speedup vs threads/pool")


def render_speedup_profile_layout(
    headless: pd.DataFrame,
    speedup: pd.DataFrame,
    scalability: pd.DataFrame,
    worker_speedup: pd.DataFrame,
    output_dir: Path,
    chart_context: str | None = None,
) -> None:
    if plt is not None:
        plot_metric_by_ball(
            speedup,
            output_dir / "speedup-vs-balls.png",
            value_col="speedup",
            ylabel="Speedup",
            title="Speedup vs number of balls",
            chart_context=chart_context,
        )
    else:
        write_placeholder_pair(output_dir / "speedup-vs-balls", "Speedup vs number of balls")

    worker_speedup = build_worker_speedup_from_scalability(scalability)
    if not worker_speedup.empty:
        if plt is not None:
            plot_worker_panels(
                worker_speedup,
                output_dir / "speedup-vs-workers.png",
                value_col="speedup",
                ylabel="Speedup",
                title="Speedup vs worker count by implementation",
                x_col="workers",
                chart_context=chart_context,
            )
        else:
            write_placeholder_pair(output_dir / "speedup-vs-workers", "Speedup vs worker count by implementation")


def render_speedup_layout(input_dir: Path, output_dir: Path) -> None:
    speedup = read_csv(input_dir / "speedup-results.csv", required=True)
    environment = read_csv(input_dir / "environment.csv", required=False)
    chart_context = build_chart_context(environment)

    if plt is not None:
        plot_metric_by_ball(
            speedup,
            output_dir / "speedup-vs-balls.png",
            value_col="speedup",
            ylabel="Speedup",
            title="Speedup vs number of balls",
            chart_context=chart_context,
        )
    else:
        write_placeholder_pair(output_dir / "speedup-vs-balls", "Speedup vs number of balls")


def render_legacy_layout(input_dir: Path, output_dir: Path) -> None:
    summary = read_csv(input_dir / "benchmark-summary.csv", required=True)
    speedup = read_csv(input_dir / "speedup-table.csv", required=True)
    runs = read_csv(input_dir / "benchmark-runs.csv", required=True)
    gui = read_csv(input_dir / "gui-responsiveness.csv", required=False)
    environment = read_csv(input_dir / "environment.csv", required=False)
    chart_context = build_chart_context(environment)

    if plt is not None:
        plot_metric_by_ball(
            summary,
            output_dir / "execution-time-vs-balls.png",
            value_col=prefer_column(summary, "medianMillis", "meanMillis"),
            ylabel="Median execution time (ms)",
            title="Execution time vs number of balls",
            chart_context=chart_context,
        )
        plot_metric_by_ball(
            speedup,
            output_dir / "speedup-vs-balls.png",
            value_col="speedup",
            ylabel="Speedup",
            title="Speedup vs number of balls",
            chart_context=chart_context,
        )
        plot_metric_by_ball(
            summary,
            output_dir / "throughput-vs-balls.png",
            value_col=prefer_column(summary, "medianThroughput", "meanThroughput"),
            ylabel="Median throughput (steps/s)",
            title="Throughput vs number of balls",
            chart_context=chart_context,
        )
        plot_worker_panels(
            summary,
            output_dir / "scalability-elapsed-time-vs-workers.png",
            value_col=prefer_column(summary, "medianMillis", "meanMillis"),
            ylabel="Elapsed time (ms)",
            title="Scalability elapsed time vs worker threads",
            x_col="threads",
            chart_context=chart_context,
        )
        plot_worker_panels(
            summary,
            output_dir / "scalability-throughput-vs-workers.png",
            value_col=prefer_column(summary, "medianThroughput", "meanThroughput"),
            ylabel="Throughput (steps/s)",
            title="Scalability throughput vs worker threads",
            x_col="threads",
            chart_context=chart_context,
        )
        plot_coordination_overhead_panels(
            runs,
            output_dir / "coordination-overhead-vs-workers.png",
            chart_context=chart_context,
        )
        thread_pool_speedup = build_thread_pool_speedup(summary, x_col="threads", value_col="meanMillis")
        if not thread_pool_speedup.empty:
            plot_thread_pool_speedup_panels(
                thread_pool_speedup,
                output_dir / "speedup-vs-thread-pool.png",
                x_col="threads",
                ylabel="Speedup",
                title="Task-based speedup vs threads/pool",
                chart_context=chart_context,
            )
        if not gui.empty and should_plot_gui_latency(gui):
            plot_metric_by_ball(
                gui,
                output_dir / "gui-frame-time-vs-balls.png",
                value_col=prefer_column(gui, "medianUpdateLatencyMillis", "meanUpdateLatencyMillis"),
                ylabel="Median frame time (ms)",
                title="GUI frame time vs number of balls",
                chart_context=chart_context,
            )
            plot_metric_by_ball(
                gui,
                output_dir / "gui-fps-vs-balls.png",
                value_col="updateRatePerSecond",
                ylabel="Frames per second",
                title="GUI FPS vs number of balls",
                chart_context=chart_context,
            )
    else:
        fallback_plot_metric_by_ball(
            summary,
            output_dir / "execution-time-vs-balls.png",
            value_col=prefer_column(summary, "medianMillis", "meanMillis"),
        )
        fallback_plot_metric_by_ball(
            speedup,
            output_dir / "speedup-vs-balls.png",
            value_col="speedup",
        )
        fallback_plot_metric_by_ball(
            summary,
            output_dir / "throughput-vs-balls.png",
            value_col=prefer_column(summary, "medianThroughput", "meanThroughput"),
        )
        fallback_plot_thread_metric_panels(
            summary,
            output_dir / "scalability-elapsed-time-vs-workers.png",
            value_col="meanMillis",
            x_col="threads",
        )
        fallback_plot_thread_metric_panels(
            summary,
            output_dir / "scalability-throughput-vs-workers.png",
            value_col="meanThroughput",
            x_col="threads",
        )
        fallback_plot_coordination_overhead_panels(
            runs,
            output_dir / "coordination-overhead-vs-workers.png",
        )
        thread_pool_speedup = build_thread_pool_speedup(summary, x_col="threads", value_col="meanMillis")
        if not thread_pool_speedup.empty:
            fallback_plot_thread_pool_speedup_panels(
                thread_pool_speedup,
                output_dir / "speedup-vs-thread-pool.png",
            )
        if not gui.empty and should_plot_gui_latency(gui):
            fallback_plot_metric_by_ball(
                gui,
                output_dir / "gui-frame-time-vs-balls.png",
                value_col=prefer_column(gui, "medianUpdateLatencyMillis", "meanUpdateLatencyMillis"),
            )
            fallback_plot_metric_by_ball(
                gui,
                output_dir / "gui-fps-vs-balls.png",
                value_col="updateRatePerSecond",
            )


def read_csv(path: Path, required: bool = False) -> pd.DataFrame:
    if not path.exists():
        if required:
            raise FileNotFoundError(f"missing required benchmark CSV: {path}")
        return pd.DataFrame()
    return pd.read_csv(path)


def plot_metric_by_ball(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
    ylabel: str,
    title: str,
    chart_context: str | None = None,
) -> None:
    required = {"balls", "implementation", value_col}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"missing required columns for {output_file.name}: {sorted(missing)}")

    fig, ax = plt.subplots(figsize=(14.0, 8.8), constrained_layout=False)
    fig.subplots_adjust(top=0.78, bottom=0.25, left=0.09, right=0.98)
    fig.suptitle(title, fontsize=16, fontweight="bold", y=0.96)
    ax.set_title("Central tendency per implementation across measured scenarios", fontsize=10, pad=14)
    add_chart_context(fig, chart_context)

    for implementation in IMPL_ORDER:
        subset = df[df["implementation"].astype(str).str.lower() == implementation].copy()
        if subset.empty:
            continue
        grouped = subset.groupby("balls", as_index=False)[value_col].median().sort_values("balls")
        ax.plot(
            grouped["balls"],
            grouped[value_col],
            marker="o",
            linewidth=2.2,
            color=IMPL_COLORS.get(implementation),
            label=implementation,
        )

    ax.set_xlabel("Number of balls")
    ax.set_ylabel(ylabel)
    ax.set_xticks(_xticks(df["balls"]))
    ax.grid(True, alpha=0.25)
    handles, labels = ax.get_legend_handles_labels()
    if handles:
        fig.legend(
            handles,
            labels,
            loc="upper center",
            bbox_to_anchor=(0.5, 0.885),
            ncol=max(1, len(handles)),
            frameon=False,
        )
    save_figure(fig, output_file)


def plot_worker_panels(
    df: pd.DataFrame,
    output_file: Path,
        value_col: str,
        ylabel: str,
        title: str,
        x_col: str = "workers",
        implementations: tuple[str, ...] = ("threads", "executor"),
    chart_context: str | None = None,
) -> None:
    required = {"balls", "implementation", x_col, value_col}
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
        figsize=(7.0 * cols, 5.4 * rows),
        sharex=False,
        sharey=True,
        constrained_layout=False,
    )
    fig.subplots_adjust(top=0.80, bottom=0.25, left=0.06, right=0.98, hspace=0.34, wspace=0.22)
    fig.suptitle(title, fontsize=16, fontweight="bold", y=0.97)
    add_chart_context(fig, chart_context)
    axes_list = axes.flatten() if hasattr(axes, "flatten") else [axes]

    for index, ball in enumerate(balls_values):
        ax = axes_list[index]
        subset = df[df["balls"] == ball]
        for implementation in implementations:
            impl_subset = subset[subset["implementation"].astype(str).str.lower() == implementation].copy()
            if impl_subset.empty:
                continue
            impl_subset = impl_subset.sort_values(x_col)
            ax.plot(
                impl_subset[x_col],
                impl_subset[value_col],
                marker="o",
                linewidth=2.0,
                color=IMPL_COLORS.get(implementation),
                label=implementation,
            )
        ax.set_title(f"{ball} balls", fontsize=11)
        if x_col in {"workers", "worker_count"}:
            ax.set_xlabel("Worker threads")
        else:
            ax.set_xlabel(x_col)
        ax.set_ylabel(ylabel)
        ax.set_xticks(_xticks(subset[x_col]))
        ax.grid(True, alpha=0.25)

    for ax in axes_list[len(balls_values):]:
        ax.axis("off")

    handles, labels = axes_list[0].get_legend_handles_labels()
    if handles:
        fig.legend(
            handles,
            labels,
            loc="upper center",
            bbox_to_anchor=(0.5, 0.90),
            ncol=max(1, len(handles)),
            frameon=False,
        )
    save_figure(fig, output_file)


def plot_thread_metric_panels(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
    ylabel: str,
    title: str,
    x_col: str = "workers",
    implementations: tuple[str, ...] = ("threads", "executor"),
    chart_context: str | None = None,
) -> None:
    plot_worker_panels(
        df,
        output_file,
        value_col,
        ylabel,
        title,
        x_col=x_col,
        implementations=implementations,
        chart_context=chart_context,
    )


def build_thread_pool_speedup(df: pd.DataFrame, x_col: str, value_col: str) -> pd.DataFrame:
    required = {"balls", "implementation", x_col, "steps", "seed", value_col}
    missing = required - set(df.columns)
    if missing:
        return pd.DataFrame()

    baseline = df[df["implementation"].astype(str).str.lower() == "threads"].copy()
    executor = df[df["implementation"].astype(str).str.lower() == "executor"].copy()
    if baseline.empty or executor.empty:
        return pd.DataFrame()

    merged = baseline.merge(
        executor,
        on=["balls", x_col, "steps", "seed"],
        suffixes=("_threads", "_executor"),
    )
    if merged.empty:
        return pd.DataFrame()

    merged["speedup"] = merged[f"{value_col}_threads"] / merged[f"{value_col}_executor"]
    return merged.loc[:, ["balls", x_col, "speedup"]].copy()


def build_worker_speedup_from_scalability(df: pd.DataFrame) -> pd.DataFrame:
    required = {"balls", "implementation", "workers", "steps", "seed", "meanElapsedMs"}
    missing = required - set(df.columns)
    if missing:
        return pd.DataFrame()

    baseline = df[df["implementation"].astype(str).str.lower() == "sequential"].copy()
    if baseline.empty:
        return pd.DataFrame()

    rows = []
    for implementation in ("threads", "executor"):
        impl_rows = df[df["implementation"].astype(str).str.lower() == implementation].copy()
        if impl_rows.empty:
            continue
        merged = impl_rows.merge(
            baseline,
            on=["balls", "steps", "seed"],
            suffixes=("_impl", "_seq"),
        )
        if merged.empty:
            continue
        merged["implementation"] = implementation
        merged["speedup"] = merged["meanElapsedMs_seq"] / merged["meanElapsedMs_impl"]
        rows.append(merged.loc[:, ["balls", "workers_impl", "implementation", "speedup"]].rename(columns={"workers_impl": "workers"}))

    if not rows:
        return pd.DataFrame()

    result = pd.concat(rows, ignore_index=True)
    return result.loc[:, ["balls", "workers", "implementation", "speedup"]].copy()


def plot_thread_pool_speedup_panels(
    df: pd.DataFrame,
    output_file: Path,
    x_col: str,
    ylabel: str,
    title: str,
    chart_context: str | None = None,
) -> None:
    required = {"balls", x_col, "speedup"}
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
        figsize=(7.0 * cols, 5.4 * rows),
        sharex=False,
        sharey=True,
        constrained_layout=False,
    )
    fig.subplots_adjust(top=0.80, bottom=0.25, left=0.06, right=0.98, hspace=0.34, wspace=0.22)
    fig.suptitle(title, fontsize=16, fontweight="bold", y=0.97)
    add_chart_context(fig, chart_context)
    axes_list = axes.flatten() if hasattr(axes, "flatten") else [axes]

    for index, ball in enumerate(balls_values):
        ax = axes_list[index]
        subset = df[df["balls"] == ball].copy()
        grouped = subset.groupby(x_col, as_index=False)["speedup"].median().sort_values(x_col)
        ax.plot(
            grouped[x_col],
            grouped["speedup"],
            marker="o",
            linewidth=2.0,
            color=IMPL_COLORS.get("executor"),
        )
        ax.set_title(f"{ball} balls", fontsize=11)
        if x_col in {"workers", "worker_count"}:
            ax.set_xlabel("Worker threads")
        else:
            ax.set_xlabel(x_col)
        ax.set_ylabel(ylabel)
        ax.set_xticks(_xticks(subset[x_col]))
        ax.grid(True, alpha=0.25)

    for ax in axes_list[len(balls_values):]:
        ax.axis("off")

    save_figure(fig, output_file)


def plot_coordination_overhead_panels(
    runs: pd.DataFrame,
    output_file: Path,
    chart_context: str | None = None,
) -> None:
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
    grouped = filtered.groupby(["balls", "implementation", "threads"], as_index=False)["coordinationMillis"].median()
    plot_thread_metric_panels(
        grouped,
        output_file,
        value_col="coordinationMillis",
        ylabel="Coordination time (ms)",
        title="Coordination overhead vs worker threads",
        x_col="threads",
        implementations=("threads", "executor"),
        chart_context=chart_context,
    )


def fallback_plot_metric_by_ball(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
) -> None:
    if plt is not None:
        raise RuntimeError("fallback_plot_metric_by_ball should only be used without matplotlib")
    write_placeholder_pair(output_file.with_suffix(""), output_file.stem)


def fallback_plot_thread_metric_panels(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
    x_col: str = "workers",
    implementations: tuple[str, ...] = ("threads", "executor"),
) -> None:
    if plt is not None:
        raise RuntimeError("fallback_plot_thread_metric_panels should only be used without matplotlib")
    write_placeholder_pair(output_file.with_suffix(""), output_file.stem)


def fallback_plot_thread_pool_speedup_panels(df: pd.DataFrame, output_file: Path) -> None:
    if plt is not None:
        raise RuntimeError("fallback_plot_thread_pool_speedup_panels should only be used without matplotlib")
    write_placeholder_pair(output_file.with_suffix(""), output_file.stem)


def fallback_plot_coordination_overhead_panels(runs: pd.DataFrame, output_file: Path) -> None:
    if plt is not None:
        raise RuntimeError("fallback_plot_coordination_overhead_panels should only be used without matplotlib")
    write_placeholder_pair(output_file.with_suffix(""), output_file.stem)


def should_plot_gui_latency(gui: pd.DataFrame) -> bool:
    latency_col = optional_column(gui, "medianUpdateLatencyMillis", "meanUpdateLatencyMillis")
    if latency_col is None:
        return False
    required = {"balls", "implementation", "maxUpdateLatencyMillis", "updateRatePerSecond"}
    required.add(latency_col)
    missing = required - set(gui.columns)
    if missing:
        return False
    return not gui.empty


def plot_gui_latency(gui: pd.DataFrame, output_file: Path) -> None:
    latency_col = prefer_column(gui, "medianUpdateLatencyMillis", "meanUpdateLatencyMillis")
    required = {"balls", "implementation", "maxUpdateLatencyMillis", latency_col}
    missing = required - set(gui.columns)
    if missing:
        raise ValueError(f"missing required columns for {output_file.name}: {sorted(missing)}")

    fig, ax = plt.subplots(figsize=(10.5, 6.2), constrained_layout=True)
    fig.suptitle("GUI update latency vs number of balls", fontsize=16, fontweight="bold", y=0.98)

    for implementation in IMPL_ORDER:
        subset = gui[gui["implementation"].astype(str).str.lower() == implementation].copy()
        if subset.empty:
            continue
        grouped = subset.groupby("balls", as_index=False)[[latency_col, "maxUpdateLatencyMillis"]].median()
        grouped = grouped.sort_values("balls")
        lower = grouped[latency_col].to_numpy() * 0.0
        upper = (grouped["maxUpdateLatencyMillis"] - grouped[latency_col]).clip(lower=0.0).to_numpy()
        ax.errorbar(
            grouped["balls"],
            grouped[latency_col],
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


def build_chart_context(environment: pd.DataFrame) -> str | None:
    if environment.empty:
        return None
    row = environment.iloc[0].fillna("")
    parts: list[str] = []

    cpu = _clean_text(row.get("cpuModel", ""))
    physical = _safe_int(row.get("physicalCores"))
    logical = _safe_int(row.get("logicalCpuCount"))
    available = _safe_int(row.get("availableProcessors"))
    ram_bytes = _safe_int(row.get("totalPhysicalMemoryBytes"))
    os_name = _clean_text(row.get("osName", ""))
    jvm_name = _clean_text(row.get("jvmName", ""))
    jvm_version = _clean_text(row.get("jvmVersion", ""))

    if cpu:
        parts.append(f"CPU: {cpu}")
    cpu_counts: list[str] = []
    if physical is not None:
        cpu_counts.append(f"{physical} physical cores")
    if logical is not None:
        cpu_counts.append(f"{logical} logical threads")
    if available is not None:
        cpu_counts.append(f"JVM available={available}")
    if cpu_counts:
        parts.append(", ".join(cpu_counts))
    if ram_bytes is not None:
        parts.append(f"RAM: {ram_bytes / (1024 ** 3):.1f} GiB")
    if jvm_version or jvm_name:
        parts.append(f"JVM: {jvm_version or jvm_name}")
    if os_name:
        parts.append(f"OS: {os_name}")
    return " | ".join(parts) if parts else None


def add_chart_context(fig: plt.Figure, chart_context: str | None) -> None:
    if not chart_context:
        return
    wrapped = "\n".join(textwrap.wrap(chart_context, width=150))
    fig.text(
        0.5,
        0.03,
        wrapped,
        ha="center",
        va="bottom",
        fontsize=8.5,
        color="#444444",
        bbox={
            "boxstyle": "round,pad=0.35",
            "facecolor": "#f7f7f7",
            "edgecolor": "#d9d9d9",
            "alpha": 0.95,
        },
    )


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


def prefer_column(df: pd.DataFrame, *candidates: str) -> str:
    for candidate in candidates:
        if candidate in df.columns:
            return candidate
    raise ValueError(f"missing expected columns; tried {candidates}")


def optional_column(df: pd.DataFrame, *candidates: str) -> str | None:
    for candidate in candidates:
        if candidate in df.columns:
            return candidate
    return None


def _safe_int(value: object) -> int | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text or text.lower() == "nan":
        return None
    try:
        return int(float(text))
    except ValueError:
        return None


def _clean_text(value: object) -> str:
    text = str(value).strip()
    if not text or text.lower() == "nan":
        return ""
    return text


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"chart_generation_failed message={exc}", file=sys.stderr)
        raise
