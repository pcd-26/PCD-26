# Report

This directory contains the LaTeX source for the report.

The report is organized under `parts/front`, `parts/chapters`, and
`parts/back`.

When opened in VS Code, saving `main.tex` triggers an automatic build and
cleans the temporary LaTeX files after a successful run, leaving only
`report.pdf` in this directory.

To generate `report.pdf`:

```bash
make
```

The `make` target runs `pdflatex` twice so citations and references settle
correctly, then removes the temporary LaTeX files.

Alternatively, without `make`:

```bash
pdflatex -interaction=nonstopmode -halt-on-error -jobname=report main.tex
```

When the build succeeds, temporary LaTeX files such as `report.aux` and
`report.log` should be removed.
