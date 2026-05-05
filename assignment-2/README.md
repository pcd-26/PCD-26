PCD a.y. 2025-2026 - ISI LM UNIBO - Cesena Campus

# Assignment #02 -  `FSStat`

v1.0.0-20260412

The assignment is about implementing a library called `FSStatLib` providing an **asynchronous  method** called `getFSReport` (details in the following) and a simple program exemplifying the use of the library.  

The library and the example program must be developed using the three different programming approaches discussed in the course, so producing three different versions:  
1. Asynchronous programming based on event-loops 
2. Reactive programming using Rx
3. Virtual Threads

**Important remark**: each version should be designed focussing exclusively on the programming discipline that characterises each approach. That is: don't generalise or reuse code when this would negatively impact on the design and programming discipline promoted by each individual approach.

### Details

The asynchronous method `getFSReport` must compute some statistics about the size of the files of some directory `D`, including recursively its subdirectories. In particular, the method should asynchronously compute and return a report `R` including:
- The total number of files that belongs to `D` (including subdirectories, recursively);
- The distribution of file sizes, that is: given a file size `MaxFS` and a number of file size bands `NB` dividing the file size range `[0, MaxFS]`, the method computes for each band the number of files with a size included in it and the number of files with a size bigger that `MaxFS` (so complexively `NB` + 1 size ranges).

`D`, `MaxFS`, `NB`are parameters of the method. 

### Optional point [*]

Develop an *interactive* extension of the library, which should provide the possibility to:
- possibly stop the generation of a report;
- dynamically get updates about statitistics, in order to e.g. visualise them.

A minimal GUI program can be used to show the features of the extended version, with e.g. buttons to start and stop the generation of report and a text area (or, a graphic panel) to visulise dynamically the statitics.
  

**[*]** mandatory for students aiming at excellence grade (30 cum laude).

### The deliverable

The deliverable must be a zipped folder `Assignment-02`, to be submitted on the course web site, including:  
- `src` directory with sources
- `doc` directory with a short report in PDF (`report.pdf`). The report should include:
	- A brief analsysis of the problem, focusing in particular aspects that are relevant from a  concurrent point of view.
	- A brief description of the strategy adopted

