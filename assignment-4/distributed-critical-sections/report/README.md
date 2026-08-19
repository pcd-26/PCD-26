# Report

This directory contains the LaTeX source for the Assignment 4 Exercise 3 report.

Open any `.tex` file in this report directory in VS Code to use the
workspace LaTeX tooling, or build it manually with:

```bash
make
```

Without `make`, the equivalent command is:

```bash
pdflatex -interaction=nonstopmode -halt-on-error -jobname=report Ass4DistributedCriticalSections.tex
```

The expected output is `report.pdf` in this directory.
