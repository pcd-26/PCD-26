PDF = report.pdf
SOURCE = main.tex

.PHONY: all clean

all: $(PDF)

$(PDF): $(SOURCE)
	pdflatex -interaction=nonstopmode -halt-on-error -jobname=report $(SOURCE)
	$(RM) report.aux report.log report.out report.toc

clean:
	$(RM) report.aux report.log report.out $(PDF)
