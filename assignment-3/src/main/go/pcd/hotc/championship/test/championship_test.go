package test

import (
	"fmt"
	"sync"
	"testing"
	"time"

	"pcd/hotc/championship"
)

// TestRunChampionshipValid tests that the championship runs successfully
// and returns a valid player ID for various tournament sizes (m = 1, 2, 3, 4).
func TestRunChampionshipValid(t *testing.T) {
	rounds := []int{1, 2, 3, 4}
	for _, m := range rounds {
		t.Run(fmt.Sprintf("Rounds_%d", m), func(t *testing.T) {
			numPlayers := 1 << m
			winnerID, err := championship.RunChampionship(m)
			if err != nil {
				t.Fatalf("Expected no error for m = %d, got %v", m, err)
			}
			if winnerID < 1 || winnerID > numPlayers {
				t.Errorf("Expected winner ID to be between 1 and %d, got %d", numPlayers, winnerID)
			}
		})
	}
}

// TestRunChampionshipInvalid tests that the championship correctly returns an error
// when given an invalid number of rounds (e.g. m < 1).
func TestRunChampionshipInvalid(t *testing.T) {
	invalidRounds := []int{0, -1, -5}
	for _, m := range invalidRounds {
		t.Run(fmt.Sprintf("InvalidRounds_%d", m), func(t *testing.T) {
			_, err := championship.RunChampionship(m)
			if err == nil {
				t.Errorf("Expected error for m = %d, got nil", m)
			}
		})
	}
}

// TestTypesStrings tests the string representations of Choice and GameResult.
func TestTypesStrings(t *testing.T) {
	// Choice String tests
	if championship.Heads.String() != "Heads" {
		t.Errorf("Expected Heads.String() to be \"Heads\", got %q", championship.Heads.String())
	}
	if championship.Tails.String() != "Tails" {
		t.Errorf("Expected Tails.String() to be \"Tails\", got %q", championship.Tails.String())
	}
	if championship.Choice(99).String() != "Tails" {
		t.Errorf("Expected invalid Choice(99).String() to return \"Tails\", got %q", championship.Choice(99).String())
	}

	// GameResult String tests
	if championship.Win.String() != "Win" {
		t.Errorf("Expected Win.String() to be \"Win\", got %q", championship.Win.String())
	}
	if championship.Lose.String() != "Lose" {
		t.Errorf("Expected Lose.String() to be \"Lose\", got %q", championship.Lose.String())
	}
	if championship.Tie.String() != "Tie" {
		t.Errorf("Expected Tie.String() to be \"Tie\", got %q", championship.Tie.String())
	}
	if championship.GameResult(-1).String() != "Unknown" {
		t.Errorf("Expected invalid GameResult(-1).String() to return \"Unknown\", got %q", championship.GameResult(-1).String())
	}
}

// TestPlayerLifecycle tests the lifecycle of a player: responding to requests,
// staying alive on Tie/Win, and exiting on Lose or channel closure.
func TestPlayerLifecycle(t *testing.T) {
	reqChan := make(chan championship.Request)
	var wg sync.WaitGroup
	wg.Add(1)

	// Start Player in a goroutine
	go championship.Player(1, reqChan, &wg)

	respChan := make(chan championship.Choice)
	feedChan := make(chan championship.GameResult)

	// --- Round 1: Tie ---
	go func() {
		reqChan <- championship.Request{ResponseChan: respChan, FeedbackChan: feedChan}
	}()

	var choice championship.Choice
	select {
	case choice = <-respChan:
		if choice != championship.Heads && choice != championship.Tails {
			t.Errorf("Expected choice to be Heads or Tails, got %v", choice)
		}
	case <-time.After(1 * time.Second):
		t.Fatal("Timeout waiting for player's choice in Round 1")
	}

	// Player receives a Tie, should stay in loop
	feedChan <- championship.Tie

	// --- Round 2: Win ---
	go func() {
		reqChan <- championship.Request{ResponseChan: respChan, FeedbackChan: feedChan}
	}()

	select {
	case choice = <-respChan:
		if choice != championship.Heads && choice != championship.Tails {
			t.Errorf("Expected choice to be Heads or Tails, got %v", choice)
		}
	case <-time.After(1 * time.Second):
		t.Fatal("Timeout waiting for player's choice in Round 2")
	}

	// Player receives a Win, should stay in loop
	feedChan <- championship.Win

	// --- Round 3: Lose ---
	go func() {
		reqChan <- championship.Request{ResponseChan: respChan, FeedbackChan: feedChan}
	}()

	select {
	case choice = <-respChan:
		if choice != championship.Heads && choice != championship.Tails {
			t.Errorf("Expected choice to be Heads or Tails, got %v", choice)
		}
	case <-time.After(1 * time.Second):
		t.Fatal("Timeout waiting for player's choice in Round 3")
	}

	// Player receives a Lose, should terminate
	feedChan <- championship.Lose

	// Wait for player goroutine to terminate
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		// Exited successfully
	case <-time.After(2 * time.Second):
		t.Fatal("Timeout waiting for player goroutine to exit after receiving Lose")
	}
}

