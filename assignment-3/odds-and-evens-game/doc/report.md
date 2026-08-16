# Odds and Evens Game Report

## 1. Problem Analysis

The Odds and Evens Game is a single-elimination tournament.
If the initial player count is `N = 2^m`, then the tournament has `m = log2(N)` rounds.
Each round halves the number of active players, so the total number of matches is `N - 1`.
This matches the elimination tree implemented by the championship coordinator.

## 2. Concurrent Decomposition

Matches in the same round are independent from one another.
The round coordinator starts one goroutine per match, so all matches in a round execute concurrently.
Rounds are temporally dependent: the next round can start only after the current round has completed.
The coordinator waits for exactly one result message per match, and this collection step acts as a barrier between rounds.

## 3. Message-Passing Strategy

Concurrent match activity is modeled by goroutines.
Channels are the only interaction mechanism between workers and coordinators.
Each match worker sends exactly one result message.
Match workers do not mutate shared slices, maps, arrays, or result structures.
Only the round coordinator builds the next players collection.
The implementation does not use shared mutable memory as the communication model.

## 4. Architecture

The architecture is split into:

- championship coordinator, which runs the full elimination;
- round coordinator, which manages one round and waits for all match outcomes;
- match workers, which execute a single match;
- `CoinTosser` abstraction, which hides the coin-toss source;
- result message type, which carries either a `MatchResult` or an error;
- explicit channel ownership, where each sender writes to its own protocol channel and the coordinator performs aggregation.

## 5. Correctness Properties

The implementation enforces the following properties:

- exactly one winner is produced per match;
- each player participates once per round;
- winners are the only players admitted to the next round;
- the player count is halved after each round;
- exactly one champion is produced at the end of the championship;
- the design avoids deadlock by having the coordinator receive the exact number of expected messages;
- the design avoids goroutine leaks by ensuring each worker has a termination path and each error still allows the coordinator to drain all results.

## 6. Testing

The test suite includes:

- deterministic unit tests for the domain model and match engine;
- controlled concurrency tests that use fake tossers and channels;
- repeated tests suitable for `go test -count=100 ./...`;
- race-detector runs with `go test -race ./...`;
- invalid input tests for players, rounds, and championship startup.

The report source is intentionally kept short and aligned with the current implementation.
