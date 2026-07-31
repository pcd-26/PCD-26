PCD a.y. 2025-2026 - ISI LM UNIBO - Cesena Campus

# Assignment #04

v1.0.0-20260517

The assignment is about distributed programming.

### Exercise #1 - *Distributed Smart Home Alarm System*

- [Description](https://github.com/nicolasfara/seminar-pcd-actor-pekko-code/blob/master/assignment_4_smart_home_alarm_cluster.md) by N. Farabegoli
- [Local description](ex1.md)
- [Project startup commands](clustered-smart-home-alarm-system/README.md)

### Exercise #2 - *Distributed TTT with Java RMI*

We want to implement a distributed system for playing Tic-Tac-Toe:
- A player that aims at play a game can create a new game with some name, waiting for opponents
- A player can join an existing game, given its name

The system should be designed according the distributed object computing and concurrent programming principles discussed in the course, using Java RMI as underlying RPC mechanism.

- [Project startup commands](distributed-tic-tac-toe/README.md)
- [Report source](distributed-tic-tac-toe/report/README.md)
 
### **[Optional]**  Exercise #3 - *Distributed Critical Sections with a Message-Oriented Middleware* 

Implement a simple high-level middleware providing support for realising critical sections for processes running in a distributed system. 
- A process must be able to use the functionality provided by the middleware without knowing anything about the other processes involved in the critical sections.
- The middleware must be designed/implemented using a MOM (such as RabbitMQ), using message passing. 

This exercise is mandatory only for students aiming at 30L.


### The deliverable

The deliverable must be a zipped folder `Assignment-04`, to be submitted on the course web site, including the `clustered-smart-home-alarm-system` project:
- `clustered-smart-home-alarm-system/src` directory with sources
- `clustered-smart-home-alarm-system/doc` directory with a short report in PDF (`report.pdf`). The report should include:
	- A brief analsysis of the problem, focusing in particular aspects that are relevant from a  concurrent point of view.
	- A brief description of the strategy adopted

The `distributed-tic-tac-toe` sub-project follows the same structure locally:
- `distributed-tic-tac-toe/src` directory with sources
- `distributed-tic-tac-toe/report` directory with the LaTeX source for its report

The optional `distributed-critical-sections` sub-project is bundled into the same Assignment 4 zip when present:
- `distributed-critical-sections/src` directory with sources
- `distributed-critical-sections/report` directory with the LaTeX source for its report

In the delivered zip, each bundled project should appear with a `src/`
directory and a `doc/` directory containing the compiled report PDF.
