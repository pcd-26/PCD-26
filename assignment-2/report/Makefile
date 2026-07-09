PDF = report.pdf
SOURCE = main.tex

.PHONY: all clean

all: $(PDF)

$(PDF): $(SOURCE)
\tpdflatex -interaction=nonstopmode -halt-on-error -jobname=report $(SOURCE)
\t$(RM) report.aux report.log report.out report.toc

clean:
\t$(RM) report.aux report.log report.out $(PDF)
