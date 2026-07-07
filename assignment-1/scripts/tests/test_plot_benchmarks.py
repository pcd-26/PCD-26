from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts import plot_benchmarks


class PlotBenchmarksTest(unittest.TestCase):
    def test_speedup_layout_writes_only_speedup_chart(self) -> None:
        """Verifies that the speedup layout generates only the speedup-vs-balls chart

        when running in a limited speedup layout mode, avoiding rendering extra metrics.
        """
        with tempfile.TemporaryDirectory() as tmp:
            input_dir = Path(tmp) / "results"
            output_dir = Path(tmp) / "charts"
            input_dir.mkdir(parents=True)

            _write_csv(
                input_dir / "aggregated-results.csv",
                [
                    "implementation,balls,workers,steps,seed,meanElapsedMs,medianElapsedMs,stdElapsedMs,meanThroughput,medianThroughput,stdThroughput,meanCoordinationMs,medianCoordinationMs,stdCoordinationMs,meanCoordinationRatio,medianCoordinationRatio,stdCoordinationRatio,meanTasksSubmitted",
                    "sequential,100,1,600,42,10.000000,10.000000,0.000000,1000.000000,1000.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000",
                ],
            )
            _write_csv(
                input_dir / "speedup-results.csv",
                [
                    "balls,workers,seed,implementation,medianSequentialMs,medianParallelMs,speedup",
                    "100,1,42,sequential,10.000000,10.000000,1.000000",
                ],
            )

            with mock.patch.object(sys, "argv", ["plot_benchmarks.py", "--input-dir", str(input_dir), "--output-dir", str(output_dir)]):
                plot_benchmarks.main()

            self.assertTrue((output_dir / "speedup-vs-balls.png").exists())
            self.assertTrue((output_dir / "speedup-vs-balls.svg").exists())
            self.assertFalse((output_dir / "execution-time-vs-balls.png").exists())
            self.assertFalse((output_dir / "throughput-vs-balls.png").exists())
            self.assertFalse((output_dir / "scalability-elapsed-time-vs-workers.png").exists())

    def test_speedup_profile_keeps_only_sequential_comparisons(self) -> None:
        """Verifies that the speedup profile correctly limits generated charts

        to speedup-vs-balls and speedup-vs-workers when explicitly using the
        'speedup' profile CLI flag.
        """
        with tempfile.TemporaryDirectory() as tmp:
            input_dir = Path(tmp) / "results"
            output_dir = Path(tmp) / "charts"
            input_dir.mkdir(parents=True)

            _write_csv(
                input_dir / "aggregated-results.csv",
                [
                    "implementation,balls,workers,steps,seed,meanElapsedMs,medianElapsedMs,stdElapsedMs,meanThroughput,medianThroughput,stdThroughput,meanCoordinationMs,medianCoordinationMs,stdCoordinationMs,meanCoordinationRatio,medianCoordinationRatio,stdCoordinationRatio,meanTasksSubmitted",
                    "sequential,100,1,600,42,10.000000,10.000000,0.000000,1000.000000,1000.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000",
                ],
            )
            _write_csv(
                input_dir / "speedup-results.csv",
                [
                    "balls,workers,seed,implementation,medianSequentialMs,medianParallelMs,speedup",
                    "100,1,42,sequential,10.000000,10.000000,1.000000",
                ],
            )
            _write_csv(
                input_dir / "aggregated-scalability-results.csv",
                [
                    "implementation,balls,workers,steps,seed,meanElapsedMs,medianElapsedMs,stdElapsedMs,meanThroughput,medianThroughput,stdThroughput,meanCoordinationMs,medianCoordinationMs,stdCoordinationMs,meanCoordinationRatio,medianCoordinationRatio,stdCoordinationRatio,meanTasksSubmitted",
                    "sequential,100,1,600,42,10.000000,10.000000,0.000000,1000.000000,1000.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000",
                    "threads,100,2,600,42,6.000000,6.000000,0.000000,1666.666667,1666.666667,0.000000,1.000000,1.000000,0.000000,0.100000,0.100000,0.000000,1.000000",
                    "executor,100,2,600,42,7.000000,7.000000,0.000000,1428.571429,1428.571429,0.000000,1.100000,1.100000,0.000000,0.110000,0.110000,0.000000,1.000000",
                ],
            )
            _write_csv(
                input_dir / "speedup-by-worker-count.csv",
                [
                    "engine_name,board_width,board_height,balls,threads,steps,seed,worker_count,speedup_vs_sequential",
                    "threads,3.000000,2.000000,100,1,600,42,1,1.000000",
                    "threads,3.000000,2.000000,100,2,600,42,2,1.666667",
                    "executor,3.000000,2.000000,100,1,600,42,1,1.000000",
                    "executor,3.000000,2.000000,100,2,600,42,2,1.428571",
                ],
            )

            with mock.patch.object(
                sys,
                "argv",
                ["plot_benchmarks.py", "--input-dir", str(input_dir), "--output-dir", str(output_dir), "--profile", "speedup"],
            ):
                plot_benchmarks.main()

            self.assertTrue((output_dir / "speedup-vs-balls.png").exists())
            self.assertTrue((output_dir / "speedup-vs-workers.png").exists())
            self.assertFalse((output_dir / "execution-time-vs-balls.png").exists())
            self.assertFalse((output_dir / "throughput-vs-balls.png").exists())
            self.assertFalse((output_dir / "scalability-elapsed-time-vs-workers.png").exists())
            self.assertFalse((output_dir / "efficiency-vs-workers.png").exists())
            self.assertFalse((output_dir / "speedup-vs-thread-pool.png").exists())


def _write_csv(path: Path, lines: list[str]) -> None:
    """Helper function to write a list of string lines into a CSV file."""
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