// TestPlayerExitOnChannelClose tests that a Player goroutine exits naturally
// when its request channel is closed.
func TestPlayerExitOnChannelClose(t *testing.T) {
	reqChan := make(chan championship.Request)
	var wg sync.WaitGroup
	wg.Add(1)

	go championship.Player(2, reqChan, &wg)

	// Close request channel
	close(reqChan)

	// Wait for player goroutine to terminate
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		// Exited successfully
	case <-time.After(2 * time.Second):
		t.Fatal("Timeout waiting for player goroutine to exit after channel close")
	}
}

// TestRunMatch_Success tests a normal, non-tied match between two players.
func TestRunMatch_Success(t *testing.T) {
	p1Req := make(chan championship.Request)
	p2Req := make(chan championship.Request)
	winnerChan := make(chan championship.PlayerInfo, 1)

	p1 := championship.PlayerInfo{ID: 1, ReqChan: p1Req}
	p2 := championship.PlayerInfo{ID: 2, ReqChan: p2Req}

	// Start match referee
	go championship.RunMatch(p1, p2, winnerChan)

	// Await requests from the referee to both players
	var r1, r2 championship.Request
	errChan := make(chan error, 2)
	go func() {
		select {
		case r1 = <-p1Req:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p1Req")
		}
	}()
	go func() {
		select {
		case r2 = <-p2Req:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p2Req")
		}
	}()

	for i := 0; i < 2; i++ {
		if err := <-errChan; err != nil {
			t.Fatal(err)
		}
	}

	// Send opposite choices: P1 Heads, P2 Tails.
	// This guarantees one matches the referee's coin and one does not.
	go func() { r1.ResponseChan <- championship.Heads }()
	go func() { r2.ResponseChan <- championship.Tails }()

	// Await match outcomes for both players
	var outcome1, outcome2 championship.GameResult
	go func() {
		select {
		case outcome1 = <-r1.FeedbackChan:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p1 feedback")
		}
	}()
	go func() {
		select {
		case outcome2 = <-r2.FeedbackChan:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p2 feedback")
		}
	}()

	for i := 0; i < 2; i++ {
		if err := <-errChan; err != nil {
			t.Fatal(err)
		}
	}

	// Get winner from winnerChan
	var winner championship.PlayerInfo
	select {
	case winner = <-winnerChan:
	case <-time.After(2 * time.Second):
		t.Fatal("Timeout waiting for winner on winnerChan")
	}

	// Assert outcomes and winner
	if outcome1 == championship.Win {
		if outcome2 != championship.Lose {
			t.Errorf("Expected P2 to lose when P1 won, got P2: %v", outcome2)
		}
		if winner.ID != p1.ID {
			t.Errorf("Expected winner to be P1, got Player %d", winner.ID)
		}
	} else if outcome2 == championship.Win {
		if outcome1 != championship.Lose {
			t.Errorf("Expected P1 to lose when P2 won, got P1: %v", outcome1)
		}
		if winner.ID != p2.ID {
			t.Errorf("Expected winner to be P2, got Player %d", winner.ID)
		}
	} else {
		t.Errorf("Expected one player to win, got outcomes P1: %v, P2: %v", outcome1, outcome2)
	}
}

