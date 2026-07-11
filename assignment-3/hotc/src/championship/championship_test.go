package championship

import "testing"

func TestPlayChampionshipWithOnePlayer(t *testing.T) {
	players := mustPlayers(t, 1)

	result, err := PlayChampionship(players, NewRandomCoinTosserFactory())
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 0, 0)
}

func TestPlayChampionshipWithTwoPlayers(t *testing.T) {
	players := mustPlayers(t, 2)

	result, err := PlayChampionship(players, headsOnlyFactory)
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 1, 1)
	assertRoundSizes(t, result, []int{1})
}

func TestPlayChampionshipWithFourPlayers(t *testing.T) {
	players := mustPlayers(t, 4)

	result, err := PlayChampionship(players, headsOnlyFactory)
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 2, 3)
	assertRoundSizes(t, result, []int{2, 1})
}

func TestPlayChampionshipWithEightPlayers(t *testing.T) {
	players := mustPlayers(t, 8)

	result, err := PlayChampionship(players, headsOnlyFactory)
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}
	assertChampionID(t, result, 1)
	assertRoundsAndMatches(t, result, 3, 7)
	assertRoundSizes(t, result, []int{4, 2, 1})
}

func TestPlayChampionshipRejectsZeroPlayers(t *testing.T) {
	_, err := PlayChampionship(nil, headsOnlyFactory)
	if err == nil {
		t.Fatal("expected error for zero players")
	}
}

func TestPlayChampionshipRejectsNonPowerOfTwoPlayers(t *testing.T) {
	players := mustPlayers(t, 3)

	_, err := PlayChampionship(players, headsOnlyFactory)
	if err == nil {
		t.Fatal("expected error for non power of two player count")
	}
}

func TestPlayChampionshipPropagatesMatchError(t *testing.T) {
	players := mustPlayers(t, 4)

	_, err := PlayChampionship(players, func(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser {
		if roundNumber == 1 && matchNumber == 2 {
			return NewFixedCoinTosser(CoinSide("edge"))
		}
		return NewFixedCoinTosser(Heads)
	})
	if err == nil {
		t.Fatal("expected error from invalid match result")
	}
}

func TestPlayChampionshipChampionCount(t *testing.T) {
	players := mustPlayers(t, 8)

	result, err := PlayChampionship(players, headsOnlyFactory)
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

	result, err := PlayChampionship(players, headsOnlyFactory)
	if err != nil {
		t.Fatalf("expected championship to succeed, got error: %v", err)
	}

	expectedSizes := []int{4, 2, 1}
	for i, round := range result.Rounds() {
		if round.MatchCount() != expectedSizes[i] {
			t.Fatalf("unexpected match count at round %d: got %d want %d", i+1, round.MatchCount(), expectedSizes[i])
		}
		if len(round.Winners()) != expectedSizes[i] {
			t.Fatalf("unexpected winners count at round %d: got %d want %d", i+1, len(round.Winners()), expectedSizes[i])
		}
	}
}

func TestPlayChampionshipPropagatesWinnersAndHalvesPlayers(t *testing.T) {
	players := mustPlayers(t, 8)

	result, err := PlayChampionship(players, headsOnlyFactory)
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

	for roundIndex, round := range rounds {
		assertRoundParticipants(t, round, expectedParticipants[roundIndex])
		assertRoundWinners(t, round, expectedWinners[roundIndex])
		if len(round.Winners())*2 != len(expectedParticipants[roundIndex]) {
			t.Fatalf("round %d did not halve players: participants=%d winners=%d", roundIndex+1, len(expectedParticipants[roundIndex]), len(round.Winners()))
		}
	}

	if result.Champion().ID() != 1 {
		t.Fatalf("unexpected champion: got %d want %d", result.Champion().ID(), 1)
	}
}

func headsOnlyFactory(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser {
	return NewFixedCoinTosser(Heads)
}

func assertChampionID(t *testing.T, result ChampionshipResult, want int) {
	t.Helper()

	if result.Champion().ID() != want {
		t.Fatalf("unexpected champion: got %d want %d", result.Champion().ID(), want)
	}
}

func assertRoundsAndMatches(t *testing.T, result ChampionshipResult, wantRounds, wantMatches int) {
	t.Helper()

	if result.TotalRounds() != wantRounds {
		t.Fatalf("unexpected rounds count: got %d want %d", result.TotalRounds(), wantRounds)
	}
	if result.TotalMatches() != wantMatches {
		t.Fatalf("unexpected matches count: got %d want %d", result.TotalMatches(), wantMatches)
	}
}

func assertRoundSizes(t *testing.T, result ChampionshipResult, want []int) {
	t.Helper()

	rounds := result.Rounds()
	if len(rounds) != len(want) {
		t.Fatalf("unexpected round count: got %d want %d", len(rounds), len(want))
	}
	for i, round := range rounds {
		if round.MatchCount() != want[i] {
			t.Fatalf("unexpected match count at round %d: got %d want %d", i+1, round.MatchCount(), want[i])
		}
	}
}

func assertRoundParticipants(t *testing.T, round RoundResult, want []int) {
	t.Helper()

	got := roundParticipants(round)
	if len(got) != len(want) {
		t.Fatalf("unexpected participant count in round %d: got %d want %d", round.RoundNumber(), len(got), len(want))
	}
	for i, playerID := range want {
		if got[i] != playerID {
			t.Fatalf("unexpected participant at position %d in round %d: got %d want %d", i, round.RoundNumber(), got[i], playerID)
		}
	}
}

func assertRoundWinners(t *testing.T, round RoundResult, want []int) {
	t.Helper()

	got := round.Winners()
	if len(got) != len(want) {
		t.Fatalf("unexpected winner count in round %d: got %d want %d", round.RoundNumber(), len(got), len(want))
	}
	for i, playerID := range want {
		if got[i].ID() != playerID {
			t.Fatalf("unexpected winner at position %d in round %d: got %d want %d", i, round.RoundNumber(), got[i].ID(), playerID)
		}
	}
}

func roundParticipants(round RoundResult) []int {
	participants := make([]int, 0, round.MatchCount()*2)
	for _, match := range round.Matches() {
		participants = append(participants, match.FirstPlayer().ID(), match.SecondPlayer().ID())
	}
	return participants
}
