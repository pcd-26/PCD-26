.RECIPEPREFIX := >

PDF = report.pdf
SOURCE = main.tex
TEMP_FILES = report.aux report.log report.out report.toc report.fls report.fdb_latexmk report.synctex.gz

.DEFAULT_GOAL := all
.PHONY: all clean

all: $(PDF)

$(PDF): $(SOURCE)
>pdflatex -interaction=nonstopmode -halt-on-error -jobname=report $(SOURCE)
>pdflatex -interaction=nonstopmode -halt-on-error -jobname=report $(SOURCE)

clean:
>if exist $(PDF) del /Q /F $(PDF)
>for %%f in ($(TEMP_FILES)) do if exist %%f del /Q /F %%f
