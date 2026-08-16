package round_test

import (
	"testing"

	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/round"
)

// roundExecution stores the outcome of a round started in a goroutine.
type roundExecution struct {
	winners []domain.Player
	results []domain.MatchResult
	err     error
}

// This test checks that the round waits for all matches before returning.
func TestPlayRoundStartsEachMatchOnceAndWaitsForAllResults(t *testing.T) {
	players := mustPlayers(t, 4)
	started := make(chan int, 2)
	done := make(chan int, 2)
	release := make(chan struct{})
	resultCh := make(chan roundExecution, 1)
	turns := make(chan int, 2)
	turns <- 1
	turns <- 2

	toss := func() domain.CoinSide {
		call := <-turns
		started <- call
		<-release
		done <- call
		return domain.Heads
	}

	go func() {
		winners, results, err := round.PlayRound(1, players, toss)
		resultCh <- roundExecution{winners: winners, results: results, err: err}
	}()

	for i := 0; i < 2; i++ {
		<-started
	}

	select {
	case <-resultCh:
		t.Fatal("round completed before releasing match results")
	default:
	}

	close(release)

	execution := <-resultCh
	if execution.err != nil {
		t.Fatalf("expected round to succeed, got error: %v", execution.err)
	}
	if len(execution.winners) != 2 {
		t.Fatalf("unexpected winners count: got %d want %d", len(execution.winners), 2)
	}
	if len(execution.results) != 2 {
		t.Fatalf("unexpected results count: got %d want %d", len(execution.results), 2)
	}

	assertRoundPlayerUsage(t, execution.results, []int{1, 2, 3, 4})
	assertMatchNumbers(t, execution.results, []int{1, 2})

	for i := 0; i < 2; i++ {
		<-done
	}
}

// This test checks that completion order does not affect result ordering.
func TestPlayRoundPreservesOrderingWhenResultsCompleteOutOfOrder(t *testing.T) {
	players := mustPlayers(t, 4)
	started := make(chan int, 2)
	done := make(chan int, 2)
	release1 := make(chan struct{})
	release2 := make(chan struct{})
	resultCh := make(chan roundExecution, 1)
	turns := make(chan int, 2)
	turns <- 1
	turns <- 2

	toss := func() domain.CoinSide {
		call := <-turns
		started <- call
		if call == 1 {
			<-release1
			done <- 1
			return domain.Heads
		}
		<-release2
		done <- 2
		return domain.Heads
	}

	go func() {
		winners, results, err := round.PlayRound(2, players, toss)
		resultCh <- roundExecution{winners: winners, results: results, err: err}
	}()

	for i := 0; i < 2; i++ {
		<-started
	}

	close(release2)
	if got := <-done; got != 2 {
		t.Fatalf("expected second toss to complete first, got %d", got)
	}

	close(release1)
	if got := <-done; got != 1 {
		t.Fatalf("expected first toss to complete second, got %d", got)
	}

	execution := <-resultCh
	if execution.err != nil {
		t.Fatalf("expected round to succeed, got error: %v", execution.err)
	}
	assertMatchNumbers(t, execution.results, []int{1, 2})
	assertWinnerIDs(t, execution.winners, []int{1, 3})
}

// This test checks that one bad match does not leave goroutines blocked.
func TestPlayRoundPropagatesErrorWithoutBlockingGoroutines(t *testing.T) {
	players := mustPlayers(t, 4)
	started := make(chan int, 2)
	done := make(chan int, 2)
	release1 := make(chan struct{})
	release2 := make(chan struct{})
	resultCh := make(chan roundExecution, 1)
	turns := make(chan int, 2)
	turns <- 1
	turns <- 2

	toss := func() domain.CoinSide {
		call := <-turns
		started <- call
		if call == 1 {
			<-release1
			done <- 1
			return domain.CoinSide("edge")
		}
		<-release2
		done <- 2
		return domain.Heads
	}

	go func() {
		winners, results, err := round.PlayRound(3, players, toss)
		resultCh <- roundExecution{winners: winners, results: results, err: err}
	}()

	for i := 0; i < 2; i++ {
		<-started
	}

	close(release1)
	close(release2)

	execution := <-resultCh
	if execution.err == nil {
		t.Fatal("expected error from invalid toss result")
	}

	for i := 0; i < 2; i++ {
		<-done
	}
}

// assertRoundPlayerUsage checks that each player appears exactly once.
func assertRoundPlayerUsage(t *testing.T, results []domain.MatchResult, want []int) {
	t.Helper()

	if len(results) == 0 {
		t.Fatal("expected results")
	}

	seen := make(map[int]int, len(want))
	for _, result := range results {
		seen[result.FirstPlayer().ID()]++
		seen[result.SecondPlayer().ID()]++
	}

	if len(seen) != len(want) {
		t.Fatalf("unexpected player count in round: got %d want %d", len(seen), len(want))
	}
	for _, playerID := range want {
		if seen[playerID] != 1 {
			t.Fatalf("expected player %d to appear exactly once, got %d", playerID, seen[playerID])
		}
	}
}
