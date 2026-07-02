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
                    "balls,workers,seed,implementation,meanSequentialMs,meanParallelMs,speedup",
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


def _write_csv(path: Path, lines: list[str]) -> None:
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
