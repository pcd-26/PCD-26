package tournament_test

import (
	"testing"

	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/match"
	"odds-and-evens-game/championship/round"
	"odds-and-evens-game/championship/tournament"
)

func TestPlayChampionshipWithOnePlayer(t *testing.T) {
	players := mustPlayers(t, 1)

	result, err := tournament.PlayChampionship(players, round.NewRandomCoinTosserFactory())
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 0, 0)
}

func TestPlayChampionshipWithTwoPlayers(t *testing.T) {
	players := mustPlayers(t, 2)

	result, err := tournament.PlayChampionship(players, headsOnlyFactory)
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 1, 1)
	assertRoundSizes(t, result, []int{1})
}

func TestPlayChampionshipWithFourPlayers(t *testing.T) {
	players := mustPlayers(t, 4)

	result, err := tournament.PlayChampionship(players, headsOnlyFactory)
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 2, 3)
	assertRoundSizes(t, result, []int{2, 1})
}

func TestPlayChampionshipWithEightPlayers(t *testing.T) {
	players := mustPlayers(t, 8)

	result, err := tournament.PlayChampionship(players, headsOnlyFactory)
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 3, 7)
	assertRoundSizes(t, result, []int{4, 2, 1})
}

func TestPlayChampionshipRejectsZeroPlayers(t *testing.T) {
	_, err := tournament.PlayChampionship(nil, headsOnlyFactory)
	if err == nil {
		t.Fatal("expected error for zero players")
	}
}

func TestPlayChampionshipRejectsNonPowerOfTwoPlayers(t *testing.T) {
	players := mustPlayers(t, 3)

	_, err := tournament.PlayChampionship(players, headsOnlyFactory)
	if err == nil {
		t.Fatal("expected error for non power of two player count")
	}
}

func TestPlayChampionshipPropagatesMatchError(t *testing.T) {
	players := mustPlayers(t, 4)

	_, err := tournament.PlayChampionship(players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer domain.Player) match.CoinTosser {
		if roundNumber == 1 && matchNumber == 2 {
			return match.NewFixedCoinTosser(domain.CoinSide("edge"))
		}
		return match.NewFixedCoinTosser(domain.Heads)
	})
	if err == nil {
		t.Fatal("expected error from invalid match result")
	}
}

func TestPlayChampionshipChampionCount(t *testing.T) {
	players := mustPlayers(t, 8)

	result, err := tournament.PlayChampionship(players, headsOnlyFactory)
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	if result.Champion().ID() == 0 {
		t.Fatal("expected a valid champion")
	}
	if len(result.Rounds()) != 3 {
		t.Fatalf("unexpected number of rounds: got %d want %d", len(result.Rounds()), 3)
	}
}

func TestPlayChampionshipRoundProgression(t *testing.T) {
	players := mustPlayers(t, 8)

	result, err := tournament.PlayChampionship(players, headsOnlyFactory)
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}

	expectedSizes := []int{4, 2, 1}
	for i, roundResult := range result.Rounds() {
		if roundResult.MatchCount() != expectedSizes[i] {
			t.Fatalf("unexpected match count at round %d: got %d want %d", i+1, roundResult.MatchCount(), expectedSizes[i])
		}
		if len(roundResult.Winners()) != expectedSizes[i] {
			t.Fatalf("unexpected winners count at round %d: got %d want %d", i+1, len(roundResult.Winners()), expectedSizes[i])
		}
	}
}

func TestPlayChampionshipPropagatesWinnersAndHalvesPlayers(t *testing.T) {
	players := mustPlayers(t, 8)

	result, err := tournament.PlayChampionship(players, headsOnlyFactory)
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}

	rounds := result.Rounds()
	if len(rounds) != 3 {
		t.Fatalf("unexpected round count: got %d want %d", len(rounds), 3)
	}

	expectedParticipants := [][]int{
		{1, 2, 3, 4, 5, 6, 7, 8},
		{1, 3, 5, 7},
		{1, 5},
	}
	expectedWinners := [][]int{
		{1, 3, 5, 7},
		{1, 5},
		{1},
	}

	for roundIndex, roundResult := range rounds {
		assertRoundParticipants(t, roundResult, expectedParticipants[roundIndex])
		assertRoundWinners(t, roundResult, expectedWinners[roundIndex])
		if len(roundResult.Winners())*2 != len(expectedParticipants[roundIndex]) {
			t.Fatalf("round %d did not halve players: participants=%d winners=%d", roundIndex+1, len(expectedParticipants[roundIndex]), len(roundResult.Winners()))
		}
	}

	if result.Champion().ID() != 1 {
		t.Fatalf("unexpected champion: got %d want %d", result.Champion().ID(), 1)
	}
}

func headsOnlyFactory(roundNumber, matchNumber int, firstPlayer, secondPlayer domain.Player) match.CoinTosser {
	return match.NewFixedCoinTosser(domain.Heads)
}

func assertChampionID(t *testing.T, result domain.ChampionshipResult, want int) {
	t.Helper()

	if result.Champion().ID() != want {
		t.Fatalf("unexpected champion: got %d want %d", result.Champion().ID(), want)
	}
}

func assertRoundsAndMatches(t *testing.T, result domain.ChampionshipResult, wantRounds, wantMatches int) {
	t.Helper()

	if result.TotalRounds() != wantRounds {
		t.Fatalf("unexpected rounds count: got %d want %d", result.TotalRounds(), wantRounds)
	}
	if result.TotalMatches() != wantMatches {
		t.Fatalf("unexpected matches count: got %d want %d", result.TotalMatches(), wantMatches)
	}
}

func assertRoundSizes(t *testing.T, result domain.ChampionshipResult, want []int) {
	t.Helper()

	rounds := result.Rounds()
	if len(rounds) != len(want) {
		t.Fatalf("unexpected round count: got %d want %d", len(rounds), len(want))
	}
	for i, roundResult := range rounds {
		if roundResult.MatchCount() != want[i] {
			t.Fatalf("unexpected match count at round %d: got %d want %d", i+1, roundResult.MatchCount(), want[i])
		}
	}
}

func assertRoundParticipants(t *testing.T, roundResult domain.RoundResult, want []int) {
	t.Helper()

	got := roundParticipants(roundResult)
	if len(got) != len(want) {
		t.Fatalf("unexpected participant count in round %d: got %d want %d", roundResult.RoundNumber(), len(got), len(want))
	}
	for i, playerID := range want {
		if got[i] != playerID {
			t.Fatalf("unexpected participant at position %d in round %d: got %d want %d", i, roundResult.RoundNumber(), got[i], playerID)
		}
	}
}

func assertRoundWinners(t *testing.T, roundResult domain.RoundResult, want []int) {
	t.Helper()

	got := roundResult.Winners()
	if len(got) != len(want) {
		t.Fatalf("unexpected winner count in round %d: got %d want %d", roundResult.RoundNumber(), len(got), len(want))
	}
	for i, playerID := range want {
		if got[i].ID() != playerID {
			t.Fatalf("unexpected winner at position %d in round %d: got %d want %d", i, roundResult.RoundNumber(), got[i].ID(), playerID)
		}
	}
}

func roundParticipants(roundResult domain.RoundResult) []int {
	participants := make([]int, 0, roundResult.MatchCount()*2)
	for _, m := range roundResult.Matches() {
		participants = append(participants, m.FirstPlayer().ID(), m.SecondPlayer().ID())
	}
	return participants
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
