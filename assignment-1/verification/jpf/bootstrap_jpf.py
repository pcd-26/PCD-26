#!/usr/bin/env python3
"""Clone jpf-core into the local verification area."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


DEFAULT_REPOSITORY = "https://github.com/javapathfinder/jpf-core.git"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--destination",
        default=Path(__file__).resolve().parent / ".jpf-core",
        type=Path,
        help="Local directory where jpf-core will be cloned.",
    )
    parser.add_argument(
        "--repository",
        default=DEFAULT_REPOSITORY,
        help="Git repository URL to clone.",
    )
    return parser.parse_args()


def clone_repository(repository: str, destination: Path) -> None:
    if destination.exists():
        print(f"jpf-core already present at {destination}")
        return

    destination.parent.mkdir(parents=True, exist_ok=True)
    print(f"Cloning jpf-core into {destination}")
    subprocess.run(["git", "clone", repository, str(destination)], check=True)

    print()
    print("jpf-core was cloned locally.")
    print("Next steps:")
    print("  1. Open the jpf-core checkout or its Docker container.")
    print("  2. Build jpf-core so build/RunJPF.jar exists.")
    print("  3. Run run_jpf.py to execute the selected verification harness.")


def main() -> int:
    args = parse_args()
    clone_repository(args.repository, args.destination)
    return 0


if __name__ == "__main__":
    sys.exit(main())
