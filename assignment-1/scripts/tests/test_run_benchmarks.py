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
        """Verifies that command line parsing defaults to 'full' mode when no arguments are given."""
        with mock.patch.object(sys, "argv", ["run_benchmarks.py"]):
            config = run_benchmarks.parse_args()

        self.assertEqual(config.mode, "full")

    def test_build_maven_command_targets_assignment_one(self) -> None:
        """Verifies that build_maven_command resolves Maven executable and targets assignment-1/pom.xml."""
        with mock.patch("scripts.run_benchmarks.resolve_maven_command", return_value="mvn.cmd"):
            command = run_benchmarks.build_maven_command("compile")
        self.assertEqual(
            command,
            ["mvn.cmd", "-f", str(Path(run_benchmarks.ASSIGNMENT_ROOT / "pom.xml")), "clean", "compile"],
        )

    def test_build_pipeline_command_uses_requested_output_dirs(self) -> None:
        """Verifies build_pipeline_command constructs Java classpath and parameters correctly for full mode."""
        results_root = Path("assignment-1/benchmarks/results")
        charts_root = Path("assignment-1/benchmarks/charts")

        with mock.patch("scripts.run_benchmarks.resolve_java_command", return_value="java.exe"):
            command = run_benchmarks.build_pipeline_command("full", results_root, charts_root)

        self.assertEqual(
            command,
            [
                "java.exe",
                "-cp",
                str(run_benchmarks.ASSIGNMENT_ROOT / "target" / "classes"),
                run_benchmarks.JAVA_MAIN_CLASS,
                "--mode",
                "full",
                "--results-root",
                str(results_root),
                "--charts-root",
                str(charts_root),
                "--profile",
                "full",
            ],
        )

    def test_build_speedup_pipeline_command_uses_minimal_mode(self) -> None:
        """Verifies build_pipeline_command uses minimal speedup profile flags when speedup mode is requested."""
        results_root = Path("assignment-1/benchmarks/results")
        charts_root = Path("assignment-1/benchmarks/charts")

        with mock.patch("scripts.run_benchmarks.resolve_java_command", return_value="java.exe"):
            command = run_benchmarks.build_pipeline_command("speedup", results_root, charts_root)

        self.assertEqual(
            command,
            [
                "java.exe",
                "-cp",
                str(run_benchmarks.ASSIGNMENT_ROOT / "target" / "classes"),
                run_benchmarks.JAVA_MAIN_CLASS,
                "--mode",
                "speedup",
                "--results-root",
                str(results_root),
                "--charts-root",
                str(charts_root),
                "--profile",
                "speedup",
            ],
        )

    def test_build_suite_command_uses_requested_mode_and_results_dir(self) -> None:
        """Verifies build_suite_command targets the correct Java suite class with mode flags."""
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
        """Verifies that reset_directory cleans out files and subdirectories from a target path."""
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
        """Verifies that plot_benchmarks' reset_chart_output cleans old plots and directories."""
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
        """Verifies resolve_maven_command searches locally first and prefers the Maven wrapper wrapper scripts."""
        with mock.patch.object(Path, "exists", autospec=True) as exists_mock:
            def fake_exists(path: Path) -> bool:
                return path == run_benchmarks.ASSIGNMENT_ROOT / "mvnw.cmd"

            exists_mock.side_effect = fake_exists
            resolved = run_benchmarks.resolve_maven_command()

        self.assertEqual(resolved, str(run_benchmarks.ASSIGNMENT_ROOT / "mvnw.cmd"))

    def test_resolve_maven_command_uses_path_lookup(self) -> None:
        """Verifies resolve_maven_command falls back to path lookups if no wrapper is found."""
        with mock.patch("scripts.run_benchmarks.shutil.which", side_effect=lambda cmd: "C:/tools/mvn.cmd" if cmd == "mvn.cmd" else None):
            with mock.patch.object(Path, "exists", return_value=False):
                resolved = run_benchmarks.resolve_maven_command()

        self.assertEqual(resolved, "C:/tools/mvn.cmd")

    def test_resolve_maven_command_raises_clear_error_when_missing(self) -> None:
        """Verifies resolve_maven_command raises a descriptive error when no Maven wrapper or PATH binary is found."""
        with mock.patch("scripts.run_benchmarks.shutil.which", return_value=None):
            with mock.patch.object(Path, "exists", return_value=False):
                with self.assertRaisesRegex(RuntimeError, "Maven executable not found"):
                    run_benchmarks.resolve_maven_command()

    def test_print_step_emits_live_progress_prefix(self) -> None:
        """Verifies print_step outputs a standardized benchmark-runner message prefix."""
        stream = StringIO()
        with mock.patch("sys.stdout", stream):
            run_benchmarks.print_step("pipeline-start mode=full")

        self.assertIn("[benchmark-runner] pipeline-start mode=full", stream.getvalue())


if __name__ == "__main__":
    unittest.main()
