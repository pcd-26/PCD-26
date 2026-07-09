# Report

This directory contains the LaTeX source for the Assignment 2 report.

To generate `report.pdf`:

```bash
make
```

Alternatively, without `make`:

```bash
pdflatex -interaction=nonstopmode -halt-on-error -jobname=report main.tex
```

When the build succeeds, temporary LaTeX files such as `report.aux` and
`report.log` can be removed.
