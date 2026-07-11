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
