.RECIPEPREFIX := >

PDFLATEX_FLAGS = -interaction=nonstopmode -halt-on-error
PDFS = physics-tick-petri-net.pdf shot-command-petri-net.pdf
TEX_SOURCES = physics-tick-petri-net.tex shot-command-petri-net.tex
TEMP_FILES = physics-tick-petri-net.aux physics-tick-petri-net.log physics-tick-petri-net.out \
             shot-command-petri-net.aux shot-command-petri-net.log shot-command-petri-net.out

.DEFAULT_GOAL := all
.PHONY: all clean

all: $(PDFS)

physics-tick-petri-net.pdf: physics-tick-petri-net.tex
>pdflatex $(PDFLATEX_FLAGS) $<
>pdflatex $(PDFLATEX_FLAGS) $<

shot-command-petri-net.pdf: shot-command-petri-net.tex
>pdflatex $(PDFLATEX_FLAGS) $<
>pdflatex $(PDFLATEX_FLAGS) $<

clean:
>rm -f $(TEMP_FILES) $(PDFS)
