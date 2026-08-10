.RECIPEPREFIX := >

PDF = report.pdf
SOURCE = main.tex
TEMP_FILES = report.aux report.log report.out report.toc report.fls report.fdb_latexmk

.DEFAULT_GOAL := all
.PHONY: all clean

$(PDF): $(SOURCE)
>pdflatex -interaction=nonstopmode -halt-on-error -jobname=report $(SOURCE)
>pdflatex -interaction=nonstopmode -halt-on-error -jobname=report $(SOURCE)
>rm -f $(TEMP_FILES)

clean:
>rm -f $(TEMP_FILES) $(PDF)
