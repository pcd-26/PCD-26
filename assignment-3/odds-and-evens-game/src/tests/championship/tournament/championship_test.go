package tournament_test

import (
	"testing"

	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/tournament"
)

// A one-player championship should end immediately.
func TestPlayChampionshipWithOnePlayer(t *testing.T) {
	players := mustPlayers(t, 1)

	result, err := tournament.PlayChampionship(players, func() domain.CoinSide { return domain.Heads })
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 0, 0)
}

// Two players means one round and one match.
func TestPlayChampionshipWithTwoPlayers(t *testing.T) {
	players := mustPlayers(t, 2)

	result, err := tournament.PlayChampionship(players, func() domain.CoinSide { return domain.Heads })
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 1, 1)
	assertRoundSizes(t, result, []int{1})
}

// Four players should produce two rounds and three matches.
func TestPlayChampionshipWithFourPlayers(t *testing.T) {
	players := mustPlayers(t, 4)

	result, err := tournament.PlayChampionship(players, func() domain.CoinSide { return domain.Heads })
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 2, 3)
	assertRoundSizes(t, result, []int{2, 1})
}

// Eight players should produce three rounds and seven matches.
func TestPlayChampionshipWithEightPlayers(t *testing.T) {
	players := mustPlayers(t, 8)

	result, err := tournament.PlayChampionship(players, func() domain.CoinSide { return domain.Heads })
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 3, 7)
	assertRoundSizes(t, result, []int{4, 2, 1})
}

// Zero players is not a valid championship.
func TestPlayChampionshipRejectsZeroPlayers(t *testing.T) {
	_, err := tournament.PlayChampionship(nil, func() domain.CoinSide { return domain.Heads })
	if err == nil {
		t.Fatal("expected error for zero players")
	}
}

// The tournament only accepts power-of-two player counts.
func TestPlayChampionshipRejectsNonPowerOfTwoPlayers(t *testing.T) {
	players := mustPlayers(t, 3)

	_, err := tournament.PlayChampionship(players, func() domain.CoinSide { return domain.Heads })
	if err == nil {
		t.Fatal("expected error for non power of two player count")
	}
}

// A bad match result must stop the whole tournament.
func TestPlayChampionshipPropagatesMatchError(t *testing.T) {
	players := mustPlayers(t, 4)

	_, err := tournament.PlayChampionship(players, func() domain.CoinSide { return domain.CoinSide("edge") })
	if err == nil {
		t.Fatal("expected error from invalid match result")
	}
}

// The final result should always contain a valid champion.
func TestPlayChampionshipChampionCount(t *testing.T) {
	players := mustPlayers(t, 8)

	result, err := tournament.PlayChampionship(players, func() domain.CoinSide { return domain.Heads })
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

// Each round should halve the number of active players.
func TestPlayChampionshipRoundProgression(t *testing.T) {
	players := mustPlayers(t, 8)

	result, err := tournament.PlayChampionship(players, func() domain.CoinSide { return domain.Heads })
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

// The winner list should match the bracket progression.
func TestPlayChampionshipPropagatesWinnersAndHalvesPlayers(t *testing.T) {
	players := mustPlayers(t, 8)

	result, err := tournament.PlayChampionship(players, func() domain.CoinSide { return domain.Heads })
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

// assertChampionID checks the final winner.
func assertChampionID(t *testing.T, result domain.ChampionshipResult, want int) {
	t.Helper()

	if result.Champion().ID() != want {
		t.Fatalf("unexpected champion: got %d want %d", result.Champion().ID(), want)
	}
}

// assertRoundsAndMatches checks the tournament shape.
func assertRoundsAndMatches(t *testing.T, result domain.ChampionshipResult, wantRounds, wantMatches int) {
	t.Helper()

	if result.TotalRounds() != wantRounds {
		t.Fatalf("unexpected rounds count: got %d want %d", result.TotalRounds(), wantRounds)
	}
	if result.TotalMatches() != wantMatches {
		t.Fatalf("unexpected matches count: got %d want %d", result.TotalMatches(), wantMatches)
	}
}

// assertRoundSizes checks how many matches each round has.
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

// assertRoundParticipants checks the exact participant order for a round.
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

// assertRoundWinners checks the exact winners order for a round.
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

// roundParticipants flattens the players involved in all matches.
func roundParticipants(roundResult domain.RoundResult) []int {
	participants := make([]int, 0, roundResult.MatchCount()*2)
	for _, m := range roundResult.Matches() {
		participants = append(participants, m.FirstPlayer().ID(), m.SecondPlayer().ID())
	}
	return participants
}

// mustPlayers builds a valid player list for the tournament tests.
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
