from __future__ import annotations

import subprocess
import sys
from shutil import which
from pathlib import Path


def find_report_root(target: Path) -> Path | None:
    current = target.resolve()
    # 1. If the target is a file, check if it's a .tex file
    if current.is_file():
        if current.suffix == ".tex":
            report_root = current.parent
            if (report_root / "Makefile").is_file():
                return report_root
        current = current.parent
    # 2. Traverse upwards looking for a Makefile alongside any .tex file
    for candidate in (current, *current.parents):
        if (candidate / "Makefile").is_file() and any(candidate.glob(".tex")):
            return candidate

    return None


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: build_latex_report.py <file-or-directory>", file=sys.stderr)
        return 2

    target = Path(sys.argv[1])
    report_root = find_report_root(target)
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
