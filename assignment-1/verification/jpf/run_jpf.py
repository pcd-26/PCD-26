#!/usr/bin/env python3
"""Run the minimal JPF models through a local JPF runtime."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model",
        choices=("threaded", "taskbased", "both"),
        required=True,
        help="Which minimal model to run.",
    )
    parser.add_argument(
        "--docker",
        action="store_true",
        default=os.environ.get("JPF_USE_DOCKER", "").lower() in {"1", "true", "yes", "on"},
        help="Build and run JPF inside Docker instead of using a local Java process.",
    )
    return parser.parse_args()


def java_executable() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.exists():
            return str(candidate)
    return "java"


def host_java_major_version() -> int | None:
    try:
        result = subprocess.run(
            [java_executable(), "-version"],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None

    version_output = "\n".join(filter(None, [result.stdout, result.stderr]))
    match = re.search(r'version "([0-9]+)(?:\.([0-9]+))?', version_output)
    if not match:
        return None

    major = int(match.group(1))
    if major == 1 and match.group(2) is not None:
        return int(match.group(2))
    return major


def compile_minimal_harnesses(repo_root: Path) -> None:
    """Compile the Java 11 verification models before launching JPF."""
    source_root = repo_root / "assignment-1" / "verification" / "jpf" / "src"
    output_root = repo_root / "assignment-1" / "target" / "jpf-classes"
    sources = sorted(source_root.rglob("*.java"))
    if not sources:
        raise SystemExit(f"No JPF harness sources found under {source_root}")

    shutil.rmtree(output_root, ignore_errors=True)
    output_root.mkdir(parents=True)
    command = [
        str(Path(java_executable()).with_name("javac.exe"))
        if os.name == "nt" and Path(java_executable()).name.lower() == "java.exe"
        else "javac",
        "--release",
        "11",
        "-d",
        str(output_root),
        *(str(source) for source in sources),
    ]
    subprocess.run(command, cwd=repo_root, check=True)

def run_model(jpf_root: Path, workdir: Path, config_file: Path) -> None:
    command = [
        java_executable(),
        "-ea",
        "-jar",
        str(jpf_root / "build" / "RunJPF.jar"),
        str(config_file),
    ]
    subprocess.run(command, cwd=workdir, check=True)


def build_jpf_in_docker(jpf_root: Path) -> None:
    image_name = "jpf-core"
    subprocess.run(["docker", "build", "-t", image_name, "."], cwd=jpf_root, check=True)
    subprocess.run(
        [
            "docker",
            "run",
            "--rm",
            "-v",
            f"{jpf_root.resolve().as_posix()}:/home/jpf-core",
            "-w",
            "/home/jpf-core",
            image_name,
            "bash",
            "-lc",
            "sed -i 's/\\r$//' gradlew && chmod +x gradlew && ./gradlew build",
        ],
        check=True,
    )


def run_model_in_docker(jpf_root: Path, repo_root: Path, config_file: Path) -> None:
    image_name = "jpf-core"
    docker_command = f"java -ea -jar /home/jpf-core/build/RunJPF.jar /repo/{config_file.as_posix()}"
    subprocess.run(
        [
            "docker",
            "run",
            "--rm",
            "-v",
            f"{jpf_root.resolve().as_posix()}:/home/jpf-core",
            "-v",
            f"{repo_root.resolve().as_posix()}:/repo",
            "-w",
            "/repo/assignment-1",
            image_name,
            "bash",
            "-lc",
            docker_command,
        ],
        check=True,
    )


def main() -> int:
    args = parse_args()

    verification_dir = Path(__file__).resolve().parent
    jpf_root = verification_dir / ".jpf-core"
    if not jpf_root.exists():
        raise SystemExit(
            f"Missing local jpf-core checkout at {jpf_root}. "
            "Run bootstrap_jpf.py first."
        )

    repo_root = verification_dir.parents[2]
    compile_minimal_harnesses(repo_root)

    use_docker = args.docker
    if not use_docker:
        java_major = host_java_major_version()
        if java_major is None or java_major > 17:
            use_docker = True

    if use_docker:
        build_jpf_in_docker(jpf_root)
    else:
        required_jars = [
            jpf_root / "build" / "RunJPF.jar",
            jpf_root / "build" / "jpf.jar",
            jpf_root / "build" / "jpf-classes.jar",
            jpf_root / "build" / "jpf-annotations.jar",
        ]
        if any(not jar.is_file() for jar in required_jars):
            raise SystemExit(
                f"Missing JPF runtime files under {jpf_root}. "
                "Use --docker to build JPF automatically inside the container, "
                "or run ./gradlew build inside .jpf-core first."
            )

    if args.model in {"threaded", "both"}:
        config_file = verification_dir / "threaded-minimal.jpf"
        if use_docker:
            run_model_in_docker(jpf_root, repo_root, config_file.relative_to(repo_root))
        else:
            run_model(
                jpf_root,
                repo_root / "assignment-1",
                config_file.relative_to(repo_root / "assignment-1"),
            )
    if args.model in {"taskbased", "both"}:
        config_file = verification_dir / "taskbased-minimal.jpf"
        if use_docker:
            run_model_in_docker(jpf_root, repo_root, config_file.relative_to(repo_root))
        else:
            run_model(
                jpf_root,
                repo_root / "assignment-1",
                config_file.relative_to(repo_root / "assignment-1"),
            )

    return 0


if __name__ == "__main__":
    sys.exit(main())
