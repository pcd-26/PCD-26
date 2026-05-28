# Assignment 1 - Concurrent Analysis and Architectural Design (Poool)

## 1. Goal and scope
Define a concurrent architecture for Poool that is correct, responsive, and verifiable.

Includes: game logic, physics simulation, user input, bot, GUI.
Excludes: multiplayer networking, persistence, hardware-specific optimizations.

## 2. Reused artifacts from sketch-01 and sketch-02
In this context, "reused" means concrete source artifacts (files/classes), not only ideas.

Final-delivery code under `pcd.poool` reuses mainly:
- from `pcd.sketch01`: physics/domain + board view artifacts (`Ball`, `Board`, `BoardConf`, board configs, `Boundary`, `P2d`, `V2d`, `ViewModel`, `View`, `ViewFrame`, `RenderSynch`)
- from `pcd.sketch02`: concurrency/controller pattern artifacts (`Cmd`, `ActiveController`, `BoundedBuffer`, `BoundedBufferImpl`)

Non-final demo artifacts remain under `assignment-1/reference/sketch01` and `assignment-1/reference/sketch02`; they are not part of the Maven build or the `pcd.poool` final-delivery scope.

Conceptual baseline from `sketch-01`:
- main loop that updates board state and renders frames
- attention to concurrency between model updates and EDT painting
- possible use of double buffering

Conceptual baseline from `sketch-02`:
- MVC
- asynchronous input
- active controller with producer/consumer on a bounded buffer
- non-blocking variant (`poll`) when the controller must keep looping

## 3. Chosen architecture (primary)

### 3.1 Components and responsibilities
1. `GameController` (active)
- orchestrates the match
- manages state transitions (`WAITING_INPUT`, `SIMULATING`, `TURN_RESOLUTION`, `GAME_OVER`)
- coordinates input, bot, physics, and GUI

2. `PhysicsEngine` (active)
- runs physics at fixed ticks
- handles collisions
- publishes consistent physics snapshots

3. `InputHandler` (active/event-driven)
- acquires input and translates it into commands
- sends commands to the controller

4. `BotAgent` (active)
- generates a shot when it is the bot turn
- sends command to the controller

5. `GUIRenderer` (active)
- reads immutable snapshots and renders

6. `GameStateModel` (passive)
- turn, score, match state

7. `PhysicsStateModel` (passive)
- ball position/velocity/dynamic state

### 3.2 Main interactions
- `InputHandler -> GameController`
- `BotAgent -> GameController`
- `GameController -> PhysicsEngine`
- `PhysicsEngine -> GameController` (e.g., `balls_stopped`)
- `PhysicsEngine -> GUIRenderer` (snapshot)
- `GameController -> GUIRenderer` (logical state)

### 3.3 Command-based controller pattern
The current controller foundation is based on a combination of the Command
pattern and the Active Object pattern.

`Cmd<T>` represents a request to perform an action on a target object of type
`T`. The command stores the parameters of the action, but does not execute the
action immediately. Execution happens only when the active controller consumes
the command from its queue.

Example conceptual flow:

```text
keyboard input / bot decision / GUI event
        |
        v
      Cmd<T>
        |
        v
 ActiveController<T>
        |
        v
   target model/service
```

For example, a future `KickPlayerCmd` should not need to own or directly expose
the player ball. It can be a `Cmd<Board>` that stores the desired velocity and,
when executed, calls a board-level operation such as `board.kickPlayer(velocity)`.
The `Board` then applies the change to its internal `playerBall`.

This keeps the ownership clear:

- producers such as the input handler and the bot create commands
- the active controller serializes command execution
- model objects are modified through a controlled entry point
- the view observes model snapshots instead of driving game logic directly

The resulting path is:

```text
InputHandler/Bot/View event
        |
        v
      command
        |
        v
 controller queue
        |
        v
 ActiveController.run()
        |
        v
 command.execute(target)
        |
        v
 model state update
        |
        v
 ViewModel snapshot -> View rendering
```

Not every internal operation must be represented as a command. Internal model
logic, such as collision resolution inside `Ball` or physics integration inside
`Board`, can remain regular domain methods. Commands are mainly useful for
external asynchronous requests that may come from different threads, such as
user input, bot actions, reset, pause, resume, or turn-management events.

## 4. Synchronization and shared state

### 4.1 Ownership rules
- `GameStateModel`: single writer `GameController`
- `PhysicsStateModel`: single writer `PhysicsEngine`
- all other components read or send change requests through events/commands

### 4.2 Coordination strategy
- message passing by default
- thread-safe queue for events/commands
- short critical sections only
- no rendering under locks
- in Swing, avoid races between model updates and EDT repaint

## 5. Concurrent game loop
- Physics loop (fixed tick, e.g., 60 Hz): integration -> collisions -> snapshot publish -> rest check
- Control loop: consumes events, updates turn state, enables input or bot
- Rendering loop: reads latest available snapshot without blocking

## 6. Physics parallelization strategy
Strategy:
1. broad phase with spatial partitioning
2. parallel processing of collision candidate pairs
3. merge/deduplicate pairs
4. final resolution with stable ordering (tie-break by ball id)

Tradeoffs:
- higher throughput
- higher synchronization complexity
- strong determinism requires explicit stable ordering

## 7. Considered alternatives

### A) Single-thread main loop
Pros:
- simple to implement and debug
- more deterministic behavior

Cons:
- limited scalability
- risk of FPS drop under high load

### B) Aggressive physics parallelization
Pros:
- maximum performance on large/massive configurations

Cons:
- higher risk of races/concurrency bugs
- harder verification

## 8. Architectural decision
Choice: **balanced primary architecture** (separate controller + physics, GUI from snapshots, asynchronous input/bot).

Rationale:
- satisfies all assignment requirements
- keeps strong design clarity
- provides the best tradeoff among performance, robustness, and verifiability

## 9. Concurrency properties and critical scenarios to verify

Properties:
- safety: no data races, no deadlocks, no lost updates
- liveness: guaranteed turn progression, responsive UI, no control-thread starvation

Critical scenarios:
1. user input while physics simulation is running
2. turn switch while bot computation is in progress
3. multiple simultaneous collisions
4. pause/resume during active simulation
5. match reset with pending queued events
6. high physics load without GUI freeze

## 10. Traceability to acceptance requirements
- Concurrent architecture clearly defined: sections 3, 5
- Responsibilities identified: section 3.1
- Synchronization and coordination strategies: section 4
- Shared-state ownership rules: section 4.1
- Physics parallelization strategy: section 6
- Interactions between GUI/simulation/input/bot: section 3.2
- Critical concurrent scenarios to verify: section 9
