PCD a.y. 2025-2026 - ISI LM UNIBO - Cesena Campus

# Assignment #03 `

v1.0.0-20260504

The assignment is about concurrent programming based on message passing, synchronous message passing based on processes and channels (first exercise, in Go) and asynchronous message passing based on actors (second exercise, using Apache Pekko).


### Exercise #1 - *Smart Home Alarm System* 

- [Description](https://github.com/nicolasfara/seminar-pcd-actor-pekko-code/blob/master/assignment_3_smart_home_alarm.md) by N. Farabegoli
- To be implemented using Apache Pekko, used as reference framework in lab 
  - alternatively, you may use any other actor-based framework or platform: in that case, ask teachers before proceeding

### Exercise #2 - *Heads-or-Tails Championship*

The goal of the exercise is to design and implement in Go language a championship of `N` players playing a `Heads-or-Tails` game. The number of players `N` is equal to 2<sup>`m`</sup>, so that  the championship is organized in `m` rounds: at each round, games run concurrently and the winners goes to the next round, until the final round. For instance: with `m = 3`, we have 8 players, at the first round playing 4 games concurently; the 4 winners go on playing the next round, playing 2 games concurrently (i.e. the semi-finals); finally, the 2 winners play the final game and we have a winner. 
- To be implemented in Go using an interaction model based on message passing
  - no shared memory is allowed


### The deliverable

The deliverable must be a zipped folder `Assignment-03`, to be submitted on the course web site, including:  
- `src` directory with sources
- `doc` directory with a short report in PDF (`report.pdf`). The report should include:
	- A brief analsysis of the problem, focusing in particular aspects that are relevant from a  concurrent point of view.
	- A brief description of the strategy adopted

