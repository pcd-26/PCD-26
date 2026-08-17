from __future__ import annotations

import subprocess
import sys
from shutil import which
from pathlib import Path


def find_report_root(start_dir: Path) -> Path | None:
    current = start_dir.resolve()

    for candidate in (current, *current.parents):
        if (candidate / "Makefile").is_file() and (candidate / "Ass3OddsAndEvensGame.tex").is_file():
            return candidate

    return None


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: build_latex_report.py <directory>", file=sys.stderr)
        return 2

    start_dir = Path(sys.argv[1])
    report_root = find_report_root(start_dir)
    if report_root is None:
        return 0

    make_command = which("mingw32-make") or which("make")
    if make_command is None:
        print("Cannot build report: neither mingw32-make nor make was found.", file=sys.stderr)
        return 127

    result = subprocess.run(
        [make_command, "-C", str(report_root)],
        check=False,
    )
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
