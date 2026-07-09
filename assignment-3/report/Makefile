.RECIPEPREFIX := >

PDF = report.pdf
SOURCE = main.tex
TEMP_FILES = report.aux report.log report.out report.toc report.fls report.fdb_latexmk

.DEFAULT_GOAL := all
.PHONY: all clean

all: $(PDF)

$(PDF): $(SOURCE)
>pdflatex -interaction=nonstopmode -halt-on-error -jobname=report $(SOURCE)
>$(RM) $(TEMP_FILES)

clean:
>$(RM) $(TEMP_FILES) $(PDF)
