PCD a.y. 2025-2026 - ISI LM UNIBO - Cesena Campus

# Assignment #03 `

v2.0.1-20260506

The assignment is about concurrent programming based on message passing, in particular asynchronous message passing based on actors (first exercise) and synchronous message passing based on processes and channels (second exercise).


### Exercise #1 - *Smart Home Alarm System* 

- [Description](https://github.com/nicolasfara/seminar-pcd-actor-pekko-code/blob/master/assignment_3_smart_home_alarm.md) by N. Farabegoli
- [Description local file](assignment_3_smart_home_alarm.md)
- To be implemented using Apache Pekko, used as reference framework in lab 
  - alternatively, you may use any other actor-based framework or platform: in that case, ask teachers before proceeding

### Exercise #2 - *Odds-and-Evens Game*

The goal of the exercise is to design and implement in Go language a `Odds-and-Evens` game played by `N` players (as a e.g. decision process to select who is going to do some task). The number of players `N` is equal to 2<sup>`m`</sup>, so that  the game is organized in `m` rounds: at each round, games run concurrently and the winners goes to the next round, until the final round. For instance: with `m = 3`, we have 8 players, at the first round playing 4 games concurently; the 4 winners go on playing the next round, playing 2 games concurrently (i.e. the semi-finals); finally, the 2 winners play the final game and we have a winner. 
- To be implemented in Go using an interaction model based on message passing
  - no shared memory is allowed


### The deliverable

The deliverable must be a zipped folder `Assignment-03`, to be submitted on the course web site, including for each problem/exercise:  
- `src` directory with sources
- `doc` directory with a short report in PDF (`report.pdf`). The report should include:
	- A brief analysis of the problem, focusing in particular aspects that are relevant from a  concurrent point of view.
	- A brief description of the strategy adopted

When editing the reports in VS Code, open `assignment-3.code-workspace` so saving `main.tex` triggers the PDF build automatically for both report folders.

