# Report

This directory contains the LaTeX source for the Assignment 4 Exercise 2 report.

Open `main.tex` in VS Code to use the LaTeX tooling configured for the
workspace, or run the build manually with:

```bash
make
```

Without `make`, the equivalent command is:

```bash
pdflatex -interaction=nonstopmode -halt-on-error -jobname=report main.tex
```

The expected output is `report.pdf` in this directory.
