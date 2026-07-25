PCD a.y. 2025-2026 - ISI LM UNIBO - Cesena Campus

# Assignment #01 -  Poool Game

v1.0.1-20260401

The assignment is about designing and developing a game called `Poool`. 

### Game Description 

The game consists in a bidimensional board with a number of small balls and two bigger balls, representing a human player ball and a bot (i.e. computer controlled) ball.  

<img src="board.png">

The number of small balls can be high (thousands). All balls can move and bounce,  against the border or each other. We consider elastic collisions and friction force, so that a moving ball stops after a while.  At the top of the board, in the corners, there are two circles representing holes. The objective of the game for the players (human and bot) is to kick the small balls in the holes, by throwing their own balls in a sequence of throws. 

Details:
- When a player puts a small ball in a hole, his/her score is incremented by one
- If a small ball kicks another small balls in a hole, scores are not changed
- The game ends when there are no more balls in the board and the winner is the player with the biggest score 
- The game ends also if/when the ball of a player goes in a hole. In that case, the winner is the other player, in spite of the score.
- To kick her/his ball, the human player can press keys - UP, DOWN, LEFT, RIGHT - to instantaneously update the velocity (simulating an impulse)
  - for instance, by pressing UP the velocity vector can be updated by adding the vector (0,1)
- Players (human and bot) play asynchronously
- The score of the human and bot player is displayed somewhere: in the picture: in blue, on the left (human) and on the right (bot)

### The Assignment

Design and develop a concurrent version of `Poool`, in two different versions:
1)  One based on Java **multithreaded programming**, using only default/platform threads;
2)  A variant applying **Task-based** approach, using Java **Executor Framework**, where useful.


The concurrent programs should be designed according the principles studied during the course, promoting modularity, encapsulation as well as performance, reactivity. Further remarks:
- For enabling/managing interaction among active components/threads, high-level constructs such as monitors should be preferably used (vs. low-level mechanisms) when possible, providing your own implementation.
- The behaviour of the bot is not meant to be smart, could be any.
- For every other aspect not specified, students are free to choose the best approach for them.

Beside the source code, the assignment should contain a brief report, including:
- A brief analsysis of the problem, focusing in particular those aspects that are relevant from a concurrent point of view
- A brief description of the adopted design, the architecture (structure) and the behaviour
  - for the behaviour, one or multiple Petri Nets can be used, choosing the proper level of abstraction
- Performance tests to check and discuss: 
  - how much the concurrent version is better than a sequential one
  - how much the program is effective in exploiting available cores
- Verification of the program (or some parts of it), using model-checking and JPF in particular 

The `assignment-01`folder in the repo includes two sketches that could be used as a starting point
- [`sketch01`](./sketch-01.md) is an example of main loop using a sequential approach to implement the dynamics of the bouncing balls, as requested in the game
- [`sketch02`](./sketch-02.md) is an example of a GUI program with asynchronous input from the keyboard, architected using MVC



### The deliverable

The deliverable must be a zipped folder `Assignment-01`, to be submitted on the course web site, including:  
- `src` directory with sources
- `doc` directory with the report in PDF (`report.pdf`). 