// TestRunMatch_TieAndRematch tests that matching choices result in a Tie feedback
// and a subsequent request to resolve the match.
func TestRunMatch_TieAndRematch(t *testing.T) {
	p1Req := make(chan championship.Request)
	p2Req := make(chan championship.Request)
	winnerChan := make(chan championship.PlayerInfo, 1)

	p1 := championship.PlayerInfo{ID: 1, ReqChan: p1Req}
	p2 := championship.PlayerInfo{ID: 2, ReqChan: p2Req}

	// Start match referee
	go championship.RunMatch(p1, p2, winnerChan)

	// --- Round 1: Force a Tie ---
	var r1, r2 championship.Request
	errChan := make(chan error, 2)
	go func() {
		select {
		case r1 = <-p1Req:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p1Req (Round 1)")
		}
	}()
	go func() {
		select {
		case r2 = <-p2Req:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p2Req (Round 1)")
		}
	}()

	for i := 0; i < 2; i++ {
		if err := <-errChan; err != nil {
			t.Fatal(err)
		}
	}

	// Both choose Heads -> guarantees a Tie regardless of what the referee flips
	go func() { r1.ResponseChan <- championship.Heads }()
	go func() { r2.ResponseChan <- championship.Heads }()

	// Await outcomes
	var outcome1, outcome2 championship.GameResult
	go func() {
		select {
		case outcome1 = <-r1.FeedbackChan:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p1 feedback (Round 1)")
		}
	}()
	go func() {
		select {
		case outcome2 = <-r2.FeedbackChan:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p2 feedback (Round 1)")
		}
	}()

	for i := 0; i < 2; i++ {
		if err := <-errChan; err != nil {
			t.Fatal(err)
		}
	}

	if outcome1 != championship.Tie || outcome2 != championship.Tie {
		t.Fatalf("Expected Tie for both players in Round 1, got P1: %v, P2: %v", outcome1, outcome2)
	}

	// --- Round 2: Resolve the match ---
	// The referee should issue new requests immediately after a Tie
	go func() {
		select {
		case r1 = <-p1Req:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p1Req (Round 2)")
		}
	}()
	go func() {
		select {
		case r2 = <-p2Req:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p2Req (Round 2)")
		}
	}()

	for i := 0; i < 2; i++ {
		if err := <-errChan; err != nil {
			t.Fatal(err)
		}
	}

	// Opposite choices -> guarantees resolution
	go func() { r1.ResponseChan <- championship.Heads }()
	go func() { r2.ResponseChan <- championship.Tails }()

	// Await final outcomes
	go func() {
		select {
		case outcome1 = <-r1.FeedbackChan:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p1 feedback (Round 2)")
		}
	}()
	go func() {
		select {
		case outcome2 = <-r2.FeedbackChan:
			errChan <- nil
		case <-time.After(2 * time.Second):
			errChan <- fmt.Errorf("timeout waiting for p2 feedback (Round 2)")
		}
	}()

	for i := 0; i < 2; i++ {
		if err := <-errChan; err != nil {
			t.Fatal(err)
		}
	}

	// Get winner from winnerChan
	var winner championship.PlayerInfo
	select {
	case winner = <-winnerChan:
	case <-time.After(2 * time.Second):
		t.Fatal("Timeout waiting for winner in Round 2")
	}

	// Assert outcomes and winner
	if outcome1 == championship.Win {
		if outcome2 != championship.Lose {
			t.Errorf("Expected P2 to lose, got %v", outcome2)
		}
		if winner.ID != p1.ID {
			t.Errorf("Expected winner to be P1, got Player %d", winner.ID)
		}
	} else if outcome2 == championship.Win {
		if outcome1 != championship.Lose {
			t.Errorf("Expected P1 to lose, got %v", outcome1)
		}
		if winner.ID != p2.ID {
			t.Errorf("Expected winner to be P2, got Player %d", winner.ID)
		}
	} else {
		t.Errorf("Expected one player to win, got outcomes P1: %v, P2: %v", outcome1, outcome2)
	}
}
