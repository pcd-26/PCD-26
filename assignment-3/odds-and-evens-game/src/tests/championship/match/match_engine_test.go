package match_test

import (
	"testing"

	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/match"
)

// The match engine should return heads when the toss says heads.
func TestPlayMatchHeadsResult(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	result, err := match.PlayMatch(3, 7, func() domain.CoinSide { return domain.Heads }, firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if result.TossedSide() != domain.Heads {
		t.Fatalf("unexpected tossed side: %q", result.TossedSide())
	}
}

// The match engine should return tails when the toss says tails.
func TestPlayMatchTailsResult(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	result, err := match.PlayMatch(3, 7, func() domain.CoinSide { return domain.Tails }, firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if result.TossedSide() != domain.Tails {
		t.Fatalf("unexpected tossed side: %q", result.TossedSide())
	}
}

// Winner selection depends only on the toss result.
func TestPlayMatchCorrectWinner(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	headsResult, err := match.PlayMatch(3, 7, func() domain.CoinSide { return domain.Heads }, firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if headsResult.Winner().ID() != firstPlayer.ID() {
		t.Fatalf("unexpected winner for heads: got %d want %d", headsResult.Winner().ID(), firstPlayer.ID())
	}

	tailsResult, err := match.PlayMatch(3, 7, func() domain.CoinSide { return domain.Tails }, firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if tailsResult.Winner().ID() != secondPlayer.ID() {
		t.Fatalf("unexpected winner for tails: got %d want %d", tailsResult.Winner().ID(), secondPlayer.ID())
	}
}

// Match metadata must be preserved in the result.
func TestPlayMatchRoundMetadata(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	result, err := match.PlayMatch(4, 9, func() domain.CoinSide { return domain.Heads }, firstPlayer, secondPlayer)
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

	result, err := match.PlayMatch(4, 9, func() domain.CoinSide { return domain.Heads }, firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if result.MatchNumber() != 9 {
		t.Fatalf("unexpected match number: got %d want %d", result.MatchNumber(), 9)
	}
}

// Invalid toss values must be rejected.
func TestPlayMatchInvalidTossResult(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	_, err := match.PlayMatch(4, 9, func() domain.CoinSide { return domain.CoinSide("edge") }, firstPlayer, secondPlayer)
	if err == nil {
		t.Fatal("expected error for invalid toss result")
	}
}

// Invalid players must be rejected before the toss is used.
func TestPlayMatchInvalidPlayer(t *testing.T) {
	validPlayer := mustPlayer(t, 2, "Bob")

	_, err := match.PlayMatch(4, 9, func() domain.CoinSide { return domain.Heads }, domain.Player{}, validPlayer)
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
