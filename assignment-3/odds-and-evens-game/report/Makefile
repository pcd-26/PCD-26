.RECIPEPREFIX := >

PDF = report.pdf
SOURCE = Ass3OddsAndEvensGame.tex
PDFLATEX_FLAGS = -interaction=nonstopmode -halt-on-error -jobname=report
TEMP_FILES = report.aux report.log report.out report.toc report.fls report.fdb_latexmk
ifeq ($(OS),Windows_NT)
REMOVE_TEMP = powershell -NoProfile -Command "Remove-Item -Force report.aux,report.log,report.out,report.toc,report.fls,report.fdb_latexmk -ErrorAction SilentlyContinue; exit 0"
REMOVE_ALL = powershell -NoProfile -Command "Remove-Item -Force report.aux,report.log,report.out,report.toc,report.fls,report.fdb_latexmk,report.pdf -ErrorAction SilentlyContinue; exit 0"
CHECK_PDF = powershell -NoProfile -Command "if (!(Test-Path 'report.pdf')) { exit 1 }"
else
REMOVE_TEMP = rm -f $(TEMP_FILES)
REMOVE_ALL = rm -f $(TEMP_FILES) $(PDF)
CHECK_PDF = test -f $(PDF)
endif

.DEFAULT_GOAL := all
.PHONY: all clean

all:
>pdflatex $(PDFLATEX_FLAGS) $(SOURCE)
>pdflatex $(PDFLATEX_FLAGS) $(SOURCE)
>$(REMOVE_TEMP)
>$(CHECK_PDF)

clean:
>$(REMOVE_ALL)
