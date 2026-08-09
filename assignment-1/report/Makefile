.RECIPEPREFIX := >

PDF = report.pdf
SOURCE = main.tex
PDFLATEX_FLAGS = -interaction=nonstopmode -halt-on-error -jobname=report
TEMP_FILES = report.aux report.log report.out report.toc report.fls report.fdb_latexmk

.DEFAULT_GOAL := all
.PHONY: all clean

all: $(PDF)

$(PDF): $(SOURCE)
>pdflatex $(PDFLATEX_FLAGS) $(SOURCE)
>pdflatex $(PDFLATEX_FLAGS) $(SOURCE)
>rm -f $(TEMP_FILES)
>test -f $(PDF)

clean:
>rm -f $(TEMP_FILES) $(PDF)
