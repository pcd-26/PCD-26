package app_test

import (
	"bytes"
	"strings"
	"testing"

	"odds-and-evens-game/app"
	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/match"
	"odds-and-evens-game/championship/round"
)

// These tests exercise the public CLI helpers from the outside.
func TestParsePlayersCountValid(t *testing.T) {
	count, err := app.ParsePlayersCount([]string{"-players", "8"})
	if err != nil {
		t.Fatalf("expected valid players flag, got error: %v", err)
	}
	if count != 8 {
		t.Fatalf("unexpected players count: got %d want %d", count, 8)
	}
}

// This checks the validation for zero, which must be rejected.
func TestParsePlayersCountRejectsZero(t *testing.T) {
	_, err := app.ParsePlayersCount([]string{"-players", "0"})
	if err == nil {
		t.Fatal("expected error for zero players")
	}
}

// This checks the validation for non power-of-two player counts.
func TestParsePlayersCountRejectsNonPowerOfTwo(t *testing.T) {
	_, err := app.ParsePlayersCount([]string{"-players", "3"})
	if err == nil {
		t.Fatal("expected error for non power-of-two players")
	}
}

// This verifies the full CLI flow using a deterministic match script.
func TestRunWithFactoryFormatsEightPlayerChampionship(t *testing.T) {
	stdout := &bytes.Buffer{}
	stderr := &bytes.Buffer{}

	exitCode := app.RunWithFactory([]string{"-players", "8"}, stdout, stderr, scriptedFactory(map[int]map[int]domain.CoinSide{
		1: {1: domain.Heads, 2: domain.Heads, 3: domain.Heads, 4: domain.Heads},
		2: {1: domain.Heads, 2: domain.Heads},
		3: {1: domain.Heads},
	}))
	if exitCode != 0 {
		t.Fatalf("expected zero exit code, got %d, stderr=%s", exitCode, stderr.String())
	}

	expected := strings.Join([]string{
		"Round 1",
		"",
		"Match 1: Player-1 [heads] vs Player-2 [tails]",
		"Toss: heads",
		"Winner: Player-1",
		"",
		"Match 2: Player-3 [heads] vs Player-4 [tails]",
		"Toss: heads",
		"Winner: Player-3",
		"",
		"Match 3: Player-5 [heads] vs Player-6 [tails]",
		"Toss: heads",
		"Winner: Player-5",
		"",
		"Match 4: Player-7 [heads] vs Player-8 [tails]",
		"Toss: heads",
		"Winner: Player-7",
		"",
		"Round 2",
		"",
		"Match 1: Player-1 [heads] vs Player-3 [tails]",
		"Toss: heads",
		"Winner: Player-1",
		"",
		"Match 2: Player-5 [heads] vs Player-7 [tails]",
		"Toss: heads",
		"Winner: Player-5",
		"",
		"Round 3",
		"",
		"Match 1: Player-1 [heads] vs Player-5 [tails]",
		"Toss: heads",
		"Winner: Player-1",
		"",
		"Champion: Player-1",
		"",
	}, "\n")

	if stdout.String() != expected {
		t.Fatalf("unexpected output:\n--- got ---\n%s--- want ---\n%s", stdout.String(), expected)
	}
}

// This verifies that invalid input is reported as a CLI error.
func TestRunWithFactoryRejectsInvalidInput(t *testing.T) {
	stdout := &bytes.Buffer{}
	stderr := &bytes.Buffer{}

	exitCode := app.RunWithFactory([]string{"-players", "3"}, stdout, stderr, headsOnlyTosserFactory)
	if exitCode == 0 {
		t.Fatal("expected non-zero exit code")
	}
	if !strings.Contains(stderr.String(), "power of two") {
		t.Fatalf("expected validation error, got stderr=%q", stderr.String())
	}
}

// scriptedFactory returns the toss result requested by each match.
func scriptedFactory(script map[int]map[int]domain.CoinSide) round.CoinTosserFactory {
	return func(roundNumber, matchNumber int, firstPlayer, secondPlayer domain.Player) match.CoinTosser {
		return match.NewFixedCoinTosser(script[roundNumber][matchNumber])
	}
}

// headsOnlyTosserFactory always gives heads, so the first player wins.
func headsOnlyTosserFactory(roundNumber, matchNumber int, firstPlayer, secondPlayer domain.Player) match.CoinTosser {
	return match.NewFixedCoinTosser(domain.Heads)
}
