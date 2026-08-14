package round_test

import (
	"testing"

	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/match"
	"odds-and-evens-game/championship/round"
)

func TestPlayRoundWithTwoPlayers(t *testing.T) {
	players := mustPlayers(t, 2)

	winners, results, err := round.PlayRound(1, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer domain.Player) match.CoinTosser {
		return match.NewFixedCoinTosser(domain.Heads)
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

	winners, results, err := round.PlayRound(2, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer domain.Player) match.CoinTosser {
		if matchNumber == 1 {
			return match.NewFixedCoinTosser(domain.Heads)
		}
		return match.NewFixedCoinTosser(domain.Tails)
	})
	if err != nil {
		t.Fatalf("expected round to succeed, got error: %v", err)
	}
	assertWinnerIDs(t, winners, []int{1, 4})
	assertMatchNumbers(t, results, []int{1, 2})
}

func TestPlayRoundWithEightPlayers(t *testing.T) {
	players := mustPlayers(t, 8)

	winners, results, err := round.PlayRound(3, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer domain.Player) match.CoinTosser {
		if matchNumber%2 == 0 {
			return match.NewFixedCoinTosser(domain.Heads)
		}
		return match.NewFixedCoinTosser(domain.Tails)
	})
	if err != nil {
		t.Fatalf("expected round to succeed, got error: %v", err)
	}
	assertWinnerIDs(t, winners, []int{2, 3, 6, 7})
	assertMatchNumbers(t, results, []int{1, 2, 3, 4})
}

func TestPlayRoundRejectsZeroPlayers(t *testing.T) {
	_, _, err := round.PlayRound(1, nil, round.NewRandomCoinTosserFactory())
	if err == nil {
		t.Fatal("expected error for zero players")
	}
}

func TestPlayRoundRejectsOddNumberOfPlayers(t *testing.T) {
	players := mustPlayers(t, 3)

	_, _, err := round.PlayRound(1, players, round.NewRandomCoinTosserFactory())
	if err == nil {
		t.Fatal("expected error for odd number of players")
	}
}

func TestPlayRoundReportsMatchError(t *testing.T) {
	players := mustPlayers(t, 4)

	_, _, err := round.PlayRound(7, players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer domain.Player) match.CoinTosser {
		if matchNumber == 2 {
			return match.NewFixedCoinTosser(domain.CoinSide("edge"))
		}
		return match.NewFixedCoinTosser(domain.Heads)
	})
	if err == nil {
		t.Fatal("expected error from invalid match result")
	}
}

func mustPlayers(t *testing.T, count int) []domain.Player {
	t.Helper()

	players := make([]domain.Player, count)
	for i := 0; i < count; i++ {
		player, err := domain.NewPlayer(i+1, "Player")
		if err != nil {
			t.Fatalf("expected valid player, got error: %v", err)
		}
		players[i] = player
	}

	return players
}

func assertWinnerIDs(t *testing.T, winners []domain.Player, want []int) {
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

func assertMatchNumbers(t *testing.T, results []domain.MatchResult, want []int) {
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
