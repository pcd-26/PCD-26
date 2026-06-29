#!/usr/bin/env python3
"""Run the local benchmark pipeline and refresh the latest chart set."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ASSIGNMENT_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ASSIGNMENT_ROOT.parent
DEFAULT_RESULTS_ROOT = ASSIGNMENT_ROOT / "benchmarks" / "results"
DEFAULT_CHARTS_ROOT = ASSIGNMENT_ROOT / "benchmarks" / "charts"
DEFAULT_MAVEN_GOAL = "compile"
DEFAULT_MODE = "full"
JAVA_MAIN_CLASS = "pcd.poool.benchmark.BenchmarkPipeline"
SUITE_MAIN_CLASS = "pcd.poool.benchmark.BenchmarkSuite"


@dataclass(frozen=True)
class BenchmarkRunConfig:
    mode: str
    results_root: Path
    charts_root: Path
    skip_build: bool
    maven_goal: str


def parse_args() -> BenchmarkRunConfig:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--mode",
        choices=("full", "smoke"),
        default=DEFAULT_MODE,
        help="Benchmark mode to execute. Defaults to full.",
    )
    parser.add_argument(
        "--results-root",
        type=Path,
        default=DEFAULT_RESULTS_ROOT,
        help="Root directory where benchmark result files will be written.",
    )
    parser.add_argument(
        "--charts-root",
        type=Path,
        default=DEFAULT_CHARTS_ROOT,
        help="Directory where the latest chart set will be written.",
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="Skip the Maven build step if the project is already compiled.",
    )
    parser.add_argument(
        "--maven-goal",
        default=DEFAULT_MAVEN_GOAL,
        help="Maven goal to run before benchmarks, defaulting to compile.",
    )
    args = parser.parse_args()
    return BenchmarkRunConfig(
        mode=args.mode,
        results_root=args.results_root,
        charts_root=args.charts_root,
        skip_build=args.skip_build,
        maven_goal=args.maven_goal,
    )


def main() -> None:
    config = parse_args()
    run_local_benchmarks(config)


def run_local_benchmarks(config: BenchmarkRunConfig) -> None:
    if not config.skip_build:
        run_command(build_maven_command(config.maven_goal))

    reset_directory(config.results_root)
    if config.mode == "full":
        reset_directory(config.charts_root)
        run_command(
            build_pipeline_command(config.results_root, config.charts_root),
        )
        return

    run_command(build_suite_command(config.mode, config.results_root))


def build_maven_command(goal: str) -> list[str]:
    return [resolve_maven_command(), "-f", str(ASSIGNMENT_ROOT / "pom.xml"), "clean", goal]


def build_pipeline_command(results_root: Path, charts_root: Path) -> list[str]:
    return [
        resolve_java_command(),
        "-cp",
        str(ASSIGNMENT_ROOT / "target" / "classes"),
        JAVA_MAIN_CLASS,
        "--results-root",
        str(results_root),
        "--charts-root",
        str(charts_root),
    ]


def build_suite_command(mode: str, results_root: Path) -> list[str]:
    return [
        resolve_java_command(),
        "-cp",
        str(ASSIGNMENT_ROOT / "target" / "classes"),
        SUITE_MAIN_CLASS,
        f"--{mode}",
        str(results_root),
    ]


def reset_directory(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)


def resolve_maven_command() -> str:
    local_candidates = [
        ASSIGNMENT_ROOT / "mvnw.cmd",
        ASSIGNMENT_ROOT / "mvnw",
        REPO_ROOT / "mvnw.cmd",
        REPO_ROOT / "mvnw",
    ]
    for candidate in local_candidates:
        if candidate.exists():
            return str(candidate)

    path_candidates = ["mvn.cmd", "mvn", "mvn.bat"]
    for candidate in path_candidates:
        resolved = shutil.which(candidate)
        if resolved is not None:
            return resolved

    raise RuntimeError(
        "Maven executable not found. Install Maven and expose `mvn` in PATH, or add a Maven wrapper (`mvnw` or `mvnw.cmd`) under assignment-1/."
    )


def resolve_java_command() -> str:
    path_candidates = ["java.exe", "java"]
    for candidate in path_candidates:
        resolved = shutil.which(candidate)
        if resolved is not None:
            return resolved

    raise RuntimeError("Java executable not found. Install Java 17 and expose `java` in PATH.")


def run_command(command: list[str]) -> None:
    completed = subprocess.run(command, cwd=REPO_ROOT, check=False)
    if completed.returncode != 0:
        raise SystemExit(completed.returncode)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit(130)
    except Exception as exc:
        print(f"benchmark_runner_failed message={exc}", file=sys.stderr)
        raise
