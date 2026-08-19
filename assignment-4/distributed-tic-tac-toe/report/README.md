# Report

This directory contains the LaTeX source for the Assignment 4 Exercise 2 report.

Open any `.tex` file in this report directory in VS Code to use the LaTeX
tooling configured for the workspace, or run the build manually with:

```bash
make
```

Without `make`, the equivalent command is:

```bash
pdflatex -interaction=nonstopmode -halt-on-error -jobname=report Ass4DistributedTicTacToe.tex
```

The expected output is `report.pdf` in this directory.
