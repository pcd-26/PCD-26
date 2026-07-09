.RECIPEPREFIX := >

PDF = report.pdf
SOURCE = main.tex
TEMP_FILES = report.aux report.log report.out report.toc report.fls report.fdb_latexmk
CLEAN_TEMP = powershell -NoProfile -Command "Remove-Item -LiteralPath 'report.aux','report.log','report.out','report.toc','report.fls','report.fdb_latexmk' -Force -ErrorAction SilentlyContinue; exit 0"

.DEFAULT_GOAL := all
.PHONY: all clean

all: $(PDF)

$(PDF): $(SOURCE)
>pdflatex -interaction=nonstopmode -halt-on-error -jobname=report $(SOURCE)
>$(CLEAN_TEMP)

clean:
>powershell -NoProfile -Command "Remove-Item -LiteralPath 'report.aux','report.log','report.out','report.toc','report.fls','report.fdb_latexmk','report.pdf' -Force -ErrorAction SilentlyContinue; exit 0"
