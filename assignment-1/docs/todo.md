# Assignment 1 TODO

## Game model structures

The core physics model is present, but the full game model described in the
README still needs the following structures and rules.

- [ ] Add the bot ball to the board/model, alongside the human player ball.
- [ ] Introduce explicit player identity, e.g. `HUMAN` and `BOT`.
- [ ] Add score state for both players: `humanScore` and `botScore`.
- [ ] Add turn/match state, e.g. current player, game status, and winner.
- [ ] Distinguish who caused a small ball to enter a hole, so score can be
      assigned only when a player ball directly pockets it.
- [ ] Implement complete game-over rules:
      - no small balls left: winner is the player with the highest score
      - human ball pocketed: bot wins
      - bot ball pocketed: human wins
- [ ] Add high-level kick operations, e.g. `kickHuman(...)` and `kickBot(...)`,
      instead of exposing direct ball manipulation.
- [ ] Add a bot/player model or agent entry point for future asynchronous bot
      behavior.
- [ ] Expose game-state snapshots for the view, including scores, turn, and
      game-over status.

## Execution modes

The domain model and physics engine should stay shared across all versions.
Different assignment variants should be implemented as different execution
strategies around the same model, not as separate games.

- [ ] Add a common runner abstraction, e.g. `GameRunner` with `start()` and
      `stop()`.
- [ ] Add a sequential runner to use as the baseline implementation and for
      performance comparisons.
- [ ] Add a platform-thread runner for the multithreaded version.
- [ ] Add a task-based runner using `ExecutorService` for the task-based
      version.
- [ ] Add a `Main` entry point that selects the execution mode from command-line
      arguments, e.g. `sequential`, `threads`, or `tasks`.
- [ ] Reuse the command concept where useful:
      - threaded version: `Cmd -> BoundedBuffer -> ActiveController -> model`
      - task-based version: `Cmd -> ExecutorService -> model`
- [ ] Keep model mutations serialized unless a specific subsystem is designed
      for safe parallelism.
- [ ] Consider parallelism mainly inside expensive physics phases, such as
      collision candidate processing, while preserving deterministic merge/order.
- [ ] Make all runners expose comparable metrics for the report, such as frame
      time, simulation step time, number of balls, and core utilization.
