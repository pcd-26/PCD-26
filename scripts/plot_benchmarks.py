#!/usr/bin/env python3
"""Generate report-ready charts from benchmark CSV files."""

from __future__ import annotations

import argparse
import math
import struct
import zlib
from pathlib import Path
from typing import Iterable, Sequence

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
THREAD_ORDER = [1, 2, 4, 8]
HAS_MATPLOTLIB = plt is not None


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
    gui = read_csv(input_dir / "gui-responsiveness.csv", required=False)

    if HAS_MATPLOTLIB:
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
        if gui.empty:
            write_placeholder_chart(
                output_dir / "gui-latency-vs-balls.png",
                "GUI Latency vs Balls",
            )
        else:
            plot_gui_latency(
                gui,
                output_dir / "gui-latency-vs-balls.png",
            )
    else:
        fallback_plot_best_by_ball(
            summary,
            output_dir / "execution-time-vs-balls.png",
            value_col="meanMillis",
            best_agg="min",
        )
        fallback_plot_best_by_ball(
            summary,
            output_dir / "throughput-vs-balls.png",
            value_col="meanThroughput",
            best_agg="max",
        )
        fallback_plot_thread_metric_panels(
            speedup,
            output_dir / "speedup-vs-thread-count.png",
            value_col="speedup",
            add_reference_line=1.0,
        )
        fallback_plot_thread_metric_panels(
            efficiency,
            output_dir / "efficiency-vs-thread-count.png",
            value_col="efficiency",
            add_reference_line=1.0,
        )
        fallback_plot_thread_metric_panels(
            summary,
            output_dir / "cpu-utilization-vs-thread-count.png",
            value_col="meanCpuUtilizationPercent",
        )
        fallback_plot_sync_overhead_panels(
            runs,
            output_dir / "synchronization-overhead-vs-thread-count.png",
        )
        if gui.empty:
            write_placeholder_chart(
                output_dir / "gui-latency-vs-balls.png",
                "GUI Latency vs Balls",
            )
        else:
            fallback_plot_gui_latency(
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


def write_placeholder_chart(output_file: Path, title: str) -> None:
    canvas = Canvas(1200, 800)
    canvas.fill((255, 255, 255))
    canvas.frame_title(title)
    canvas.rect(140, 140, 1060, 660, (210, 210, 210))
    canvas.line(220, 620, 1020, 620, (130, 130, 130))
    canvas.line(220, 620, 220, 220, (130, 130, 130))
    canvas.circle(380, 500, 14, (31, 119, 180))
    canvas.circle(600, 420, 14, (214, 39, 40))
    canvas.circle(820, 330, 14, (44, 160, 44))
    canvas.save(output_file)


def fallback_plot_best_by_ball(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
    best_agg: str,
) -> None:
    grouped = []
    for implementation in IMPL_ORDER:
        subset = df[df["implementation"] == implementation].copy()
        if subset.empty:
            continue
        if best_agg == "min":
            idx = subset.groupby("balls")[value_col].idxmin()
        else:
            idx = subset.groupby("balls")[value_col].idxmax()
        best = subset.loc[idx].sort_values("balls")
        grouped.append((implementation, best["balls"].tolist(), best[value_col].tolist()))
    render_simple_line_chart(
        output_file,
        title="Execution Time vs Balls" if value_col == "meanMillis" else "Throughput vs Balls",
        x_label="Balls",
        y_label="Value",
        series=grouped,
    )


def fallback_plot_thread_metric_panels(
    df: pd.DataFrame,
    output_file: Path,
    value_col: str,
    add_reference_line: float | None = None,
) -> None:
    balls_values = [ball for ball in BALL_ORDER if ball in set(df["balls"].tolist())]
    if not balls_values:
        balls_values = sorted(df["balls"].dropna().astype(int).unique().tolist())
    panels = []
    for ball in balls_values:
        subset = df[df["balls"] == ball]
        series = []
        for implementation in IMPL_ORDER:
            impl_subset = subset[subset["implementation"] == implementation].copy()
            if impl_subset.empty:
                continue
            impl_subset = impl_subset.sort_values("threads")
            series.append((implementation, impl_subset["threads"].tolist(), impl_subset[value_col].tolist()))
        panels.append((f"{ball} balls", series))
    render_simple_panel_chart(
        output_file,
        title="Metric vs Thread Count",
        panels=panels,
        x_label="Threads",
        y_label="Value",
        add_reference_line=add_reference_line,
    )


def fallback_plot_sync_overhead_panels(runs: pd.DataFrame, output_file: Path) -> None:
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
    fallback_plot_thread_metric_panels(grouped, output_file, value_col="coordinationMillis")


def fallback_plot_gui_latency(gui: pd.DataFrame, output_file: Path) -> None:
    required = {"balls", "implementation", "meanUpdateLatencyMillis", "maxUpdateLatencyMillis"}
    missing = required - set(gui.columns)
    if missing:
        raise ValueError(f"missing required columns for {output_file.name}: {sorted(missing)}")

    panels = []
    for implementation in IMPL_ORDER:
        subset = gui[gui["implementation"] == implementation].copy()
        if subset.empty:
            continue
        grouped = subset.groupby("balls", as_index=False)[["meanUpdateLatencyMillis", "maxUpdateLatencyMillis"]].mean()
        grouped = grouped.sort_values("balls")
        panels.append(
            (
                implementation,
                grouped["balls"].tolist(),
                grouped["meanUpdateLatencyMillis"].tolist(),
            )
        )
    render_simple_line_chart(
        output_file,
        title="GUI Latency vs Balls",
        x_label="Balls",
        y_label="Mean update latency (ms)",
        series=panels,
    )


def render_simple_line_chart(
    output_file: Path,
    title: str,
    x_label: str,
    y_label: str,
    series: list[tuple[str, list[int | float], list[int | float]]],
) -> None:
    canvas = Canvas(1200, 800)
    canvas.fill((255, 255, 255))
    canvas.frame_title(title)
    _draw_chart_area(canvas, 90, 80, 1120, 700, x_label, y_label, series, None)
    canvas.save(output_file)


def render_simple_panel_chart(
    output_file: Path,
    title: str,
    panels: list[tuple[str, list[tuple[str, list[int | float], list[int | float]]]]],
    x_label: str,
    y_label: str,
    add_reference_line: float | None,
) -> None:
    canvas = Canvas(1600, 1100)
    canvas.fill((255, 255, 255))
    canvas.frame_title(title)

    positions = [
        (80, 120, 470, 430),
        (565, 120, 955, 430),
        (1050, 120, 1440, 430),
        (80, 585, 470, 895),
        (565, 585, 955, 895),
        (1050, 585, 1440, 895),
    ]
    for index, (panel_title, panel_series) in enumerate(panels[:6]):
        left, top, right, bottom = positions[index]
        canvas.panel_title(panel_title, left, top - 18)
        _draw_chart_area(canvas, left, top, right, bottom, x_label, y_label, panel_series, add_reference_line)
    canvas.save(output_file)


def _draw_chart_area(
    canvas: "Canvas",
    left: int,
    top: int,
    right: int,
    bottom: int,
    x_label: str,
    y_label: str,
    series: list[tuple[str, list[int | float], list[int | float]]] | None = None,
    reference_line: float | None = None,
) -> None:
    series = series or []
    plot_left = left + 35
    plot_top = top + 10
    plot_right = right - 10
    plot_bottom = bottom - 35
    canvas.rect(plot_left, plot_top, plot_right, plot_bottom, (190, 190, 190))

    points = [(x, y) for _, xs, ys in series for x, y in zip(xs, ys)]
    if reference_line is not None:
        points.append((0.0, reference_line))
    if points:
        xs = [float(x) for x, _ in points]
        ys = [float(y) for _, y in points]
        min_x = min(xs)
        max_x = max(xs)
        min_y = min(ys)
        max_y = max(ys)
    else:
        min_x, max_x, min_y, max_y = 0.0, 1.0, 0.0, 1.0

    if math.isclose(min_x, max_x):
        min_x -= 1.0
        max_x += 1.0
    if math.isclose(min_y, max_y):
        min_y -= 1.0
        max_y += 1.0

    def map_x(value: float) -> int:
        ratio = (value - min_x) / (max_x - min_x)
        return int(plot_left + ratio * (plot_right - plot_left))

    def map_y(value: float) -> int:
        ratio = (value - min_y) / (max_y - min_y)
        return int(plot_bottom - ratio * (plot_bottom - plot_top))

    if reference_line is not None:
        y = map_y(reference_line)
        canvas.line(plot_left, y, plot_right, y, (110, 110, 110))

    for index, (_, xs, ys) in enumerate(series):
        color = _hex_to_rgb(IMPL_COLORS.get(IMPL_ORDER[index % len(IMPL_ORDER)], "#000000"))
        if len(xs) >= 2:
            ordered = sorted(zip(xs, ys), key=lambda item: float(item[0]))
            for (x0, y0), (x1, y1) in zip(ordered, ordered[1:]):
                canvas.line(map_x(float(x0)), map_y(float(y0)), map_x(float(x1)), map_y(float(y1)), color)
        for x, y in zip(xs, ys):
            canvas.circle(map_x(float(x)), map_y(float(y)), 4, color)


class Canvas:
    def __init__(self, width: int, height: int) -> None:
        self.width = width
        self.height = height
        self.pixels = bytearray([255, 255, 255] * width * height)

    def fill(self, color: tuple[int, int, int]) -> None:
        r, g, b = color
        self.pixels[:] = bytearray([r, g, b] * self.width * self.height)

    def _set(self, x: int, y: int, color: tuple[int, int, int]) -> None:
        if x < 0 or y < 0 or x >= self.width or y >= self.height:
            return
        index = (y * self.width + x) * 3
        self.pixels[index:index + 3] = bytes(color)

    def line(self, x0: int, y0: int, x1: int, y1: int, color: tuple[int, int, int]) -> None:
        dx = abs(x1 - x0)
        dy = -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        while True:
            self._set(x0, y0, color)
            if x0 == x1 and y0 == y1:
                return
            twice_err = 2 * err
            if twice_err >= dy:
                err += dy
                x0 += sx
            if twice_err <= dx:
                err += dx
                y0 += sy

    def rect(self, left: int, top: int, right: int, bottom: int, color: tuple[int, int, int]) -> None:
        self.line(left, top, right, top, color)
        self.line(right, top, right, bottom, color)
        self.line(right, bottom, left, bottom, color)
        self.line(left, bottom, left, top, color)

    def circle(self, cx: int, cy: int, radius: int, color: tuple[int, int, int]) -> None:
        x = radius
        y = 0
        err = 0
        while x >= y:
            for dx, dy in [
                (x, y),
                (y, x),
                (-y, x),
                (-x, y),
                (-x, -y),
                (-y, -x),
                (y, -x),
                (x, -y),
            ]:
                self._set(cx + dx, cy + dy, color)
            y += 1
            if err <= 0:
                err += 2 * y + 1
            if err > 0:
                x -= 1
                err -= 2 * x + 1

    def frame_title(self, title: str) -> None:
        # Placeholder title marker so the fallback image is still readable enough
        # without depending on any external font libraries.
        self.rect(60, 30, self.width - 60, 60, (230, 230, 230))
        self._stamp_barcode(80, 38, title)

    def panel_title(self, title: str, left: int, top: int) -> None:
        self._stamp_barcode(left, top, title)

    def _stamp_barcode(self, x: int, y: int, text: str) -> None:
        # Encode text as a tiny deterministic pattern. This is intentionally
        # simple: the fallback only needs to create distinct, valid PNG charts
        # when matplotlib is unavailable.
        digest = zlib.crc32(text.encode("utf-8")) & 0xFFFFFFFF
        for index in range(32):
            if (digest >> index) & 1:
                self.rect(x + index * 4, y, x + index * 4 + 2, y + 8, (120, 120, 120))

    def save(self, output_file: Path) -> None:
        raw = bytearray()
        row_width = self.width * 3
        for row in range(self.height):
            raw.append(0)
            start = row * row_width
            raw.extend(self.pixels[start:start + row_width])
        compressed = zlib.compress(bytes(raw), level=9)
        png = b"".join([
            b"\x89PNG\r\n\x1a\n",
            _png_chunk(b"IHDR", struct.pack(">IIBBBBB", self.width, self.height, 8, 2, 0, 0, 0)),
            _png_chunk(b"IDAT", compressed),
            _png_chunk(b"IEND", b""),
        ])
        output_file.write_bytes(png)


def _png_chunk(tag: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def _hex_to_rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[index:index + 2], 16) for index in (0, 2, 4))


if __name__ == "__main__":
    main()
