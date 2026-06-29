from __future__ import annotations

import sys
import tempfile
import unittest
from io import StringIO
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts import run_benchmarks


class RunBenchmarksScriptTest(unittest.TestCase):
    def test_parse_args_defaults_to_full_mode(self) -> None:
        with mock.patch.object(sys, "argv", ["run_benchmarks.py"]):
            config = run_benchmarks.parse_args()

        self.assertEqual(config.mode, "full")

    def test_build_maven_command_targets_assignment_one(self) -> None:
        with mock.patch("scripts.run_benchmarks.resolve_maven_command", return_value="mvn.cmd"):
            command = run_benchmarks.build_maven_command("compile")
        self.assertEqual(
            command,
            ["mvn.cmd", "-f", str(Path(run_benchmarks.ASSIGNMENT_ROOT / "pom.xml")), "clean", "compile"],
        )

    def test_build_pipeline_command_uses_requested_output_dirs(self) -> None:
        results_root = Path("assignment-1/benchmarks/results")
        charts_root = Path("assignment-1/benchmarks/charts")

        with mock.patch("scripts.run_benchmarks.resolve_java_command", return_value="java.exe"):
            command = run_benchmarks.build_pipeline_command(results_root, charts_root)

        self.assertEqual(
            command,
            [
                "java.exe",
                "-cp",
                str(run_benchmarks.ASSIGNMENT_ROOT / "target" / "classes"),
                run_benchmarks.JAVA_MAIN_CLASS,
                "--results-root",
                str(results_root),
                "--charts-root",
                str(charts_root),
            ],
        )

    def test_build_suite_command_uses_requested_mode_and_results_dir(self) -> None:
        results_root = Path("assignment-1/benchmarks/results")

        with mock.patch("scripts.run_benchmarks.resolve_java_command", return_value="java.exe"):
            command = run_benchmarks.build_suite_command("smoke", results_root)

        self.assertEqual(
            command,
            [
                "java.exe",
                "-cp",
                str(run_benchmarks.ASSIGNMENT_ROOT / "target" / "classes"),
                run_benchmarks.SUITE_MAIN_CLASS,
                "--smoke",
                str(results_root),
            ],
        )

    def test_reset_directory_removes_previous_contents(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stale_file = root / "old-chart.png"
            nested_dir = root / "nested"
            nested_dir.mkdir()
            stale_file.write_text("stale", encoding="utf-8")
            (nested_dir / "old-chart.svg").write_text("stale", encoding="utf-8")

            run_benchmarks.reset_directory(root)

            self.assertTrue(root.exists())
            self.assertEqual(list(root.iterdir()), [])

    def test_plot_reset_output_removes_previous_contents(self) -> None:
        from scripts import plot_benchmarks

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stale_file = root / "old-chart.png"
            nested_dir = root / "nested"
            nested_dir.mkdir()
            stale_file.write_text("stale", encoding="utf-8")
            (nested_dir / "old-chart.svg").write_text("stale", encoding="utf-8")

            plot_benchmarks.reset_chart_output(root)

            self.assertTrue(root.exists())
            self.assertEqual(list(root.iterdir()), [])

    def test_resolve_maven_command_prefers_assignment_wrapper(self) -> None:
        with mock.patch.object(Path, "exists", autospec=True) as exists_mock:
            def fake_exists(path: Path) -> bool:
                return path == run_benchmarks.ASSIGNMENT_ROOT / "mvnw.cmd"

            exists_mock.side_effect = fake_exists
            resolved = run_benchmarks.resolve_maven_command()

        self.assertEqual(resolved, str(run_benchmarks.ASSIGNMENT_ROOT / "mvnw.cmd"))

    def test_resolve_maven_command_uses_path_lookup(self) -> None:
        with mock.patch("scripts.run_benchmarks.shutil.which", side_effect=lambda cmd: "C:/tools/mvn.cmd" if cmd == "mvn.cmd" else None):
            with mock.patch.object(Path, "exists", return_value=False):
                resolved = run_benchmarks.resolve_maven_command()

        self.assertEqual(resolved, "C:/tools/mvn.cmd")

    def test_resolve_maven_command_raises_clear_error_when_missing(self) -> None:
        with mock.patch("scripts.run_benchmarks.shutil.which", return_value=None):
            with mock.patch.object(Path, "exists", return_value=False):
                with self.assertRaisesRegex(RuntimeError, "Maven executable not found"):
                    run_benchmarks.resolve_maven_command()

    def test_print_step_emits_live_progress_prefix(self) -> None:
        stream = StringIO()
        with mock.patch("sys.stdout", stream):
            run_benchmarks.print_step("pipeline-start mode=full")

        self.assertIn("[benchmark-runner] pipeline-start mode=full", stream.getvalue())


if __name__ == "__main__":
    unittest.main()
