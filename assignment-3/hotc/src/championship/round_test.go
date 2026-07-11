package championship

import "testing"

func TestPlayRoundWithTwoPlayers(t *testing.T) {
	players := mustPlayers(t, 2)

	winners, results, err := PlayRound(1, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser {
		return NewFixedCoinTosser(Heads)
	})
	if err != nil {
		t.Fatalf("expected round to succeed, got error: %v", err)
	}
	if len(winners) != 1 {
		t.Fatalf("unexpected winners count: got %d want %d", len(winners), 1)
	}
	if len(results) != 1 {
		t.Fatalf("unexpected results count: got %d want %d", len(results), 1)
	}
	if winners[0].ID() != players[0].ID() {
		t.Fatalf("unexpected winner: got %d want %d", winners[0].ID(), players[0].ID())
	}
}

func TestPlayRoundWithFourPlayers(t *testing.T) {
	players := mustPlayers(t, 4)

	winners, results, err := PlayRound(2, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser {
		if matchNumber == 1 {
			return NewFixedCoinTosser(Heads)
		}
		return NewFixedCoinTosser(Tails)
	})
	if err != nil {
		t.Fatalf("expected round to succeed, got error: %v", err)
	}
	assertWinnerIDs(t, winners, []int{1, 4})
	assertMatchNumbers(t, results, []int{1, 2})
}

func TestPlayRoundWithEightPlayers(t *testing.T) {
	players := mustPlayers(t, 8)

	winners, results, err := PlayRound(3, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser {
		if matchNumber%2 == 0 {
			return NewFixedCoinTosser(Heads)
		}
		return NewFixedCoinTosser(Tails)
	})
	if err != nil {
		t.Fatalf("expected round to succeed, got error: %v", err)
	}
	assertWinnerIDs(t, winners, []int{2, 3, 6, 7})
	assertMatchNumbers(t, results, []int{1, 2, 3, 4})
}

func TestPlayRoundRejectsZeroPlayers(t *testing.T) {
	_, _, err := PlayRound(1, nil, NewRandomCoinTosserFactory())
	if err == nil {
		t.Fatal("expected error for zero players")
	}
}

func TestPlayRoundRejectsOddNumberOfPlayers(t *testing.T) {
	players := mustPlayers(t, 3)

	_, _, err := PlayRound(1, players, NewRandomCoinTosserFactory())
	if err == nil {
		t.Fatal("expected error for odd number of players")
	}
}

func TestPlayRoundReportsMatchError(t *testing.T) {
	players := mustPlayers(t, 4)

	_, _, err := PlayRound(7, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser {
		if matchNumber == 2 {
			return NewFixedCoinTosser(CoinSide("edge"))
		}
		return NewFixedCoinTosser(Heads)
	})
	if err == nil {
		t.Fatal("expected error from invalid match result")
	}
}

func mustPlayers(t *testing.T, count int) []Player {
	t.Helper()

	players := make([]Player, count)
	for i := 0; i < count; i++ {
		player, err := NewPlayer(i+1, "Player")
		if err != nil {
			t.Fatalf("expected valid player, got error: %v", err)
		}
		players[i] = player
	}

	return players
}

func assertWinnerIDs(t *testing.T, winners []Player, want []int) {
	t.Helper()

	if len(winners) != len(want) {
		t.Fatalf("unexpected winners count: got %d want %d", len(winners), len(want))
	}
	for i, winner := range winners {
		if winner.ID() != want[i] {
			t.Fatalf("unexpected winner at index %d: got %d want %d", i, winner.ID(), want[i])
		}
	}
}

func assertMatchNumbers(t *testing.T, results []MatchResult, want []int) {
	t.Helper()

	if len(results) != len(want) {
		t.Fatalf("unexpected results count: got %d want %d", len(results), len(want))
	}
	for i, result := range results {
		if result.MatchNumber() != want[i] {
			t.Fatalf("unexpected match number at index %d: got %d want %d", i, result.MatchNumber(), want[i])
		}
	}
}
