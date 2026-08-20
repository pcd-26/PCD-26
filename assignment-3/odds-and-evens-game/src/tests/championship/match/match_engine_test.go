package match_test

import (
	"testing"

	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/match"
)

// The match engine should return odd when the parity draw says odd.
func TestPlayMatchOddResult(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	result, err := match.PlayMatch(3, 7, func() domain.Parity { return domain.Odd }, firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if result.Parity() != domain.Odd {
		t.Fatalf("unexpected parity: %q", result.Parity())
	}
}

// The match engine should return even when the parity draw says even.
func TestPlayMatchEvenResult(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	result, err := match.PlayMatch(3, 7, func() domain.Parity { return domain.Even }, firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if result.Parity() != domain.Even {
		t.Fatalf("unexpected parity: %q", result.Parity())
	}
}

// Winner selection depends only on the parity result.
func TestPlayMatchCorrectWinner(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	oddResult, err := match.PlayMatch(3, 7, func() domain.Parity { return domain.Odd }, firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if oddResult.Winner().ID() != firstPlayer.ID() {
		t.Fatalf("unexpected winner for odd: got %d want %d", oddResult.Winner().ID(), firstPlayer.ID())
	}

	evenResult, err := match.PlayMatch(3, 7, func() domain.Parity { return domain.Even }, firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if evenResult.Winner().ID() != secondPlayer.ID() {
		t.Fatalf("unexpected winner for even: got %d want %d", evenResult.Winner().ID(), secondPlayer.ID())
	}
}

// Match metadata must be preserved in the result.
func TestPlayMatchRoundMetadata(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	result, err := match.PlayMatch(4, 9, func() domain.Parity { return domain.Odd }, firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if result.RoundNumber() != 4 {
		t.Fatalf("unexpected round number: got %d want %d", result.RoundNumber(), 4)
	}
}

// Match metadata must keep the original match number too.
func TestPlayMatchMatchMetadata(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	result, err := match.PlayMatch(4, 9, func() domain.Parity { return domain.Odd }, firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if result.MatchNumber() != 9 {
		t.Fatalf("unexpected match number: got %d want %d", result.MatchNumber(), 9)
	}
}

// Invalid parity values must be rejected.
func TestPlayMatchInvalidParityResult(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	_, err := match.PlayMatch(4, 9, func() domain.Parity { return domain.Parity("edge") }, firstPlayer, secondPlayer)
	if err == nil {
		t.Fatal("expected error for invalid parity result")
	}
}

// Invalid players must be rejected before the parity function is used.
func TestPlayMatchInvalidPlayer(t *testing.T) {
	validPlayer := mustPlayer(t, 2, "Bob")

	_, err := match.PlayMatch(4, 9, func() domain.Parity { return domain.Odd }, domain.Player{}, validPlayer)
	if err == nil {
		t.Fatal("expected error for invalid player")
	}
}

// mustPlayer is a small test helper that builds a valid player.
func mustPlayer(t *testing.T, id int, name string) domain.Player {
	t.Helper()

	player, err := domain.NewPlayer(id, name)
	if err != nil {
		t.Fatalf("expected valid player, got error: %v", err)
	}

	return player
}
