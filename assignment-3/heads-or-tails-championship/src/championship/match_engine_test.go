package championship

import "testing"

func TestPlayMatchHeadsResult(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	result, err := PlayMatch(3, 7, NewFixedCoinTosser(Heads), firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if result.TossedSide() != Heads {
		t.Fatalf("unexpected tossed side: %q", result.TossedSide())
	}
}

func TestPlayMatchTailsResult(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	result, err := PlayMatch(3, 7, NewFixedCoinTosser(Tails), firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if result.TossedSide() != Tails {
		t.Fatalf("unexpected tossed side: %q", result.TossedSide())
	}
}

func TestPlayMatchCorrectWinner(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	headsResult, err := PlayMatch(3, 7, NewFixedCoinTosser(Heads), firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if headsResult.Winner().ID() != firstPlayer.ID() {
		t.Fatalf("unexpected winner for heads: got %d want %d", headsResult.Winner().ID(), firstPlayer.ID())
	}

	tailsResult, err := PlayMatch(3, 7, NewFixedCoinTosser(Tails), firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if tailsResult.Winner().ID() != secondPlayer.ID() {
		t.Fatalf("unexpected winner for tails: got %d want %d", tailsResult.Winner().ID(), secondPlayer.ID())
	}
}

func TestPlayMatchRoundMetadata(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	result, err := PlayMatch(4, 9, NewFixedCoinTosser(Heads), firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if result.RoundNumber() != 4 {
		t.Fatalf("unexpected round number: got %d want %d", result.RoundNumber(), 4)
	}
}

func TestPlayMatchMatchMetadata(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	result, err := PlayMatch(4, 9, NewFixedCoinTosser(Heads), firstPlayer, secondPlayer)
	if err != nil {
		t.Fatalf("expected match to succeed, got error: %v", err)
	}
	if result.MatchNumber() != 9 {
		t.Fatalf("unexpected match number: got %d want %d", result.MatchNumber(), 9)
	}
}

func TestPlayMatchInvalidTossResult(t *testing.T) {
	firstPlayer := mustPlayer(t, 1, "Alice")
	secondPlayer := mustPlayer(t, 2, "Bob")

	_, err := PlayMatch(4, 9, NewFixedCoinTosser("edge"), firstPlayer, secondPlayer)
	if err == nil {
		t.Fatal("expected error for invalid toss result")
	}
}

func TestPlayMatchInvalidPlayer(t *testing.T) {
	validPlayer := mustPlayer(t, 2, "Bob")

	_, err := PlayMatch(4, 9, NewFixedCoinTosser(Heads), Player{}, validPlayer)
	if err == nil {
		t.Fatal("expected error for invalid player")
	}
}

func mustPlayer(t *testing.T, id int, name string) Player {
	t.Helper()

	player, err := NewPlayer(id, name)
	if err != nil {
		t.Fatalf("expected valid player, got error: %v", err)
	}

	return player
}
