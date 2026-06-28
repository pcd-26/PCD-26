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
