package championship

import "testing"

type roundExecution struct {
	winners []Player
	results []MatchResult
	err     error
}

type coordinatedTosser struct {
	matchNumber int
	side        CoinSide
	started     chan<- int
	done        chan<- int
	release     <-chan struct{}
}

func (t coordinatedTosser) Toss() CoinSide {
	t.started <- t.matchNumber
	<-t.release
	t.done <- t.matchNumber
	return t.side
}

func signalRelease(ch chan struct{}) {
	select {
	case ch <- struct{}{}:
	default:
	}
}

func TestPlayRoundStartsEachMatchOnceAndWaitsForAllResults(t *testing.T) {
	players := mustPlayers(t, 4)
	started := make(chan int, 2)
	done := make(chan int, 2)
	release1 := make(chan struct{}, 1)
	release2 := make(chan struct{}, 1)
	resultCh := make(chan roundExecution, 1)
	defer signalRelease(release1)
	defer signalRelease(release2)

	go func() {
		winners, results, err := PlayRound(1, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser {
			switch matchNumber {
			case 1:
				return coordinatedTosser{
					matchNumber: matchNumber,
					side:        Heads,
					started:     started,
					done:        done,
					release:     release1,
				}
			default:
				return coordinatedTosser{
					matchNumber: matchNumber,
					side:        Tails,
					started:     started,
					done:        done,
					release:     release2,
				}
			}
		})
		resultCh <- roundExecution{winners: winners, results: results, err: err}
	}()

	startedMatches := make(map[int]struct{}, 2)
	for len(startedMatches) < 2 {
		startedMatches[<-started] = struct{}{}
	}
	if len(startedMatches) != 2 {
		t.Fatalf("expected each match to start once, got %d starts", len(startedMatches))
	}

	select {
	case <-resultCh:
		t.Fatal("round completed before releasing match results")
	default:
	}

	signalRelease(release1)
	signalRelease(release2)

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

	doneMatches := make(map[int]struct{}, 2)
	for len(doneMatches) < 2 {
		doneMatches[<-done] = struct{}{}
	}
	if len(doneMatches) != 2 {
		t.Fatalf("expected each match to produce one result, got %d completions", len(doneMatches))
	}
}

func TestPlayRoundUsesOneResultPerMatchAndNoPlayerTwice(t *testing.T) {
	players := mustPlayers(t, 8)
	started := make(chan int, 4)
	done := make(chan int, 4)
	release := []chan struct{}{
		make(chan struct{}, 1),
		make(chan struct{}, 1),
		make(chan struct{}, 1),
		make(chan struct{}, 1),
	}
	resultCh := make(chan roundExecution, 1)
	defer func() {
		for _, ch := range release {
			signalRelease(ch)
		}
	}()

	go func() {
		winners, results, err := PlayRound(1, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser {
			return coordinatedTosser{
				matchNumber: matchNumber,
				side:        Heads,
				started:     started,
				done:        done,
				release:     release[matchNumber-1],
			}
		})
		resultCh <- roundExecution{winners: winners, results: results, err: err}
	}()

	startedMatches := make(map[int]struct{}, 4)
	for len(startedMatches) < 4 {
		startedMatches[<-started] = struct{}{}
	}
	if len(startedMatches) != 4 {
		t.Fatalf("expected exactly 4 started matches, got %d", len(startedMatches))
	}

	for _, ch := range release {
		signalRelease(ch)
	}

	execution := <-resultCh
	if execution.err != nil {
		t.Fatalf("expected round to succeed, got error: %v", execution.err)
	}
	if len(execution.results) != 4 {
		t.Fatalf("unexpected results count: got %d want %d", len(execution.results), 4)
	}
	assertRoundPlayerUsage(t, execution.results, []int{1, 2, 3, 4, 5, 6, 7, 8})
}

func TestPlayRoundPreservesOrderingWhenResultsCompleteOutOfOrder(t *testing.T) {
	players := mustPlayers(t, 4)
	started := make(chan int, 2)
	done := make(chan int, 2)
	release1 := make(chan struct{}, 1)
	release2 := make(chan struct{}, 1)
	resultCh := make(chan roundExecution, 1)
	defer signalRelease(release1)
	defer signalRelease(release2)

	go func() {
		winners, results, err := PlayRound(2, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser {
			if matchNumber == 1 {
				return coordinatedTosser{
					matchNumber: matchNumber,
					side:        Heads,
					started:     started,
					done:        done,
					release:     release1,
				}
			}
			return coordinatedTosser{
				matchNumber: matchNumber,
				side:        Tails,
				started:     started,
				done:        done,
				release:     release2,
			}
		})
		resultCh <- roundExecution{winners: winners, results: results, err: err}
	}()

	startedMatches := make(map[int]struct{}, 2)
	for len(startedMatches) < 2 {
		startedMatches[<-started] = struct{}{}
	}

	signalRelease(release2)
	if got := <-done; got != 2 {
		t.Fatalf("expected match 2 to complete first, got match %d", got)
	}

	signalRelease(release1)
	if got := <-done; got != 1 {
		t.Fatalf("expected match 1 to complete second, got match %d", got)
	}

	execution := <-resultCh
	if execution.err != nil {
		t.Fatalf("expected round to succeed, got error: %v", execution.err)
	}
	assertMatchNumbers(t, execution.results, []int{1, 2})
	assertWinnerIDs(t, execution.winners, []int{1, 4})
}

func TestPlayRoundPropagatesErrorWithoutBlockingGoroutines(t *testing.T) {
	players := mustPlayers(t, 4)
	started := make(chan int, 2)
	done := make(chan int, 2)
	release1 := make(chan struct{}, 1)
	release2 := make(chan struct{}, 1)
	resultCh := make(chan roundExecution, 1)
	defer signalRelease(release1)
	defer signalRelease(release2)

	go func() {
		winners, results, err := PlayRound(3, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser {
			if matchNumber == 1 {
				return coordinatedTosser{
					matchNumber: matchNumber,
					side:        CoinSide("edge"),
					started:     started,
					done:        done,
					release:     release1,
				}
			}
			return coordinatedTosser{
				matchNumber: matchNumber,
				side:        Heads,
				started:     started,
				done:        done,
				release:     release2,
			}
		})
		resultCh <- roundExecution{winners: winners, results: results, err: err}
	}()

	startedMatches := make(map[int]struct{}, 2)
	for len(startedMatches) < 2 {
		startedMatches[<-started] = struct{}{}
	}

	signalRelease(release1)
	signalRelease(release2)

	execution := <-resultCh
	if execution.err == nil {
		t.Fatal("expected error from invalid toss result")
	}

	doneMatches := make(map[int]struct{}, 2)
	for len(doneMatches) < 2 {
		doneMatches[<-done] = struct{}{}
	}
	if len(doneMatches) != 2 {
		t.Fatalf("expected both match goroutines to finish, got %d", len(doneMatches))
	}
}

func assertRoundPlayerUsage(t *testing.T, results []MatchResult, want []int) {
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
